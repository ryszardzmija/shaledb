package com.ryszardzmija.shaledb.storage.hash.segment.model;

import com.ryszardzmija.shaledb.storage.serialization.io.ReadRequest;
import com.ryszardzmija.shaledb.storage.serialization.io.ReadResult;
import com.ryszardzmija.shaledb.storage.serialization.io.RecordIOException;
import com.ryszardzmija.shaledb.storage.serialization.io.RecordReader;
import com.ryszardzmija.shaledb.storage.hash.index.ByteKey;
import com.ryszardzmija.shaledb.storage.hash.index.Index;
import com.ryszardzmija.shaledb.storage.serialization.record.RecordType;

import java.util.Objects;
import java.util.Optional;

public class SegmentReader {
    private final RecordReader recordReader;
    private final Index index;

    public SegmentReader(RecordReader recordReader, Index index) {
        this.recordReader = Objects.requireNonNull(recordReader);
        this.index = Objects.requireNonNull(index);
    }

    public LookupResult get(ByteKey key) {
        if (index.isDeleted(key)) {
            return new LookupResult.Deleted();
        }

        Optional<Long> offset = index.getKeyOffset(key);
        if (offset.isEmpty()) {
            return new LookupResult.NotPresent();
        }

        long recordOffset = offset.get();
        if (recordOffset < 0) {
            throw new RecordIOException.InvalidLocation("Indexed offset is negative", recordOffset, null);
        }

        ReadResult readResult;
        try {
            readResult = recordReader.read(new ReadRequest(recordOffset));
        } catch (ReadRequest.BeyondEnd e) {
            throw new RecordIOException.InvalidLocation("Indexed offset is beyond EOF", recordOffset, e);
        }
        if (readResult.recordType() != RecordType.NORMAL) {
            throw new IndexConsistencyException(recordOffset,
                    "Expected record type NORMAL, found " + readResult.recordType());
        }
        if (!key.matches(readResult.recordPayload().key())) {
            throw new IndexConsistencyException(recordOffset, "Record key differs from indexed key");
        }
        return new LookupResult.Present(readResult.recordPayload().value());
    }
}
