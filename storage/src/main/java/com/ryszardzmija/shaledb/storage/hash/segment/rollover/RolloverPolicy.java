package com.ryszardzmija.shaledb.storage.hash.segment.rollover;

public interface RolloverPolicy {
    /** Evaluates an already admitted record against the physical size of the active segment. */
    boolean shouldRollover(long currentSize, int recordSize);
}
