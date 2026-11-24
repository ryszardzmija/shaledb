package com.ryszardzmija.shaledb.storage.serialization.io;

/** The file extent or an EOF left an expected record section incomplete. */
public final class IncompleteRecordException extends RecordIOException {
    public enum Section { HEADER, PAYLOAD }
    public enum Origin { FILE_EXTENT, TRANSFER_EOF }

    private final Section section;
    private final Origin origin;
    private final long recordOffset;
    private final int expectedBytes;
    private final int availableBytes;

    IncompleteRecordException(Section section, Origin origin, long recordOffset, int expectedBytes, int availableBytes) {
        super("Incomplete record %s at offset %d (%s): expected %d bytes, found %d"
                .formatted(section, recordOffset, origin, expectedBytes, availableBytes));
        this.section = section;
        this.origin = origin;
        this.recordOffset = recordOffset;
        this.expectedBytes = expectedBytes;
        this.availableBytes = availableBytes;
    }

    public Section section() {
        return section;
    }

    public Origin origin() {
        return origin;
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
