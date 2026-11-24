package com.ryszardzmija.shaledb.storage.hash.index;

import com.ryszardzmija.shaledb.storage.serialization.io.IncompleteRecordException;
import com.ryszardzmija.shaledb.storage.serialization.io.IncompleteRecordException.Origin;
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
        ScanResult result = scan();
        if (result.incompleteTail() != null) {
            throw new IndexBuildException("Incomplete record at offset " + result.validSize(), result.incompleteTail());
        }
        return result.index();
    }

    /** The replayed prefix, with a null incomplete tail when it reaches exact EOF. */
    public record ScanResult(Index index, long validSize, long fileSize, IncompleteRecordException incompleteTail) {}

    public ScanResult scan() {
        Index index = new HashIndex();
        long currentOffset = 0;
        try {
            long fileSize = readChannel.size();
            IncompleteRecordException incompleteTail = null;
            while (currentOffset < fileSize) {
                ReadResult readResult;
                try {
                    readResult = recordReader.readWithinExtent(new ReadRequest(currentOffset), fileSize);
                } catch (IncompleteRecordException e) {
                    if (e.origin() != Origin.FILE_EXTENT) {
                        throw new IndexBuildException("Unexpected EOF at record offset %d within scanned extent %d"
                                .formatted(currentOffset, fileSize), e);
                    }
                    incompleteTail = e;
                    break;
                }
                ByteKey key = new ByteKey(readResult.recordPayload().key());
                if (readResult.recordType() == RecordType.TOMBSTONE) {
                    index.markDeleted(key);
                } else {
                    index.markPresent(key, currentOffset);
                }
                currentOffset = readResult.nextRecordOffset();
            }
            long finalSize = readChannel.size();
            if (finalSize != fileSize) {
                throw new IOException("Segment size changed during index rebuild from %d to %d"
                        .formatted(fileSize, finalSize));
            }
            return new ScanResult(index, currentOffset, fileSize, incompleteTail);
        } catch (RecordIOException | IOException e) {
            throw new IndexBuildException("Failed to build index at offset " + currentOffset, e);
        }
    }
}
