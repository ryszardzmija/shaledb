package com.ryszardzmija.shaledb.storage.hash.segment.model;

import com.ryszardzmija.shaledb.storage.serialization.io.ReadRequest;
import com.ryszardzmija.shaledb.storage.serialization.io.ReadResult;
import com.ryszardzmija.shaledb.storage.serialization.io.RecordIOException;
import com.ryszardzmija.shaledb.storage.serialization.io.RecordReader;
import com.ryszardzmija.shaledb.storage.hash.index.ByteKey;
import com.ryszardzmija.shaledb.storage.hash.index.Index;

import java.util.Objects;
import java.util.Optional;

public class SegmentReader {
    private final RecordReader recordReader;
    private final Index index;
    private final long maxSegmentSize;

    public SegmentReader(RecordReader recordReader, Index index, long maxSegmentSize) {
        this.recordReader = Objects.requireNonNull(recordReader);
        this.index = Objects.requireNonNull(index);
        this.maxSegmentSize = maxSegmentSize;
    }

    public LookupResult get(ByteKey key) {
        // Note:
        // Here we rely on the contract of an Index which implements a state machine
        // where the key can be in exactly three mutually exclusive states:
        // - deleted: it's marked as deleted inside the segment
        // - present: it's present in the segment and not deleted
        // - not present: it's not in the segment

        if (index.isDeleted(key)) {
            return new LookupResult.Deleted();
        }

        Optional<Long> offset = index.getKeyOffset(key);
        if (offset.isEmpty()) {
            return new LookupResult.NotPresent();
        }

        long recordOffset = offset.get();
        if (recordOffset < 0 || recordOffset >= maxSegmentSize) {
            throw new RecordIOException.InvalidLocation(
                    "Indexed offset outside [0, %d)".formatted(maxSegmentSize), recordOffset, null);
        }

        try {
            ReadResult readResult = recordReader.read(new ReadRequest(recordOffset));
            return new LookupResult.Present(readResult.recordPayload().value());
        } catch (ReadRequest.BeyondEnd e) {
            throw new RecordIOException.InvalidLocation("Indexed offset is beyond EOF", recordOffset, e);
        }
    }
}
