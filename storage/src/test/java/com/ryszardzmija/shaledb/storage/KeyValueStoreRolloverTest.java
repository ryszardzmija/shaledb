package com.ryszardzmija.shaledb.storage;

import com.ryszardzmija.shaledb.storage.config.StorageConfig;
import com.ryszardzmija.shaledb.storage.durability.DurabilityMode;
import com.ryszardzmija.shaledb.storage.serialization.io.ReadRequest;
import com.ryszardzmija.shaledb.storage.serialization.io.RecordReader;
import com.ryszardzmija.shaledb.storage.serialization.record.RecordType;
import com.ryszardzmija.shaledb.storage.serialization.spec.FormatInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static com.ryszardzmija.shaledb.storage.KeyValueStoreTestSupport.assertValue;
import static com.ryszardzmija.shaledb.storage.KeyValueStoreTestSupport.segmentCount;
import static java.nio.file.StandardOpenOption.READ;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class KeyValueStoreRolloverTest {
    private static final byte[] KEY = {(byte) 0x80};
    private static final byte[] OTHER = {(byte) 0xff};
    private static final byte[] VALUE = {1};
    private static final byte[] EMPTY = {};
    private static final int PUT_SIZE = FormatInfo.getEncodedRecordSize(KEY.length + VALUE.length);
    private static final int TOMBSTONE_SIZE = FormatInfo.getEncodedRecordSize(KEY.length);

    @TempDir
    Path directory;

    @Test
    void putAndTombstoneFitInOneSegmentAndExactFitDefersRollover() throws IOException {
        int capacity = PUT_SIZE + TOMBSTONE_SIZE;
        StorageConfig config = config(capacity, 2);
        try (KeyValueStore store = new KeyValueStore(config)) {
            store.put(KEY, VALUE);
            assertThat(segmentCount(directory)).isOne();
            assertThat(Files.size(segment(1))).isEqualTo(PUT_SIZE);
            assertValue(store, KEY, VALUE);

            store.delete(KEY);
            assertThat(store.get(KEY)).isEmpty();
            assertThat(segmentCount(directory)).isOne();
            assertThat(Files.size(segment(1))).isEqualTo(capacity);
        }

        byte[] full = Files.readAllBytes(segment(1));
        try (KeyValueStore store = new KeyValueStore(config)) {
            assertThat(segmentCount(directory)).isOne();
            store.put(OTHER, VALUE);
            assertThat(segmentCount(directory)).isEqualTo(2);
            assertThat(Files.readAllBytes(segment(1))).containsExactly(full);
            assertThat(Files.size(segment(2))).isEqualTo(PUT_SIZE);
            assertThat(store.get(KEY)).isEmpty();
            assertValue(store, OTHER, VALUE);
        }
    }

    @Test
    void oneByteHeaderInclusiveOverflowRollsOverBeforeThePut() throws IOException {
        int capacity = 2 * PUT_SIZE - 1;
        try (KeyValueStore store = new KeyValueStore(config(capacity, 2))) {
            store.put(KEY, VALUE);
            byte[] before = Files.readAllBytes(segment(1));

            store.put(OTHER, VALUE);

            assertThat(segmentCount(directory)).isEqualTo(2);
            assertThat(Files.readAllBytes(segment(1))).containsExactly(before);
            assertThat(Files.size(segment(2))).isEqualTo(PUT_SIZE);
            assertValue(store, KEY, VALUE);
            assertValue(store, OTHER, VALUE);
        }
    }

    @Test
    void recordOverTotalCapacityLeavesFullSegmentIntactAndStoreUsable() throws IOException {
        StorageConfig config = config(PUT_SIZE, 2);
        try (KeyValueStore store = new KeyValueStore(config)) {
            store.put(KEY, VALUE);
            byte[] full = Files.readAllBytes(segment(1));

            assertThatIllegalArgumentException().isThrownBy(() -> store.put(KEY, new byte[2]));
            assertThatIllegalArgumentException().isThrownBy(() -> store.delete(new byte[3]));
            assertThat(segmentCount(directory)).isOne();
            assertThat(Files.readAllBytes(segment(1))).containsExactly(full);
            assertValue(store, KEY, VALUE);

            store.put(KEY, EMPTY);
            assertThat(segmentCount(directory)).isEqualTo(2);
            assertThat(Files.readAllBytes(segment(1))).containsExactly(full);
            assertValue(store, KEY, EMPTY);
        }
    }

    @Test
    void repeatedRolloversPreserveBytesOffsetsAndLatestRecordSemantics() throws IOException {
        int capacity = PUT_SIZE + TOMBSTONE_SIZE;
        StorageConfig config = config(capacity, 2);
        byte[] first;
        byte[] second;
        try (KeyValueStore store = new KeyValueStore(config)) {
            store.put(KEY, VALUE);
            store.put(OTHER, EMPTY);
            first = Files.readAllBytes(segment(1));

            store.put(KEY, new byte[] {2});
            assertValue(store, KEY, new byte[] {2});
            store.delete(OTHER);
            second = Files.readAllBytes(segment(2));

            store.delete(KEY);
            assertThat(store.get(KEY)).isEmpty();
            assertThat(store.get(OTHER)).isEmpty();
            store.put(OTHER, VALUE);
            byte[] third = Files.readAllBytes(segment(3));

            store.put(KEY, EMPTY);
            assertValue(store, KEY, EMPTY);
            assertValue(store, OTHER, VALUE);
            assertThat(segmentCount(directory)).isEqualTo(4);
            assertThat(Files.readAllBytes(segment(1))).containsExactly(first);
            assertThat(Files.readAllBytes(segment(2))).containsExactly(second);
            assertThat(Files.readAllBytes(segment(3))).containsExactly(third);
            assertThat(first).hasSize(capacity);
            assertThat(second).hasSize(capacity);
            assertThat(third).hasSize(capacity);
            assertThat(Files.size(segment(4))).isEqualTo(TOMBSTONE_SIZE);
        }

        try (FileChannel secondFile = FileChannel.open(segment(2), READ);
             FileChannel thirdFile = FileChannel.open(segment(3), READ)) {
            var secondReader = new RecordReader(secondFile, 2);
            var normal = secondReader.read(new ReadRequest(0));
            assertThat(normal.nextRecordOffset()).isEqualTo(PUT_SIZE);
            var tombstone = secondReader.read(new ReadRequest(PUT_SIZE));
            assertThat(tombstone.recordType()).isEqualTo(RecordType.TOMBSTONE);
            assertThat(tombstone.recordPayload().key()).containsExactly(OTHER);
            assertThat(tombstone.nextRecordOffset()).isEqualTo(capacity);

            var thirdReader = new RecordReader(thirdFile, 2);
            var reinserted = thirdReader.read(new ReadRequest(TOMBSTONE_SIZE));
            assertThat(reinserted.recordPayload().value()).containsExactly(VALUE);
            assertThat(reinserted.nextRecordOffset()).isEqualTo(capacity);
        }
        try (KeyValueStore reopened = new KeyValueStore(config)) {
            assertValue(reopened, KEY, EMPTY);
            assertValue(reopened, OTHER, VALUE);
        }
    }

    private StorageConfig config(int capacity, int payload) {
        return new StorageConfig(capacity, payload, directory, DurabilityMode.SYNC_EACH_WRITE);
    }

    private Path segment(long id) {
        return directory.resolve(String.format(Locale.ROOT, "segment_%019d.log", id));
    }
}
