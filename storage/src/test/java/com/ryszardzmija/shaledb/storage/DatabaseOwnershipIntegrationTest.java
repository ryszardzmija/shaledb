package com.ryszardzmija.shaledb.storage;

import com.ryszardzmija.shaledb.storage.StorageEngineException.MutationOutcome;
import com.ryszardzmija.shaledb.storage.StorageEngineException.Operation;
import com.ryszardzmija.shaledb.storage.StorageEngineException.Reason;
import com.ryszardzmija.shaledb.storage.config.StorageConfig;
import com.ryszardzmija.shaledb.storage.durability.DurabilityMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;

import static com.ryszardzmija.shaledb.storage.KeyValueStoreTestSupport.assertFailure;
import static com.ryszardzmija.shaledb.storage.KeyValueStoreTestSupport.assertValue;
import static com.ryszardzmija.shaledb.storage.KeyValueStoreTestSupport.config;
import static com.ryszardzmija.shaledb.storage.KeyValueStoreTestSupport.hex;
import static com.ryszardzmija.shaledb.storage.KeyValueStoreTestSupport.invalidHeader;
import static com.ryszardzmija.shaledb.storage.KeyValueStoreTestSupport.segmentCount;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.junit.jupiter.api.Assumptions.abort;

class DatabaseOwnershipIntegrationTest {
    private static final byte[] KEY = {1};
    private static final byte[] VALUE = {2, 3};

    @TempDir
    Path directory;

    @Test
    void retainsOwnershipThroughRolloverAndOldCloseCannotAffectTheNextOwner() throws IOException {
        var config = new StorageConfig(16, 3, directory, DurabilityMode.SYNC_EACH_WRITE);
        var owner = new KeyValueStore(config);
        try (owner) {
            assertOpenFailure(config, Reason.DATABASE_LOCKED);
            owner.put(KEY, VALUE);
            assertThat(segmentCount(directory)).isEqualTo(2);
            assertOpenFailure(config, Reason.DATABASE_LOCKED);
            assertValue(owner, KEY, VALUE);
        }

        try (var successor = new KeyValueStore(config)) {
            owner.close();
            assertOpenFailure(config, Reason.DATABASE_LOCKED);
            assertThatIllegalStateException().isThrownBy(() -> owner.put(KEY, VALUE));
            assertThatIllegalStateException().isThrownBy(() -> owner.delete(KEY));
            assertThatIllegalStateException().isThrownBy(() -> owner.get(new byte[] {4}));
            assertValue(successor, KEY, VALUE);
        }
        assertThat(directory.resolve("LOCK")).isRegularFile();
    }

    @Test
    void resolvesRelativeDotAndParentComponentsToTheSameDirectory() throws IOException {
        Path database = Files.createDirectory(directory.resolve("database")).toRealPath();
        Files.createDirectory(database.resolve("child"));
        Path relative = Path.of("").toRealPath().relativize(database);
        assertThat(relative.isAbsolute()).isFalse();

        try (var owner = new KeyValueStore(config(relative, 256))) {
            assertOpenFailure(config(database, 256), Reason.DATABASE_LOCKED);
            assertOpenFailure(config(database.resolve("./child/.."), 256), Reason.DATABASE_LOCKED);
            owner.put(KEY, VALUE);
        }
        try (var reopened = new KeyValueStore(config(database, 256))) {
            assertValue(reopened, KEY, VALUE);
        }
    }

    @Test
    void resolvesDirectorySymlinksBeforeComparingOwnership() throws IOException {
        Path parent = Files.createDirectory(directory.resolve("parent"));
        Path database = Files.createDirectory(parent.resolve("database"));
        Path alias = directory.resolve("alias");
        Path childAlias = directory.resolve("child-alias");
        try {
            Files.createSymbolicLink(alias, database);
            Files.createSymbolicLink(childAlias, Files.createDirectory(database.resolve("child")));
        } catch (UnsupportedOperationException | FileSystemException e) {
            abort("Symbolic links unavailable: " + e);
        }

        try (var owner = new KeyValueStore(config(database, 256))) {
            assertOpenFailure(config(alias, 256), Reason.DATABASE_LOCKED);
            assertOpenFailure(config(childAlias.resolve(".."), 256), Reason.DATABASE_LOCKED);
            owner.put(KEY, VALUE);
        }
        try (var reopened = new KeyValueStore(config(alias, 256))) {
            assertValue(reopened, KEY, VALUE);
        }
    }

