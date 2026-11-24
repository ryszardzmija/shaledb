package com.ryszardzmija.shaledb.storage.config;

import com.ryszardzmija.shaledb.storage.durability.DurabilityMode;
import com.ryszardzmija.shaledb.storage.serialization.spec.FormatInfo;

import java.nio.file.Path;

public record StorageConfig(long maxSegmentSize, long maxPayloadSize, Path segmentDir, DurabilityMode durabilityMode) {
    public StorageConfig {
        if (maxSegmentSize <= 0) {
            throw new IllegalArgumentException("maxSegmentSize must be greater than 0 bytes, but was " + maxSegmentSize);
        }

        if (maxPayloadSize <= 0) {
            throw new IllegalArgumentException("maxPayloadSize must be greater than 0 bytes, but was " + maxPayloadSize);
        }

        int maxEncodedRecordSize = FormatInfo.getEncodedRecordSize(maxPayloadSize);
        if (maxEncodedRecordSize > maxSegmentSize) {
            throw new IllegalArgumentException(
                    "Encoded record size %d for maxPayloadSize %d exceeds maxSegmentSize %d"
                            .formatted(maxEncodedRecordSize, maxPayloadSize, maxSegmentSize));
        }

        if (segmentDir == null) {
            throw new IllegalArgumentException("segmentDir must not be null");
        }

        if (durabilityMode == null) {
            throw new IllegalArgumentException("durabilityMode must not be null");
        }
    }
}
