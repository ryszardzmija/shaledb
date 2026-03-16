package com.ryszardzmija.storage.hash.segment.model;

import com.ryszardzmija.storage.serialization.io.ReadRequest;
import com.ryszardzmija.storage.serialization.io.ReadResult;
import com.ryszardzmija.storage.serialization.io.RecordReader;
import com.ryszardzmija.storage.hash.index.ByteKey;
import com.ryszardzmija.storage.hash.index.Index;

import java.util.Objects;
import java.util.Optional;

public class SegmentReader {
    private final RecordReader recordReader;
    private final Index index;

    public SegmentReader(RecordReader recordReader, Index index) {
        this.recordReader = Objects.requireNonNull(recordReader);
        this.index = Objects.requireNonNull(index);
    }

    public Optional<byte[]> get(ByteKey key) {
        // Note:
        // Here, we rely on the assumption that the index is in a consistent state,
        // meaning that if the key was deleted then getKeyOffset() will not
        // return the offset of a stale record.
        Optional<Long> offset = index.getKeyOffset(key);

        if (offset.isEmpty()) {
            return Optional.empty();
        }

        ReadResult readResult = recordReader.read(new ReadRequest(offset.get()));
        return Optional.of(readResult.recordPayload().value());
    }
}
