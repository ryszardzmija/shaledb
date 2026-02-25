package com.ryszardzmija.segment;

import com.ryszardzmija.format.RecordReader;
import com.ryszardzmija.index.ByteKey;
import com.ryszardzmija.index.IndexBuilder;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.Optional;

public class ImmutableSegment implements AutoCloseable {
    private final FileChannel readChannel;
    private final SegmentReader segmentReader;

    public ImmutableSegment(Path path) throws IOException {
        Objects.requireNonNull(path);

        this.readChannel = FileChannel.open(path, StandardOpenOption.READ);
        this.segmentReader = new SegmentReader(new RecordReader(readChannel), new IndexBuilder(readChannel).build());
    }

    public boolean containsKey(ByteKey key) {
        return segmentReader.containsKey(key);
    }

    public Optional<byte[]> get(ByteKey key) throws IOException {
        return segmentReader.get(key);
    }

    public long size() throws IOException {
        return readChannel.size();
    }

    @Override
    public void close() throws IOException {
        readChannel.close();
    }
}
