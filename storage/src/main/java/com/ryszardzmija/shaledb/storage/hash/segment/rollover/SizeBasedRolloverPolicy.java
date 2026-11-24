package com.ryszardzmija.shaledb.storage.hash.segment.rollover;

public class SizeBasedRolloverPolicy implements RolloverPolicy {
    private final long maxSegmentSize;

    public SizeBasedRolloverPolicy(long maxSegmentSize) {
        this.maxSegmentSize = maxSegmentSize;
    }

    @Override
    public boolean shouldRollover(long currentSize, int recordSize) {
        return currentSize > maxSegmentSize || recordSize > maxSegmentSize - currentSize;
    }
}
