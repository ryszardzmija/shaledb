package com.ryszardzmija.shaledb.storage;

import com.ryszardzmija.shaledb.storage.StorageEngineException.Operation;
import com.ryszardzmija.shaledb.storage.StorageEngineException.Reason;
import com.ryszardzmija.shaledb.storage.config.StorageConfig;
import com.ryszardzmija.shaledb.storage.durability.DurabilityMode;
import com.ryszardzmija.shaledb.storage.serialization.io.DataCorruptionException;
import com.ryszardzmija.shaledb.storage.serialization.io.RecordIOException;
import com.ryszardzmija.shaledb.storage.serialization.spec.FormatInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static com.ryszardzmija.shaledb.storage.KeyValueStoreTestSupport.assertFailure;
import static com.ryszardzmija.shaledb.storage.KeyValueStoreTestSupport.assertValue;
import static com.ryszardzmija.shaledb.storage.KeyValueStoreTestSupport.hex;
import static com.ryszardzmija.shaledb.storage.KeyValueStoreTestSupport.segmentCount;
import static com.ryszardzmija.shaledb.storage.StorageEngineException.MutationOutcome.NOT_APPLICABLE;
import static org.assertj.core.api.Assertions.assertThat;

class KeyValueStoreRecoveryTest {
    private static final byte[] KEY = {1, 2};
    private static final byte[] VALUE = {3, 4};
    private static final byte[] REPLACEMENT = {5, 6, 7};
    private static final byte[] EMPTY_KEY = {0, (byte) 0x80, (byte) 0xff};
    private static final byte[] EMPTY = {};
    private static final byte[] FIRST = hex("f930972d00020000000200000001020304");
    private static final byte[] OVERWRITE = hex("82048c0f0002000000030000000102050607");
    private static final byte[] EMPTY_OVERWRITE = hex("c3df08cb0002000000000000000102");
    private static final byte[] TOMBSTONE = hex("0228795c0102000000000000000102");

    @TempDir
    Path directory;

    @Test
    void recoversEveryProperPrefixOfAnOverwriteAndRetainsBothEndpoints() throws IOException {
        assertAllPrefixes(OVERWRITE, REPLACEMENT);
    }

    @Test
    void recoversEveryProperPrefixOfATombstoneAndRetainsBothEndpoints() throws IOException {
        assertAllPrefixes(TOMBSTONE, null);
    }

    @Test
    void recoversEveryProperPrefixOfAnEmptyValueOverwriteAndRetainsBothEndpoints() throws IOException {
        assertAllPrefixes(EMPTY_OVERWRITE, EMPTY);
    }

    private void assertAllPrefixes(byte[] candidate, byte[] valueAfterCompleteRecord) throws IOException {
        for (int length = 0; length <= candidate.length; length++) {
            Path db = Files.createDirectory(directory.resolve("prefix-" + length));
            Path active = segment(db, 1);
            StorageConfig config = config(db, 30, 128);
            try (KeyValueStore store = new KeyValueStore(config)) {
                store.put(EMPTY_KEY, EMPTY);
                store.put(KEY, VALUE);
            }
            byte[] original = Files.readAllBytes(active);
            Files.write(active, join(original, Arrays.copyOf(candidate, length)));
            boolean complete = length == candidate.length;
            byte[] retained = complete ? join(original, candidate) : original;
            byte[] expected = complete ? valueAfterCompleteRecord : VALUE;

            try (KeyValueStore reopened = new KeyValueStore(config)) {
                assertThat(Files.readAllBytes(active)).as("prefix length %d", length).containsExactly(retained);
                assertValue(reopened, EMPTY_KEY, EMPTY);
                if (expected == null) {
                    assertThat(reopened.get(KEY)).isEmpty();
                } else {
                    assertValue(reopened, KEY, expected);
                }
                reopened.put(KEY, REPLACEMENT);
                assertThat(Files.readAllBytes(active)).containsExactly(join(retained, OVERWRITE));
                assertThat(segmentCount(db)).isOne();
            }
            try (KeyValueStore reopened = new KeyValueStore(config)) {
                assertValue(reopened, KEY, REPLACEMENT);
                assertValue(reopened, EMPTY_KEY, EMPTY);
            }
        }
    }

