package com.ryszardzmija.shaledb.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static com.ryszardzmija.shaledb.storage.KeyValueStoreTestSupport.assertValue;
import static com.ryszardzmija.shaledb.storage.KeyValueStoreTestSupport.config;
import static com.ryszardzmija.shaledb.storage.KeyValueStoreTestSupport.hex;
import static com.ryszardzmija.shaledb.storage.KeyValueStoreTestSupport.segmentCount;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class KeyValueStorePersistenceTest {
    private static final int SMALL_SEGMENT_SIZE = 256;
    private static final String FILLER_KEY = "ff00";
    private static final int FILLER_VALUE_SIZE = 96;

    @TempDir
    Path tempDir;

    @Test
    void preservesBinaryEmptyAndOverwrittenValuesAfterReopening() {
        var config = config(tempDir, 4096);
        byte[] binaryKey = hex("0080ff");
        byte[] emptyKey = hex("0080");
        byte[] overwritten = hex("00807f");
        byte[] latest = hex("00fe");

        try (var store = new KeyValueStore(config)) {
            store.put(binaryKey, hex("ff00c328"));
            store.put(emptyKey, new byte[0]);
            store.put(overwritten, hex("10"));
            store.put(overwritten, hex("2030"));
            store.put(overwritten, latest);
        }

        try (var store = new KeyValueStore(config)) {
            assertValue(store, hex("0080ff"), hex("ff00c328"));
            assertValue(store, emptyKey, new byte[0]);
            assertValue(store, overwritten, latest);
            assertThat(store.get(hex("0080fe"))).isEmpty();
        }
    }

    @Test
    void readsRecordsWrittenBeforeAndAfterMultipleRollovers() throws IOException {
        byte[] first = hex("01");
        byte[] second = hex("02");
        byte[] third = hex("03");

        try (var store = new KeyValueStore(config(tempDir, SMALL_SEGMENT_SIZE))) {
            store.put(first, hex("80"));
            advanceToNewSegment(store);
            assertValue(store, first, hex("80"));

            store.put(second, hex("00ff"));
            advanceToNewSegment(store);
            store.put(third, hex("fe00"));

            assertValue(store, first, hex("80"));
            assertValue(store, second, hex("00ff"));
            assertValue(store, third, hex("fe00"));
            assertThat(store.get(hex("04"))).isEmpty();
        }
    }

    @Test
    void replaysLatestValuesAndTombstonesAcrossSegments() throws IOException {
        var config = config(tempDir, SMALL_SEGMENT_SIZE);
        byte[] untouched = hex("01");
        byte[] overwritten = hex("02");
        byte[] deleted = hex("03");
        byte[] reinserted = hex("04");
        byte[] missing = hex("05");

        try (var store = new KeyValueStore(config)) {
            store.put(untouched, hex("0080ff"));
            store.put(overwritten, hex("10"));
            store.put(deleted, hex("11"));
            store.put(reinserted, hex("12"));

            advanceToNewSegment(store);
            store.put(overwritten, hex("20"));
            store.put(deleted, hex("21"));
            store.put(reinserted, hex("22"));
            assertValue(store, overwritten, hex("20"));

            advanceToNewSegment(store);
            store.put(overwritten, hex("30"));
            store.put(deleted, hex("31"));
            assertValue(store, overwritten, hex("30"));
            assertValue(store, deleted, hex("31"));

            advanceToNewSegment(store);
            store.delete(deleted);
            store.delete(reinserted);
            assertThat(store.get(deleted)).isEmpty();
            assertThat(store.get(reinserted)).isEmpty();

            advanceToNewSegment(store);
            assertThat(store.get(deleted)).isEmpty();
            assertThat(store.get(reinserted)).isEmpty();
            store.delete(deleted);
            assertThat(store.get(deleted)).isEmpty();
        }

        try (var store = new KeyValueStore(config)) {
            assertValue(store, untouched, hex("0080ff"));
            assertValue(store, overwritten, hex("30"));
            assertValue(store, hex(FILLER_KEY), new byte[FILLER_VALUE_SIZE]);
            assertThat(store.get(deleted)).isEmpty();
            assertThat(store.get(reinserted)).isEmpty();
            assertThat(store.get(missing)).isEmpty();

            store.delete(deleted);
            store.put(overwritten, hex("4000"));
            store.put(reinserted, new byte[0]);
            assertThat(store.get(deleted)).isEmpty();
            assertValue(store, overwritten, hex("4000"));
            assertValue(store, reinserted, new byte[0]);
        }

        try (var store = new KeyValueStore(config)) {
            assertValue(store, untouched, hex("0080ff"));
            assertValue(store, overwritten, hex("4000"));
            assertValue(store, reinserted, new byte[0]);
            assertValue(store, hex(FILLER_KEY), new byte[FILLER_VALUE_SIZE]);
            assertThat(store.get(deleted)).isEmpty();
            assertThat(store.get(missing)).isEmpty();
        }
    }

    @Test
    void appendsToAReopenedStoreAndRollsOverBeforeAnotherRestart() throws IOException {
        var config = config(tempDir, SMALL_SEGMENT_SIZE);
        byte[] unchanged = hex("01");
        byte[] overwritten = hex("02");
        byte[] deleted = hex("03");
        byte[] insertedAfterReopen = hex("04");

        try (var store = new KeyValueStore(config)) {
            store.put(unchanged, hex("80"));
            store.put(overwritten, hex("81"));
            store.put(deleted, hex("82"));
        }
        assertThat(segmentCount(tempDir)).as("populated segment to reopen for append").isOne();

        try (var store = new KeyValueStore(config)) {
            store.put(insertedAfterReopen, hex("00ff"));
            store.put(overwritten, hex("9000"));
            store.delete(deleted);

            assertValue(store, unchanged, hex("80"));
            assertValue(store, overwritten, hex("9000"));
            assertValue(store, insertedAfterReopen, hex("00ff"));
            assertThat(store.get(deleted)).isEmpty();
            advanceToNewSegment(store);
        }

        try (var store = new KeyValueStore(config)) {
            assertValue(store, unchanged, hex("80"));
            assertValue(store, overwritten, hex("9000"));
            assertValue(store, insertedAfterReopen, hex("00ff"));
            assertValue(store, hex(FILLER_KEY), new byte[FILLER_VALUE_SIZE]);
            assertThat(store.get(deleted)).isEmpty();
        }
    }

    @Test
    void canReopenAnEmptyDatabaseAndPersistItsFirstWrite() {
        var config = config(tempDir, 4096);
        byte[] key = hex("00ff");

        try (var store = new KeyValueStore(config)) {
            assertThat(store.get(key)).isEmpty();
        }

        try (var store = new KeyValueStore(config)) {
            assertThat(store.get(key)).isEmpty();
            store.put(key, hex("8000"));
            assertValue(store, key, hex("8000"));
        }

        try (var store = new KeyValueStore(config)) {
            assertValue(store, key, hex("8000"));
        }
    }

    @Test
    void rejectedMutationsLeaveTheDatabaseReadableAfterReopening() {
        var config = config(tempDir, 4096);
        byte[] existing = hex("01");
        byte[] missing = hex("02");
        byte[] emptyKey = new byte[0];

        try (var store = new KeyValueStore(config)) {
            store.put(existing, hex("8000"));

            assertThatIllegalArgumentException().isThrownBy(() -> store.put(null, hex("ff")));
            assertThatIllegalArgumentException().isThrownBy(() -> store.put(emptyKey, hex("ff")));
            assertThatIllegalArgumentException().isThrownBy(() -> store.put(existing, null));
            assertThatIllegalArgumentException().isThrownBy(() -> store.delete(null));
            assertThatIllegalArgumentException().isThrownBy(() -> store.delete(emptyKey));
        }

        try (var store = new KeyValueStore(config)) {
            assertValue(store, existing, hex("8000"));
            assertThat(store.get(missing)).isEmpty();
        }
    }

    private void advanceToNewSegment(KeyValueStore store) throws IOException {
        byte[] key = hex(FILLER_KEY);
        byte[] value = new byte[FILLER_VALUE_SIZE];
        long initialCount = segmentCount(tempDir);
        long currentCount = initialCount;
        for (int writes = 0; writes < 8 && currentCount == initialCount; writes++) {
            store.put(key, value);
            currentCount = segmentCount(tempDir);
        }

        assertThat(currentCount).as("segment count after filler writes").isGreaterThan(initialCount);
    }
}
