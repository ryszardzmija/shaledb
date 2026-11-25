package com.ryszardzmija.shaledb.storage.hash.segment.files;

import java.nio.file.Path;
import java.util.Objects;

/** Identifies a segment from discovery or creation through retirement. */
public record SegmentFile(long id, Path path) {
    public SegmentFile {
        if (id <= 0) {
            throw new IllegalArgumentException("Segment ID must be positive: " + id);
        }
        Objects.requireNonNull(path);
    }
}
