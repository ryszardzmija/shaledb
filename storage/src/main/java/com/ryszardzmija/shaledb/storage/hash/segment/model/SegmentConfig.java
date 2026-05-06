package com.ryszardzmija.shaledb.storage.hash.segment.model;

import com.ryszardzmija.shaledb.storage.config.StorageConfig;

public record SegmentConfig(long maxSegmentSize, long maxPayloadSize) {
    public static SegmentConfig from(StorageConfig storageConfig) {
        return new SegmentConfig(storageConfig.maxSegmentSize(), storageConfig.maxPayloadSize());
    }
}
