package com.ryszardzmija.shaledb.storage.serialization.io;

public class RecordIOException extends RuntimeException {
    static final int MAX_CONSECUTIVE_ZERO_TRANSFERS = 3;

    RecordIOException(String message) {
        super(message);
    }

    public RecordIOException(String message, Throwable throwable) {
        super(message, throwable);
    }

    public static final class NoProgress extends RecordIOException {
        NoProgress(String message) {
            super(message);
        }
    }
}
