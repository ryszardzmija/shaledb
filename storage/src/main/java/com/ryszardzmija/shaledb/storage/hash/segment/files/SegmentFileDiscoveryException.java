package com.ryszardzmija.shaledb.storage.hash.segment.files;

/**
 * Thrown after encountering an error during segment file discovery.
 */
public class SegmentFileDiscoveryException extends RuntimeException {
    public SegmentFileDiscoveryException(String message, Throwable cause) {
        super(message, cause);
    }

    public static class InvalidLayout extends SegmentFileDiscoveryException {
        InvalidLayout(String message) {
            this(message, null);
        }

        InvalidLayout(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
