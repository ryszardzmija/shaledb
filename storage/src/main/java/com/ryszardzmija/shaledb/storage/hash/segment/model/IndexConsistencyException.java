package com.ryszardzmija.shaledb.storage.hash.segment.model;

import com.ryszardzmija.shaledb.storage.serialization.io.RecordIOException;

/** A directory location cannot be resolved to the expected normal record. */
public final class IndexConsistencyException extends RecordIOException {
    private final long recordOffset;

    public IndexConsistencyException(long recordOffset, String detail) {
        super("Invalid index reference at offset %d: %s".formatted(recordOffset, detail), null);
        this.recordOffset = recordOffset;
    }

    public long recordOffset() {
        return recordOffset;
    }
}
