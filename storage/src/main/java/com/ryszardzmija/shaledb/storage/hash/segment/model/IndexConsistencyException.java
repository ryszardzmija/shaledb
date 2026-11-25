package com.ryszardzmija.shaledb.storage.hash.segment.model;

import com.ryszardzmija.shaledb.storage.serialization.io.RecordIOException;

/** An offset expected to hold a normal record resolves to a valid record with an unexpected type or key. */
public final class IndexConsistencyException extends RecordIOException {
    private final long recordOffset;

    IndexConsistencyException(long recordOffset, String detail) {
        super("Invalid index reference at offset %d: %s".formatted(recordOffset, detail), null);
        this.recordOffset = recordOffset;
    }

    public long recordOffset() {
        return recordOffset;
    }
}
