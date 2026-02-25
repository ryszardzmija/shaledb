package com.ryszardzmija.segment;

import com.ryszardzmija.format.RecordReader;
import com.ryszardzmija.format.RecordWriter;
import com.ryszardzmija.index.ByteKey;
import com.ryszardzmija.index.Index;
import com.ryszardzmija.index.IndexBuilder;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.Optional;

public class MutableSegment implements AutoCloseable {
    private final Path path;
    private final FileChannel readChannel;
    private final FileChannel writeChannel;
    private final SegmentReader segmentReader;
    private final SegmentWriter segmentWriter;

    public MutableSegment(Path path) throws IOException {
        this.path = Objects.requireNonNull(path);
        this.readChannel = FileChannel.open(path, StandardOpenOption.READ);
        this.writeChannel = FileChannel.open(path, StandardOpenOption.APPEND);
        Index index = new IndexBuilder(readChannel).build();
        this.segmentReader = new SegmentReader(new RecordReader(readChannel), index);
        this.segmentWriter = new SegmentWriter(new RecordWriter(writeChannel), index);
    }

    public boolean containsKey(ByteKey key) {
        return segmentReader.containsKey(key);
    }

    public Optional<byte[]> get(ByteKey key) throws IOException {
        return segmentReader.get(key);
    }

    public void put(ByteKey key, byte[] value) throws IOException {
        segmentWriter.put(key, value);
    }

    public long size() throws IOException {
        return readChannel.size();
    }

    public Path getPath() {
        return path;
    }

    @Override
    public void close() throws IOException {
        readChannel.close();
        writeChannel.close();
    }
}
