package com.ryszardzmija.shaledb.storage;

import com.ryszardzmija.shaledb.storage.config.StorageConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Random;
import java.util.TreeMap;

import static com.ryszardzmija.shaledb.storage.KeyValueStoreTestSupport.config;
import static com.ryszardzmija.shaledb.storage.KeyValueStoreTestSupport.hex;
import static com.ryszardzmija.shaledb.storage.KeyValueStoreTestSupport.segmentCount;
import static org.assertj.core.api.Assertions.assertThat;

class KeyValueStoreModelTest {
    private static final int OPERATION_COUNT = 300;
    private static final int SMALL_SEGMENT_SIZE = 512;
    private static final HexFormat HEX = HexFormat.of();
    private static final List<String> KEYS = List.of(
            "00", "0080", "0080ff", "00807f", "ff", "80", "001f", "0100", "ff00", "0001fe");

    @TempDir
    Path tempDir;

    @Test
    void matchesReferenceModelForSeed5EED() throws IOException {
        runSequence(0x5eedL);
    }

    @Test
    void matchesReferenceModelForSeedC0FFEE() throws IOException {
        runSequence(0xc0ffeeL);
    }

    @Test
    void matchesReferenceModelForSeedB17CA5C() throws IOException {
        runSequence(0xb17ca5cL);
    }

    private void runSequence(long seed) throws IOException {
        Random random = new Random(seed);
        Map<String, byte[]> expected = new HashMap<>();

        try (var database = new ReopenableStore(config(tempDir, SMALL_SEGMENT_SIZE))) {
            NavigableMap<Path, byte[]> previous = segmentBytes();
            for (int step = 1; step <= OPERATION_COUNT; step++) {
                Operation operation = nextOperation(random);
                String key = operation == Operation.REOPEN ? null : KEYS.get(random.nextInt(KEYS.size()));
                byte[] value = operation == Operation.PUT ? randomValue(random) : null;
                String context = "seed 0x%x, operation %d %s".formatted(seed, step, operation);
                if (key != null) {
                    context += " key 0x" + key;
                }
                if (operation == Operation.PUT) {
                    context += " value " + describeValue(value);
                }

                try {
                    switch (operation) {
                        case PUT -> {
                            expected.put(key, value.clone());
                            database.store.put(hex(key), value);
                        }
                        case GET -> assertMatches(database.store, key, expected.get(key), context);
                        case DELETE -> {
                            expected.remove(key);
                            database.store.delete(hex(key));
                        }
                        case REOPEN -> database.reopen();
                    }
                } catch (RuntimeException e) {
                    throw new AssertionError("%s: expected success, actual exception %s".formatted(context, e), e);
                }

                for (String candidate : KEYS) {
                    assertMatches(database.store, candidate, expected.get(candidate), context);
                }
                NavigableMap<Path, byte[]> current = segmentBytes();
                assertThat(current.keySet()).as(context).containsAll(previous.keySet());
                for (var segment : current.entrySet()) {
                    assertThat(segment.getValue()).as("%s, size of %s", context, segment.getKey())
                            .hasSizeLessThanOrEqualTo(SMALL_SEGMENT_SIZE);
                    byte[] oldBytes = previous.get(segment.getKey());
                    if (oldBytes != null && !segment.getKey().equals(current.lastKey())) {
                        assertThat(segment.getValue()).as("%s, historical bytes of %s", context, segment.getKey())
                                .containsExactly(oldBytes);
                    }
                }
                previous = current;
            }

            assertThat(segmentCount(tempDir)).as("segment files after seed 0x%x", seed).isGreaterThanOrEqualTo(3);
        }
    }

    private NavigableMap<Path, byte[]> segmentBytes() throws IOException {
        var segments = new TreeMap<Path, byte[]>();
        try (var files = Files.newDirectoryStream(tempDir, "segment_*.log")) {
            for (Path path : files) {
                segments.put(path, Files.readAllBytes(path));
            }
        }
        return segments;
    }

    private static void assertMatches(KeyValueStore store, String key, byte[] expected, String context) {
        String lookup = "%s, reading key 0x%s".formatted(context, key);
        String expectedValue = describeValue(expected);
        byte[] actual;
        try {
            actual = store.get(hex(key)).orElse(null);
        } catch (RuntimeException e) {
            throw new AssertionError("%s: expected %s, actual exception %s".formatted(lookup, expectedValue, e), e);
        }
        assertThat(describeValue(actual)).as(lookup).isEqualTo(expectedValue);
    }

    private static String describeValue(byte[] value) {
        return value == null ? "<missing>" : "0x" + HEX.formatHex(value);
    }

    private static Operation nextOperation(Random random) {
        int choice = random.nextInt(100);
        if (choice < 40) {
            return Operation.PUT;
        }
        if (choice < 65) {
            return Operation.GET;
        }
        if (choice < 90) {
            return Operation.DELETE;
        }
        return Operation.REOPEN;
    }

    private static byte[] randomValue(Random random) {
        int length = random.nextInt(8) == 0 ? 0 : 1 + random.nextInt(64);
        byte[] value = new byte[length];
        random.nextBytes(value);
        return value;
    }

    private enum Operation {
        PUT, GET, DELETE, REOPEN
    }

    private static final class ReopenableStore implements AutoCloseable {
        private final StorageConfig config;
        private KeyValueStore store;

        private ReopenableStore(StorageConfig config) {
            this.config = config;
            this.store = new KeyValueStore(config);
        }

        private void reopen() {
            close();
            store = new KeyValueStore(config);
        }

        @Override
        public void close() {
            KeyValueStore current = store;
            store = null;
            if (current != null) {
                current.close();
            }
        }
    }
}
