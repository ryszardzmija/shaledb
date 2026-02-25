package com.ryszardzmija.index;

import com.ryszardzmija.format.RecordReader;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.Objects;

public class IndexBuilder {
    private final FileChannel readChannel;
    private final RecordReader recordReader;

    public IndexBuilder(FileChannel readChannel) throws IOException {
        this.readChannel = Objects.requireNonNull(readChannel);
        this.recordReader = new RecordReader(readChannel);
    }

    public Index build() throws IOException {
        return createIndex();
    }

    private Index createIndex() throws IOException {
        Index index = new HashIndex();

        long currentOffset = 0;
        while (currentOffset < readChannel.size()) {
            var readRecord = recordReader.read(currentOffset);
            ByteKey key = new ByteKey(readRecord.record().key());
            index.putKeyOffset(key, currentOffset);
            currentOffset = readRecord.nextRecordOffset();
        }

        return index;
    }
}
