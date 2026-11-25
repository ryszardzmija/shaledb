package com.ryszardzmija.shaledb.storage.hash.segment.model;

import com.ryszardzmija.shaledb.storage.hash.index.ByteKey;
import com.ryszardzmija.shaledb.storage.hash.index.Index;
import com.ryszardzmija.shaledb.storage.serialization.io.RecordIOException;

import java.nio.file.Path;
import java.util.Optional;

final class SegmentLookup {
    private SegmentLookup() {}

    static LookupResult get(Path path, Index index, SegmentReader reader, ByteKey key) {
        try {
            if (index.isDeleted(key)) {
                return new LookupResult.Deleted();
            }
            Optional<Long> offset = index.getKeyOffset(key);
            if (offset.isEmpty()) {
                return new LookupResult.NotPresent();
            }
            return new LookupResult.Present(reader.readValue(offset.get(), key));
        } catch (RecordIOException e) {
            throw new SegmentIOException("Failed to read from segment " + path, e);
        }
    }
}