    @Test
    void separateDirectoriesCanBeUsedIndependently() throws IOException {
        Path first = Files.createDirectory(directory.resolve("first"));
        Path second = Files.createDirectory(directory.resolve("second"));
        byte[] otherValue = {4};
        try (var secondStore = new KeyValueStore(config(second, 256))) {
            try (var firstStore = new KeyValueStore(config(first, 256))) {
                firstStore.put(KEY, VALUE);
                secondStore.put(KEY, otherValue);
                assertValue(firstStore, KEY, VALUE);
                assertValue(secondStore, KEY, otherValue);
            }
            assertValue(secondStore, KEY, otherValue);
        }
    }

    @Test
    void exactlyOneConcurrentOpenerSucceeds() throws Exception {
        var config = config(directory, 256);
        var start = new CyclicBarrier(2);
        var attempted = new CountDownLatch(2);
        Callable<Boolean> open = () -> {
            start.await(10, SECONDS);
            try (var store = new KeyValueStore(config)) {
                attempted.countDown();
                assertThat(attempted.await(10, SECONDS)).isTrue();
                store.put(KEY, VALUE);
                return true;
            } catch (StorageEngineException failure) {
                attempted.countDown();
                assertThat(failure.operation()).isEqualTo(Operation.OPEN);
                assertThat(failure.reason()).isEqualTo(Reason.DATABASE_LOCKED);
                return false;
            }
        };

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(open);
            var second = executor.submit(open);
            assertThat(first.get(20, SECONDS)).isNotEqualTo(second.get(20, SECONDS));
        }
        try (var reopened = new KeyValueStore(config)) {
            assertValue(reopened, KEY, VALUE);
        }
    }

    @Test
    void contentionPrecedesDiscoveryAndReplayEvenWhenExistingHistoryIsUnreadable() throws IOException {
        var config = config(directory, 256);
        Path active = directory.resolve("segment_0000000000000000001.log");
        try (var owner = new KeyValueStore(config)) {
            owner.put(KEY, VALUE);
            // Introduce invalid fixtures after startup to distinguish the order of the contender's checks.
            byte[] invalid = invalidHeader();
            Files.write(active, invalid);
            Path malformed = Files.write(directory.resolve("segment_invalid.log"), VALUE);
            assertOpenFailure(config, Reason.DATABASE_LOCKED);
            assertThat(Files.readAllBytes(malformed)).containsExactly(VALUE);
            Files.delete(malformed);
            assertOpenFailure(config, Reason.DATABASE_LOCKED);
            assertThat(Files.readAllBytes(active)).containsExactly(invalid);
        }
        assertOpenFailure(config, Reason.STRUCTURAL_CORRUPTION);
    }

    @Test
    void releasesOwnershipAfterFailedDiscoveryAndAfterPartialSegmentLoad() throws IOException {
        var config = config(directory, 256);
        Path malformed = Files.createFile(directory.resolve("segment_invalid.log"));
        assertOpenFailure(config, Reason.INVALID_SEGMENT_METADATA);
        Files.delete(malformed);

        Path older = Files.createFile(directory.resolve("segment_1.log"));
        Path active = Files.write(directory.resolve("segment_2.log"), invalidHeader());
        assertOpenFailure(config, Reason.STRUCTURAL_CORRUPTION);
        assertThat(Files.readAllBytes(active)).containsExactly(invalidHeader());
        Files.write(active, new byte[0]);

        try (var reopened = new KeyValueStore(config)) {
            reopened.put(KEY, VALUE);
            assertValue(reopened, KEY, VALUE);
            assertThat(Files.size(older)).isZero();
        }
    }

    @Test
    void opensPreexistingSegmentsWithoutALockAndPreservesTheirBytes() throws IOException {
        // Independently encoded normal records for key 0080ff, followed by an unrelated tombstone.
        byte[] history = hex("dd057e930003000000020000000080ffc328");
        byte[] latest = hex("dd057e930003000000020000000080ffc3280166497c010200000000000000dead");
        Path older = Files.write(directory.resolve("segment_000009.log"), history);
        Path active = Files.write(directory.resolve("segment_10.log"), latest);
        Path lock = directory.resolve("LOCK");
        assertThat(lock).doesNotExist();

        for (int attempt = 0; attempt < 2; attempt++) {
            try (var store = new KeyValueStore(config(directory, 256))) {
                assertValue(store, hex("0080ff"), hex("c328"));
                assertThat(store.get(hex("dead"))).isEmpty();
            }
            try (var files = Files.list(directory)) {
                assertThat(files).containsExactlyInAnyOrder(older, active, lock);
            }
            assertThat(Files.readAllBytes(older)).containsExactly(history);
            assertThat(Files.readAllBytes(active)).containsExactly(latest);
        }
    }

    private static void assertOpenFailure(StorageConfig config, Reason reason) {
        assertFailure(() -> {
            try (var unexpected = new KeyValueStore(config)) {
                throw new AssertionError("Unexpected successful open");
            }
        }, Operation.OPEN, reason, MutationOutcome.NOT_APPLICABLE);
    }
}
