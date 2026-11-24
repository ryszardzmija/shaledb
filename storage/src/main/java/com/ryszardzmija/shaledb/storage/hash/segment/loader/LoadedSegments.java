package com.ryszardzmija.shaledb.storage.hash.segment.loader;

import com.ryszardzmija.shaledb.storage.hash.segment.model.ImmutableSegment;
import com.ryszardzmija.shaledb.storage.hash.segment.model.MutableSegment;

import java.util.ArrayList;

/** Transfers the segments and the newest-first history list to the engine on successful load. */
public record LoadedSegments(ArrayList<ImmutableSegment> immutableSegments, MutableSegment mutableSegment) {
}
