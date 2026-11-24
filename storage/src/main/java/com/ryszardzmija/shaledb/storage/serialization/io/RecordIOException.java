package com.ryszardzmija.shaledb.storage.serialization.io;

import com.ryszardzmija.shaledb.storage.serialization.spec.FormatInfo.PayloadLimitExceededException;

/** An operational failure transferring or interpreting a record. */
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

    public static final class ReadLimitExceeded extends RecordIOException {
        private final long recordOffset;

        ReadLimitExceeded(long recordOffset, PayloadLimitExceededException cause) {
            super("Cannot read record at offset %d: %s".formatted(recordOffset, cause.getMessage()), cause);
            this.recordOffset = recordOffset;
        }

        public long recordOffset() {
            return recordOffset;
        }
    }

    public static final class InvalidLocation extends RecordIOException {
        private final long recordOffset;

        public InvalidLocation(String message, long recordOffset, Throwable cause) {
            super("Invalid record location at offset %d: %s".formatted(recordOffset, message), cause);
            this.recordOffset = recordOffset;
        }

        public long recordOffset() {
            return recordOffset;
        }
    }
}
