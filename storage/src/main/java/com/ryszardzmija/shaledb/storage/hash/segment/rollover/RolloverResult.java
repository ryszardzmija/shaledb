package com.ryszardzmija.shaledb.storage.hash.segment.rollover;

import com.ryszardzmija.shaledb.storage.hash.segment.model.ImmutableSegment;
import com.ryszardzmija.shaledb.storage.hash.segment.model.MutableSegment;

/** Transfers the successor and historical view to the engine after the old mutable is retired. */
public record RolloverResult(MutableSegment newMutableSegment, ImmutableSegment newImmutableSegment) {
}
