package com.ryszardzmija.shaledb.storage;

import com.ryszardzmija.shaledb.storage.config.StorageConfig;
import com.ryszardzmija.shaledb.storage.durability.DurabilityMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

import static com.ryszardzmija.shaledb.storage.KeyValueStoreTestSupport.assertValue;
import static com.ryszardzmija.shaledb.storage.KeyValueStoreTestSupport.hex;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class KeyValueStoreValidationTest {
    private static final int MAX_PAYLOAD_SIZE = 8;

    @TempDir
    Path tempDir;

    @Test
    void oversizedPutLeavesSegmentFilesAndContentsUnchanged() throws IOException {
        var config = config(64);
        byte[] existing = hex("01");
        byte[] newKey = hex("02030405");
        byte[] later = hex("06");

        try (var store = new KeyValueStore(config)) {
            store.put(existing, hex("80ff"));
            var before = segmentContents();

            assertThatIllegalArgumentException().isThrownBy(() -> store.put(newKey, new byte[5]))
                    .withMessageContaining("Record payload size 9 exceeds configured maxPayloadSize 8");

            assertThat(segmentContents()).isEqualTo(before);
            assertThat(store.get(newKey)).isEmpty();
            assertValue(store, existing, hex("80ff"));
            store.put(later, hex("00fe"));
        }

        try (var reopened = new KeyValueStore(config)) {
            assertValue(reopened, existing, hex("80ff"));
            assertValue(reopened, later, hex("00fe"));
            assertThat(reopened.get(newKey)).isEmpty();
        }
    }

    @Test
    void oversizedOverwritePreservesOriginalValueAndSegmentContents() throws IOException {
        var config = config(64);
        byte[] key = hex("00ff");
        byte[] value = hex("8081");

        try (var store = new KeyValueStore(config)) {
            store.put(key, value);
            var before = segmentContents();

            assertThatIllegalArgumentException().isThrownBy(() -> store.put(key, new byte[7]));

            assertThat(segmentContents()).isEqualTo(before);
            assertValue(store, key, value);
        }

        try (var reopened = new KeyValueStore(config)) {
            assertValue(reopened, key, value);
        }
    }

    @Test
    void oversizedDeleteLeavesSegmentFilesAndContentsUnchanged() throws IOException {
        var config = config(64);
        byte[] oversizedKey = new byte[MAX_PAYLOAD_SIZE + 1];
        byte[] key = hex("80");

        try (var store = new KeyValueStore(config)) {
            var before = segmentContents();

            assertThatIllegalArgumentException().isThrownBy(() -> store.delete(oversizedKey))
                    .withMessageContaining("Record payload size 9 exceeds configured maxPayloadSize 8");

            assertThat(segmentContents()).isEqualTo(before);
            assertThat(store.get(oversizedKey)).isEmpty();
            store.put(key, hex("ff"));
            assertValue(store, key, hex("ff"));
        }

        try (var reopened = new KeyValueStore(config)) {
            assertValue(reopened, key, hex("ff"));
            assertThat(reopened.get(oversizedKey)).isEmpty();
        }
    }

    @Test
    void rejectedMutationsNearSegmentBoundaryDoNotAppendOrRollOver() throws IOException {
        byte[] deleted = hex("01");
        try (var store = new KeyValueStore(config(32))) {
            store.put(deleted, hex("80ff"));
            store.delete(deleted);
            var before = segmentContents();
            assertThat(before).hasSize(1);
            assertThat(before.values()).singleElement().asString().hasSize(60); // 30 bytes in hex

            assertThatIllegalArgumentException().isThrownBy(() -> store.put(deleted, new byte[8]));
            assertThat(segmentContents()).isEqualTo(before);
            assertThatIllegalArgumentException().isThrownBy(() -> store.delete(new byte[MAX_PAYLOAD_SIZE + 1]));
            assertThat(segmentContents()).isEqualTo(before);
            assertThat(store.get(deleted)).isEmpty();
        }
    }

    @Test
    void exactLimitPutFitsAnEmptySegmentAndSurvivesReopen() {
        var config = config(21);
        byte[] key = hex("00ff");
        byte[] value = hex("8001fe030405");

        try (var store = new KeyValueStore(config)) {
            store.put(key, value);
            assertValue(store, key, value);
        }

        try (var reopened = new KeyValueStore(config)) {
            assertValue(reopened, key, value);
        }
    }

    @Test
    void exactLimitTombstoneShadowsAnEmptyValueAfterReopen() {
        var config = config(21);
        byte[] key = hex("0080ff0102030405");

        try (var store = new KeyValueStore(config)) {
            store.put(key, new byte[0]);
            assertValue(store, key, new byte[0]);
            store.delete(key);
            assertThat(store.get(key)).isEmpty();
        }

        try (var reopened = new KeyValueStore(config)) {
            assertThat(reopened.get(key)).isEmpty();
        }
    }

    private StorageConfig config(long maxSegmentSize) {
        return new StorageConfig(maxSegmentSize, MAX_PAYLOAD_SIZE, tempDir, DurabilityMode.SYNC_EACH_WRITE);
    }

    private Map<String, String> segmentContents() throws IOException {
        Map<String, String> contents = new HashMap<>();
        try (var files = Files.list(tempDir)) {
            // Opening and closing another channel to LOCK can release this JVM's file lock.
            for (Path file : files.filter(path -> !path.getFileName().toString().equals("LOCK")).toList()) {
                contents.put(file.getFileName().toString(), HexFormat.of().formatHex(Files.readAllBytes(file)));
            }
        }
        return contents;
    }
}