    @Test
    void recoversAnIncompleteFirstTombstoneWithoutShadowingHistoricalValue() throws IOException {
        Path historical = Files.write(segment(directory, 1), FIRST);
        Path active = Files.write(segment(directory, 2), Arrays.copyOf(TOMBSTONE, TOMBSTONE.length - 1));
        StorageConfig config = config(directory, 30, 128);

        for (int reopen = 0; reopen < 3; reopen++) {
            try (KeyValueStore store = new KeyValueStore(config)) {
                assertThat(active).isEmptyFile();
                assertThat(segmentCount(directory)).isEqualTo(2);
                assertValue(store, KEY, VALUE);
            }
        }
        try (KeyValueStore store = new KeyValueStore(config)) {
            store.delete(KEY);
            assertThat(store.get(KEY)).isEmpty();
            assertThat(Files.readAllBytes(active)).containsExactly(TOMBSTONE);
        }
        try (KeyValueStore store = new KeyValueStore(config)) {
            assertThat(store.get(KEY)).isEmpty();
            store.put(KEY, REPLACEMENT);
            assertThat(Files.readAllBytes(active)).containsExactly(join(TOMBSTONE, OVERWRITE));
        }
        assertThat(Files.readAllBytes(historical)).containsExactly(FIRST);
    }

    @Test
    void incompleteActiveOverwriteKeepsTheHistoricalLocation() throws IOException {
        Path historical = Files.write(segment(directory, 1), FIRST);
        Path active = Files.write(segment(directory, 5), Arrays.copyOf(OVERWRITE, OVERWRITE.length - 1));
        try (KeyValueStore store = new KeyValueStore(config(directory, 30, 128))) {
            assertValue(store, KEY, VALUE);
            assertThat(active).isEmptyFile();
        }
        assertThat(Files.readAllBytes(historical)).containsExactly(FIRST);
    }

    @Test
    void incompleteActiveReinsertionKeepsTheHistoricalDeletion() throws IOException {
        Path oldest = Files.write(segment(directory, 1), FIRST);
        Path historical = Files.write(segment(directory, 2), TOMBSTONE);
        Path active = Files.write(segment(directory, 5), Arrays.copyOf(OVERWRITE, OVERWRITE.length - 1));
        for (int i = 0; i < 2; i++) {
            try (KeyValueStore store = new KeyValueStore(config(directory, 30, 128))) {
                assertThat(store.get(KEY)).isEmpty();
                assertThat(active).isEmptyFile();
            }
        }
        assertThat(Files.readAllBytes(oldest)).containsExactly(FIRST);
        assertThat(Files.readAllBytes(historical)).containsExactly(TOMBSTONE);
    }

    @Test
    void checksCapacityAtTheRecoveredBoundaryAndRetiresCompleteHistory() throws IOException {
        int combinedSize = FIRST.length + OVERWRITE.length;
        for (int maxSize : new int[] {combinedSize + 1, combinedSize, combinedSize - 1}) {
            Path db = Files.createDirectory(directory.resolve("capacity-" + maxSize));
            Path active = Files.write(segment(db, 1), join(FIRST, Arrays.copyOf(OVERWRITE, 4)));
            StorageConfig config = config(db, 5, maxSize);
            boolean fits = maxSize >= combinedSize;
            byte[] historicalBytes = fits ? join(FIRST, OVERWRITE) : FIRST;
            try (KeyValueStore store = new KeyValueStore(config)) {
                assertThat(Files.readAllBytes(active)).containsExactly(FIRST);
                assertValue(store, KEY, VALUE);
                store.put(KEY, REPLACEMENT);
                if (fits) {
                    assertThat(segmentCount(db)).isOne();
                    assertThat(Files.readAllBytes(active)).containsExactly(historicalBytes);
                    store.put(KEY, REPLACEMENT);
                }
                assertThat(Files.readAllBytes(segment(db, 2))).containsExactly(OVERWRITE);
                assertThat(Files.readAllBytes(active)).containsExactly(historicalBytes);
                assertThat(segmentCount(db)).isEqualTo(2);
            }
            try (KeyValueStore reopened = new KeyValueStore(config)) {
                assertValue(reopened, KEY, REPLACEMENT);
            }
            assertThat(Files.readAllBytes(active)).containsExactly(historicalBytes);
        }
    }

    @Test
    void preservesARecoveredSegmentAboveTheReducedRolloverThreshold() throws IOException {
        byte[] validPrefix = join(FIRST, FIRST);
        Path active = Files.write(segment(directory, 1), join(validPrefix, Arrays.copyOf(TOMBSTONE, 4)));
        StorageConfig config = config(directory, 4, FIRST.length);
        try (KeyValueStore store = new KeyValueStore(config)) {
            assertThat(Files.readAllBytes(active)).containsExactly(validPrefix);
            assertValue(store, KEY, VALUE);
            store.delete(KEY);
            assertThat(Files.readAllBytes(segment(directory, 2))).containsExactly(TOMBSTONE);
        }
        try (KeyValueStore reopened = new KeyValueStore(config)) {
            assertThat(reopened.get(KEY)).isEmpty();
        }
        assertThat(Files.readAllBytes(active)).containsExactly(validPrefix);
    }

