package com.ryszardzmija.shaledb.storage.hash.index;

import com.ryszardzmija.shaledb.storage.serialization.io.ReadRequest;
import com.ryszardzmija.shaledb.storage.serialization.io.ReadResult;
import com.ryszardzmija.shaledb.storage.serialization.io.RecordIOException;
import com.ryszardzmija.shaledb.storage.serialization.io.RecordReader;
import com.ryszardzmija.shaledb.storage.serialization.record.RecordType;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.Objects;

public class IndexBuilder {
    private final FileChannel readChannel;
    private final RecordReader recordReader;

    public IndexBuilder(FileChannel readChannel, long maxPayloadSize) {
        this.readChannel = Objects.requireNonNull(readChannel);
        this.recordReader = new RecordReader(readChannel, maxPayloadSize);
    }

    public Index build() {
        Index index = new HashIndex();
        long currentOffset = 0;
        try {
            while (currentOffset < readChannel.size()) {
                ReadResult readResult = recordReader.read(new ReadRequest(currentOffset));
                ByteKey key = new ByteKey(readResult.recordPayload().key());
                if (readResult.recordType() == RecordType.TOMBSTONE) {
                    index.markDeleted(key);
                } else {
                    index.markPresent(key, currentOffset);
                }
                currentOffset = readResult.nextRecordOffset();
            }
            return index;
        } catch (ReadRequest.BeyondEnd e) {
            throw new IndexBuildException("Segment file shrank during index rebuild at offset " + currentOffset, e);
        } catch (RecordIOException | IOException e) {
            throw new IndexBuildException("Failed to build index at offset " + currentOffset, e);
        }
    }
}
