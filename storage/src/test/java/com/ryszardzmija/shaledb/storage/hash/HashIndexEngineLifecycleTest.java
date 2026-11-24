package com.ryszardzmija.shaledb.storage.hash;

import com.ryszardzmija.shaledb.storage.StorageEngineException;
import com.ryszardzmija.shaledb.storage.config.StorageConfig;
import com.ryszardzmija.shaledb.storage.durability.DurabilityMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class HashIndexEngineLifecycleTest {
    private static final byte[] HISTORICAL = {1};
    private static final byte[] DELETED = {2};
    private static final byte[] EMPTY = {3};
    private static final byte[] PRESENT = {4};
    private static final byte[] MISSING = {5};
    private static final byte[] VALUE = {6};

    @TempDir
    Path directory;

    @Test
    void closePermanentlyRejectsOperationsAndPreservesTheHistoryAcrossMultipleRollovers() throws IOException {
        HashIndexEngine engine = open();
        Map<Path, byte[]> history;
        try {
            // Each pair crosses the 48-byte rollover threshold.
            engine.put(HISTORICAL, new byte[16]);
            engine.put(DELETED, new byte[16]);
            engine.put(HISTORICAL, new byte[16]);
            engine.put(DELETED, new byte[16]);
            engine.delete(DELETED);
            engine.put(EMPTY, new byte[0]);
            engine.put(PRESENT, VALUE);
            assertContents(engine);
            history = snapshot();
            assertThat(history).hasSize(4); // Three segment files and LOCK.
        } finally {
            engine.close();
        }
        assertUnavailable(engine, null);
        engine.markFailed(new IOException("notification after close"));
        assertUnavailable(engine, null);
        assertSnapshot(history);

        HashIndexEngine next = open();
        try {
            engine.close();
            assertContents(next);
            assertLocked();
        } finally {
            next.close();
        }
        assertSnapshot(history);
    }

    @Test
    void failedEnginePreservesTheFirstCauseAndHoldsOwnershipUntilClosed() throws IOException {
        HashIndexEngine engine = open();
        Map<Path, byte[]> history;
        IOException first = new IOException("terminal failure");
        try {
            engine.put(PRESENT, VALUE);
            history = snapshot();

            engine.markFailed(first);
            engine.markFailed(new IOException("repeated failure notification"));
            assertUnavailable(engine, first);
            assertLocked();
            assertSnapshot(history);
        } finally {
            engine.close();
        }

        assertUnavailable(engine, null);
        engine.close();
        assertSnapshot(history);
        HashIndexEngine next = open();
        try {
            assertThat(next.get(PRESENT)).hasValueSatisfying(value -> assertThat(value).containsExactly(VALUE));
        } finally {
            next.close();
        }
    }

    private HashIndexEngine open() {
        return new HashIndexEngine(new StorageConfig(48, 32, directory, DurabilityMode.SYNC_EACH_WRITE));
    }

    private void assertLocked() {
        StorageEngineException failure = catchThrowableOfType(() -> {
            open().close();
            throw new AssertionError("Contender opened the database");
        }, StorageEngineException.class);
        assertThat(failure).isNotNull();
        assertThat(failure.reason()).isEqualTo(StorageEngineException.Reason.DATABASE_LOCKED);
    }

    private static void assertContents(HashIndexEngine engine) {
        assertThat(engine.get(HISTORICAL)).hasValueSatisfying(value -> assertThat(value).containsExactly(new byte[16]));
        assertThat(engine.get(PRESENT)).hasValueSatisfying(value -> assertThat(value).containsExactly(VALUE));
        assertThat(engine.get(EMPTY)).hasValueSatisfying(value -> assertThat(value).isEmpty());
        assertThat(engine.get(DELETED)).isEmpty();
        assertThat(engine.get(MISSING)).isEmpty();
    }

    private static void assertUnavailable(HashIndexEngine engine, Throwable cause) {
        for (byte[] key : List.of(HISTORICAL, DELETED, EMPTY, PRESENT, MISSING)) {
            assertThatIllegalStateException().isThrownBy(() -> engine.get(key)).withCause(cause);
            assertThatIllegalStateException().isThrownBy(() -> engine.put(key, new byte[0])).withCause(cause);
            assertThatIllegalStateException().isThrownBy(() -> engine.delete(key)).withCause(cause);
        }
        assertThatIllegalStateException().isThrownBy(() -> engine.get(null)).withCause(cause);
        assertThatIllegalStateException().isThrownBy(() -> engine.put(null, null)).withCause(cause);
        assertThatIllegalStateException().isThrownBy(() -> engine.delete(null)).withCause(cause);
    }

    private Map<Path, byte[]> snapshot() throws IOException {
        Map<Path, byte[]> files = new LinkedHashMap<>();
        try (var entries = Files.list(directory)) {
            for (Path path : entries.toList()) {
                files.put(path, Files.readAllBytes(path));
            }
        }
        return files;
    }

    private void assertSnapshot(Map<Path, byte[]> expected) throws IOException {
        Map<Path, byte[]> actual = snapshot();
        assertThat(actual).containsOnlyKeys(expected.keySet());
        for (var entry : expected.entrySet()) {
            assertThat(actual.get(entry.getKey())).containsExactly(entry.getValue());
        }
    }
}
