package com.ryszardzmija.shaledb.storage.config;

import com.ryszardzmija.shaledb.storage.durability.DurabilityMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StorageConfigTest {
    private static final long VALID_MAX_SEGMENT_SIZE = 65536;
    private static final long VALID_MAX_PAYLOAD_SIZE = 16384;
    private static final DurabilityMode VALID_DURABILITY_MODE = DurabilityMode.SYNC_EACH_WRITE;

    @TempDir
    Path tempDir;

    @Test
    void acceptsValidConfig() {
        long maxSegmentSize = VALID_MAX_SEGMENT_SIZE;
        long maxPayloadSize = VALID_MAX_PAYLOAD_SIZE;
        DurabilityMode durabilityMode = VALID_DURABILITY_MODE;

        StorageConfig config = new StorageConfig(maxSegmentSize, maxPayloadSize, tempDir, durabilityMode);

        assertThat(config.maxSegmentSize()).isEqualTo(maxSegmentSize);
        assertThat(config.maxPayloadSize()).isEqualTo(maxPayloadSize);
        assertThat(config.segmentDir()).isEqualTo(tempDir);
        assertThat(config.durabilityMode()).isEqualTo(durabilityMode);
    }

    @Test
    void rejectsZeroMaxSegmentSize() {
        long maxSegmentSize = 0;

        assertThatThrownBy(() -> new StorageConfig(maxSegmentSize, VALID_MAX_PAYLOAD_SIZE, tempDir, VALID_DURABILITY_MODE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("maxSegmentSize must be greater than 0 bytes, but was " + maxSegmentSize);
    }

    @Test
    void rejectsNegativeMaxSegmentSize() {
        long maxSegmentSize = -1;

        assertThatThrownBy(() -> new StorageConfig(maxSegmentSize, VALID_MAX_PAYLOAD_SIZE, tempDir, VALID_DURABILITY_MODE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("maxSegmentSize must be greater than 0 bytes, but was " + maxSegmentSize);
    }

    @Test
    void rejectsZeroMaxPayloadSize() {
        long maxPayloadSize = 0;

        assertThatThrownBy(() -> new StorageConfig(VALID_MAX_SEGMENT_SIZE, maxPayloadSize, tempDir, VALID_DURABILITY_MODE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("maxPayloadSize must be greater than 0 bytes, but was " + maxPayloadSize);
    }

    @Test
    void rejectsNegativeMaxPayloadSize() {
        long maxPayloadSize = -1;

        assertThatThrownBy(() -> new StorageConfig(VALID_MAX_SEGMENT_SIZE, maxPayloadSize, tempDir, VALID_DURABILITY_MODE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("maxPayloadSize must be greater than 0 bytes, but was " + maxPayloadSize);
    }

    @Test
    void rejectsMaxPayloadSizeExceedingMaxSegmentSize() {
        long maxPayloadSize = VALID_MAX_SEGMENT_SIZE + 1;

        assertThatThrownBy(() -> new StorageConfig(VALID_MAX_SEGMENT_SIZE, maxPayloadSize, tempDir, VALID_DURABILITY_MODE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Encoded record size 65550 for maxPayloadSize 65537 exceeds maxSegmentSize 65536");
    }

    @Test
    void acceptsMinimumSegmentAndPayloadSizes() {
        StorageConfig config = new StorageConfig(14, 1, tempDir, VALID_DURABILITY_MODE);

        assertThat(config.maxSegmentSize()).isEqualTo(14);
        assertThat(config.maxPayloadSize()).isOne();
    }

    @Test
    void rejectsSegmentTooSmallForMinimumRecord() {
        assertThatThrownBy(() -> new StorageConfig(13, 1, tempDir, VALID_DURABILITY_MODE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Encoded record size 14 for maxPayloadSize 1 exceeds maxSegmentSize 13");
    }

    @Test
    void acceptsMaximumEncodedRecordSizeEqualToSegmentSize() {
        StorageConfig config = new StorageConfig(63, 50, tempDir, VALID_DURABILITY_MODE);

        assertThat(config.maxSegmentSize()).isEqualTo(63);
        assertThat(config.maxPayloadSize()).isEqualTo(50);
    }

    @Test
    void rejectsPayloadBudgetWhenHeaderWouldExceedSegmentSizeByOneByte() {
        assertThatThrownBy(() -> new StorageConfig(62, 50, tempDir, VALID_DURABILITY_MODE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Encoded record size 63 for maxPayloadSize 50 exceeds maxSegmentSize 62");
    }

    @Test
    void acceptsLargestRepresentableEncodedRecordSize() {
        StorageConfig config = new StorageConfig(Integer.MAX_VALUE, Integer.MAX_VALUE - 13, tempDir, VALID_DURABILITY_MODE);

        assertThat(config.maxPayloadSize()).isEqualTo(2147483634L);
    }

    @Test
    void rejectsPayloadBudgetWhenEncodedSizeExceedsIntegerRange() {
        long maxPayloadSize = Integer.MAX_VALUE - 12L;

        assertThatThrownBy(() -> new StorageConfig(Long.MAX_VALUE, maxPayloadSize, tempDir, VALID_DURABILITY_MODE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Record payload size 2147483635 is outside the representable range");
    }

    @Test
    void rejectsPayloadBudgetThatWouldOverflowALongWithHeader() {
        assertThatThrownBy(() -> new StorageConfig(Long.MAX_VALUE, Long.MAX_VALUE, tempDir, VALID_DURABILITY_MODE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Record payload size " + Long.MAX_VALUE + " is outside the representable range");
    }

    @Test
    void rejectsNullSegmentDir() {
        assertThatThrownBy(() -> new StorageConfig(VALID_MAX_SEGMENT_SIZE, VALID_MAX_PAYLOAD_SIZE, null, VALID_DURABILITY_MODE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("segmentDir must not be null");
    }

    @Test
    void rejectsNullDurabilityMode() {
        assertThatThrownBy(() -> new StorageConfig(VALID_MAX_SEGMENT_SIZE, VALID_MAX_PAYLOAD_SIZE, tempDir, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("durabilityMode must not be null");
    }
}
