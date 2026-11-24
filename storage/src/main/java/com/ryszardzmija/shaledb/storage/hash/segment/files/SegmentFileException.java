package com.ryszardzmija.shaledb.storage.hash.segment.files;

/**
 * Thrown after encountering an unrecoverable file management operation for segment files.
 */
public class SegmentFileException extends RuntimeException {
    private final boolean segmentChannelCloseFailed;

    public SegmentFileException(String message) {
        super(message);
        this.segmentChannelCloseFailed = false;
    }

    public SegmentFileException(String message, Throwable cause) {
        this(message, cause, false);
    }

    SegmentFileException(String message, Throwable cause, boolean segmentChannelCloseFailed) {
        super(message, cause);
        this.segmentChannelCloseFailed = segmentChannelCloseFailed;
    }

    /** Whether closing the new segment's write channel failed during creation. */
    public boolean segmentChannelCloseFailed() {
        return segmentChannelCloseFailed;
    }

    public static class IdExhausted extends SegmentFileException {
        IdExhausted(String message) {
            super(message);
        }
    }
}
