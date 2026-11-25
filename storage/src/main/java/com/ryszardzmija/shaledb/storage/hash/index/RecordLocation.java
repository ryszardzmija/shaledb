package com.ryszardzmija.shaledb.storage.hash.index;

/** The stable segment ID and byte offset of a normal record. */
public record RecordLocation(long segmentId, long recordOffset) {
    public RecordLocation {
        if (segmentId <= 0) {
            throw new IllegalArgumentException("Segment ID must be positive: " + segmentId);
        }
        if (recordOffset < 0) {
            throw new IllegalArgumentException("Record offset must be nonnegative: " + recordOffset);
        }
    }
}
