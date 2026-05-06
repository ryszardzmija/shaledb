package com.ryszardzmija.shaledb.storage.hash.segment.rollover;

import com.ryszardzmija.shaledb.storage.hash.segment.model.MutableSegment;

public class SizeBasedRolloverPolicy implements RolloverPolicy {
    private final long maxSegmentSize;

    public SizeBasedRolloverPolicy(long maxSegmentSize) {
        this.maxSegmentSize = maxSegmentSize;
    }

    @Override
    public boolean shouldRollover(MutableSegment segment) {
        return segment.size() >= maxSegmentSize;
    }
}
