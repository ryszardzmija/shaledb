package com.ryszardzmija.shaledb.storage.hash.segment.model;

import com.ryszardzmija.shaledb.storage.durability.DurabilityMode;
import com.ryszardzmija.shaledb.storage.serialization.io.RecordWriter;
import com.ryszardzmija.shaledb.storage.serialization.io.WriteRequest;
import com.ryszardzmija.shaledb.storage.serialization.io.WriteResult;
import com.ryszardzmija.shaledb.storage.serialization.record.RecordPayload;
import com.ryszardzmija.shaledb.storage.serialization.record.RecordType;

import java.util.Objects;

public class SegmentWriter {
    private final RecordWriter recordWriter;
    private final DurabilityMode durabilityMode;

    public SegmentWriter(RecordWriter recordWriter, DurabilityMode durabilityMode) {
        this.recordWriter = Objects.requireNonNull(recordWriter);
        this.durabilityMode = Objects.requireNonNull(durabilityMode);
    }

    /** Returns the physical write result after the configured durability barrier succeeds. */
    public WriteResult appendValue(byte[] key, byte[] value) {
        return append(new WriteRequest(new RecordPayload(key, value), RecordType.NORMAL));
    }

    /** Returns the physical write result after the configured durability barrier succeeds. */
    public WriteResult appendTombstone(byte[] key) {
        return append(new WriteRequest(RecordPayload.forTombstone(key), RecordType.TOMBSTONE));
    }

    private WriteResult append(WriteRequest writeRequest) {
        WriteResult writeResult = recordWriter.write(writeRequest);
        if (durabilityMode == DurabilityMode.SYNC_EACH_WRITE) {
            sync();
        }
        return writeResult;
    }

    public void sync() {
        recordWriter.flushToStorage();
    }
}
