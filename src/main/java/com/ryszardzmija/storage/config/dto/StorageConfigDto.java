package com.ryszardzmija.storage.config.dto;

import com.ryszardzmija.storage.config.StorageConfig;

import java.nio.file.Path;

public class StorageConfigDto {
    public long maxSegmentSize;
    public long maxRecordSize;
    public String segmentDir;

    public StorageConfig toStorageConfig() {
        return new StorageConfig(maxSegmentSize, maxRecordSize, Path.of(segmentDir));
    }
}