    @Test
    void rejectsCompleteChecksumFailuresAtTheEndInTheMiddleAndInHistory() throws IOException {
        byte[] corrupt = OVERWRITE.clone();
        corrupt[0] ^= 1;
        Path segment = segment(directory, 1);
        for (byte[] bytes : new byte[][] {join(FIRST, corrupt), join(join(FIRST, corrupt), FIRST)}) {
            Files.write(segment, bytes);
            assertChecksumFailure(config(directory, 30, 128));
            assertThat(Files.readAllBytes(segment)).containsExactly(bytes);
        }
        byte[] historical = Files.readAllBytes(segment);
        byte[] incompleteTail = Arrays.copyOf(TOMBSTONE, 3);
        Path active = Files.write(segment(directory, 2), incompleteTail);
        assertChecksumFailure(config(directory, 30, 128));
        assertThat(Files.readAllBytes(segment)).containsExactly(historical);
        assertThat(Files.readAllBytes(active)).containsExactly(incompleteTail);
    }

    @Test
    void rejectsInvalidCompleteHeadersBeforeConsideringTailRecovery() throws IOException {
        Path active = segment(directory, 1);
        for (byte[] invalid : new byte[][] {
                header(2, 2, 0), header(0, 0, 0), header(0, -1, 0), header(0, 2, -1),
                header(1, 2, 1), header(0, 1, Integer.MAX_VALUE - 13), header(0, Integer.MAX_VALUE, 1)
        }) {
            byte[] bytes = join(FIRST, invalid);
            Files.write(active, bytes);
            StorageEngineException failure = assertFailure(() -> new KeyValueStore(config(directory, 30, 128)),
                    Operation.OPEN, Reason.STRUCTURAL_CORRUPTION, NOT_APPLICABLE);
            assertThat(failure.getCause().getCause().getCause().getCause())
                    .isInstanceOfSatisfying(DataCorruptionException.class, cause -> {
                        assertThat(cause.kind()).isEqualTo(DataCorruptionException.Kind.STRUCTURE);
                        assertThat(cause.recordOffset()).isEqualTo(FIRST.length);
                    });
            assertThat(Files.readAllBytes(active)).containsExactly(bytes);
        }
    }

    @Test
    void reducedPayloadLimitRejectsBothCompleteAndIncompleteRecordsUntilReopenedCompatibly() throws IOException {
        Path active = segment(directory, 1);
        for (int length : new int[] {FormatInfo.getHeaderSize(), OVERWRITE.length}) {
            byte[] bytes = join(FIRST, Arrays.copyOf(OVERWRITE, length));
            Files.write(active, bytes);
            StorageEngineException failure = assertFailure(() -> new KeyValueStore(config(directory, 4, 128)),
                    Operation.OPEN, Reason.INCOMPATIBLE_RECORD_SIZE, NOT_APPLICABLE);
            assertThat(failure.getCause().getCause().getCause().getCause())
                    .isInstanceOfSatisfying(RecordIOException.ReadLimitExceeded.class,
                            cause -> assertThat(cause.recordOffset()).isEqualTo(FIRST.length));
            assertThat(Files.readAllBytes(active)).containsExactly(bytes);

            boolean complete = length == OVERWRITE.length;
            try (KeyValueStore store = new KeyValueStore(config(directory, 5, 128))) {
                assertValue(store, KEY, complete ? REPLACEMENT : VALUE);
                assertThat(Files.readAllBytes(active)).containsExactly(complete ? bytes : FIRST);
            }
        }
    }

    private static void assertChecksumFailure(StorageConfig config) {
        StorageEngineException failure = assertFailure(() -> new KeyValueStore(config),
                Operation.OPEN, Reason.CHECKSUM_CORRUPTION, NOT_APPLICABLE);
        assertThat(failure).hasRootCauseInstanceOf(DataCorruptionException.class);
        DataCorruptionException cause = (DataCorruptionException) failure.getCause().getCause().getCause().getCause();
        assertThat(cause.kind()).isEqualTo(DataCorruptionException.Kind.CHECKSUM);
        assertThat(cause.recordOffset()).isEqualTo(FIRST.length);
    }

    private static Path segment(Path dir, long id) {
        return dir.resolve("segment_%019d.log".formatted(id));
    }

    private static StorageConfig config(Path dir, int payloadLimit, int segmentLimit) {
        return new StorageConfig(segmentLimit, payloadLimit, dir, DurabilityMode.SYNC_EACH_WRITE);
    }

    private static byte[] join(byte[] first, byte[] second) {
        return ByteBuffer.allocate(first.length + second.length).put(first).put(second).array();
    }

    private static byte[] header(int type, int keyLength, int valueLength) {
        return ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0).put((byte) type).putInt(keyLength).putInt(valueLength).array();
    }
}
