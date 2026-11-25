package com.ryszardzmija.shaledb.storage.hash.segment.model;

import com.ryszardzmija.shaledb.storage.hash.index.ByteKey;
import com.ryszardzmija.shaledb.storage.serialization.io.ReadRequest;
import com.ryszardzmija.shaledb.storage.serialization.io.ReadResult;
import com.ryszardzmija.shaledb.storage.serialization.io.RecordIOException;
import com.ryszardzmija.shaledb.storage.serialization.io.RecordReader;
import com.ryszardzmija.shaledb.storage.serialization.record.RecordType;

import java.util.Objects;

public class SegmentReader {
    private final RecordReader recordReader;

    public SegmentReader(RecordReader recordReader) {
        this.recordReader = Objects.requireNonNull(recordReader);
    }

    /** Reads a normal record at the given physical offset and verifies its binary key. */
    public byte[] readValue(long recordOffset, ByteKey expectedKey) {
        if (recordOffset < 0) {
            throw new RecordIOException.InvalidLocation("Record offset is negative", recordOffset, null);
        }

        ReadResult readResult;
        try {
            readResult = recordReader.read(new ReadRequest(recordOffset));
        } catch (ReadRequest.BeyondEnd e) {
            throw new RecordIOException.InvalidLocation("Record offset is beyond EOF", recordOffset, e);
        }
        if (readResult.recordType() != RecordType.NORMAL) {
            throw new IndexConsistencyException(recordOffset,
                    "Expected record type NORMAL, found " + readResult.recordType());
        }
        if (!expectedKey.matches(readResult.recordPayload().key())) {
            throw new IndexConsistencyException(recordOffset, "Record key differs from expected key");
        }
        return readResult.recordPayload().value();
    }
}
