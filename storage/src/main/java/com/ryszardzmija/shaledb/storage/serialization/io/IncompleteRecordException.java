package com.ryszardzmija.shaledb.storage.serialization.io;

/** The file extent or an EOF left an expected record section incomplete. */
public final class IncompleteRecordException extends RecordIOException {
    public enum Section { HEADER, PAYLOAD }

    private final Section section;
    private final long recordOffset;
    private final int expectedBytes;
    private final int availableBytes;

    IncompleteRecordException(Section section, long recordOffset, int expectedBytes, int availableBytes) {
        super("Incomplete record %s at offset %d: expected %d bytes, found %d"
                .formatted(section, recordOffset, expectedBytes, availableBytes));
        this.section = section;
        this.recordOffset = recordOffset;
        this.expectedBytes = expectedBytes;
        this.availableBytes = availableBytes;
    }

    public Section section() {
        return section;
    }

    public long recordOffset() {
        return recordOffset;
    }

    public int expectedBytes() {
        return expectedBytes;
    }

    /** Bytes present in the observed extent or transferred before EOF, within this section. */
    public int availableBytes() {
        return availableBytes;
    }
}
