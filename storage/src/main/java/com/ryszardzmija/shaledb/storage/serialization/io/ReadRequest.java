package com.ryszardzmija.shaledb.storage.serialization.io;

public record ReadRequest(long offset) {
    public ReadRequest {
        if (offset < 0) {
            throw new IllegalArgumentException("Record offset must not be negative: " + offset);
        }
    }

    /** The caller requested a starting offset beyond the observed file extent. */
    public static final class BeyondEnd extends IllegalArgumentException {
        BeyondEnd(long offset, long fileSize) {
            super("Record offset %d is beyond file size %d".formatted(offset, fileSize));
        }
    }
}
