package com.ryszardzmija.segment;

import com.ryszardzmija.format.RecordReader;
import com.ryszardzmija.index.ByteKey;
import com.ryszardzmija.index.Index;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

public class SegmentReader {
    private final RecordReader recordReader;
    private final Index index;

    public SegmentReader(RecordReader recordReader, Index index) {
        this.recordReader = Objects.requireNonNull(recordReader);
        this.index = Objects.requireNonNull(index);
    }

    public boolean containsKey(ByteKey key) {
        return index.containsKey(key);
    }

    public Optional<byte[]> get(ByteKey key) throws IOException {
        Optional<Long> offset = index.getKeyOffset(key);

        if (offset.isEmpty()) {
            return Optional.empty();
        }

        var readResult = recordReader.read(offset.get());
        var record = readResult.record();

        return Optional.of(record.value());
    }
}
