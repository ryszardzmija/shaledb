package com.ryszardzmija.shaledb.storage;

import com.ryszardzmija.shaledb.storage.StorageEngineException.MutationOutcome;
import com.ryszardzmija.shaledb.storage.StorageEngineException.Operation;
import com.ryszardzmija.shaledb.storage.StorageEngineException.Reason;
import com.ryszardzmija.shaledb.storage.config.StorageConfig;
import com.ryszardzmija.shaledb.storage.durability.DurabilityMode;
import com.ryszardzmija.shaledb.storage.serialization.spec.FormatInfo;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

final class KeyValueStoreTestSupport {
    private static final HexFormat HEX = HexFormat.of();
    private static final Pattern SEGMENT_FILE = Pattern.compile("segment_[0-9]+\\.log");

    private KeyValueStoreTestSupport() {
    }

    static StorageConfig config(Path segmentDir, long maxSegmentSize) {
        return new StorageConfig(maxSegmentSize, 128, segmentDir, DurabilityMode.SYNC_EACH_WRITE);
    }

    static byte[] hex(String value) {
        return HEX.parseHex(value);
    }

    static void assertValue(KeyValueStore store, byte[] key, byte[] expected) {
        String keyDescription = "value for key 0x" + HEX.formatHex(key);
        assertThat(store.get(key)).as(keyDescription).hasValueSatisfying(actual ->
                assertThat(actual).as(keyDescription).containsExactly(expected));
    }

    static long segmentCount(Path segmentDir) throws IOException {
        try (var files = Files.list(segmentDir)) {
            return files.filter(Files::isRegularFile)
                    .filter(file -> SEGMENT_FILE.matcher(file.getFileName().toString()).matches())
                    .count();
        }
    }

    static StorageEngineException assertFailure(
            Runnable action, Operation operation, Reason reason, MutationOutcome outcome) {
        StorageEngineException failure = catchThrowableOfType(action::run, StorageEngineException.class);
        assertThat(failure).isNotNull();
        assertThat(failure.operation()).isEqualTo(operation);
        assertThat(failure.reason()).isEqualTo(reason);
        assertThat(failure.mutationOutcome()).isEqualTo(outcome);
        return failure;
    }

    static byte[] invalidHeader() {
        return ByteBuffer.allocate(FormatInfo.getHeaderSize()).put(FormatInfo.CHECKSUM_FIELD_SIZE, (byte) 99).array();
    }
}
