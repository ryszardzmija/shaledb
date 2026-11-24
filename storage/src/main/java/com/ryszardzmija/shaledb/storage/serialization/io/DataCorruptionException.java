package com.ryszardzmija.shaledb.storage.serialization.io;

public class DataCorruptionException extends RecordIOException {
    public enum Kind { STRUCTURE, CHECKSUM }

    private static final String ERROR_MESSAGE_TEMPLATE = "Data corruption detected at offset %d: %s";
    private final long recordOffset;
    private final Kind kind;

    public DataCorruptionException(String message, long offset) {
        this(message, offset, null);
    }

    public DataCorruptionException(String message, long offset, Throwable throwable) {
        this(message, offset, Kind.STRUCTURE, throwable);
    }

    DataCorruptionException(String message, long offset, Kind kind, Throwable throwable) {
        super(ERROR_MESSAGE_TEMPLATE.formatted(offset, message), throwable);
        this.recordOffset = offset;
        this.kind = kind;
    }

    public long recordOffset() {
        return recordOffset;
    }

    public Kind kind() {
        return kind;
    }
}
