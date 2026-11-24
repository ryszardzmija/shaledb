package com.ryszardzmija.shaledb.storage.hash.segment.model;

import com.ryszardzmija.shaledb.storage.durability.DurabilityConfig;
import com.ryszardzmija.shaledb.storage.durability.FileSystemSync;
import com.ryszardzmija.shaledb.storage.serialization.io.RecordIOException;
import com.ryszardzmija.shaledb.storage.serialization.io.RecordReader;
import com.ryszardzmija.shaledb.storage.serialization.io.RecordWriter;
import com.ryszardzmija.shaledb.storage.hash.index.ByteKey;
import com.ryszardzmija.shaledb.storage.hash.index.Index;
import com.ryszardzmija.shaledb.storage.hash.index.IndexBuildException;
import com.ryszardzmija.shaledb.storage.hash.index.IndexBuilder;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

public class MutableSegment implements AutoCloseable {
    private final Path path;
    private final FileChannel readChannel;
    private final FileChannel writeChannel;
    private final SegmentReader segmentReader;
    private final SegmentWriter segmentWriter;
    private final RecordWriter recordWriter;

    public MutableSegment(Path path, SegmentConfig segmentConfig, DurabilityConfig durabilityConfig) {
        this.path = Objects.requireNonNull(path);

        FileChannel openedReadChannel = null;
        FileChannel openedWriteChannel = null;
        try {
            openedReadChannel = FileChannel.open(path, StandardOpenOption.READ);
            openedWriteChannel = FileChannel.open(path, StandardOpenOption.WRITE);
            Index index = new IndexBuilder(openedReadChannel, segmentConfig.maxPayloadSize()).build();

            this.readChannel = openedReadChannel;
            this.writeChannel = openedWriteChannel;
            this.segmentReader = new SegmentReader(new RecordReader(readChannel, segmentConfig.maxPayloadSize()), index);

            this.recordWriter = new RecordWriter(writeChannel, new FileSystemSync(), segmentConfig.maxPayloadSize());
            this.segmentWriter = new SegmentWriter(recordWriter, index, durabilityConfig.durabilityMode());
        } catch (IOException | RuntimeException | Error e) {
            try {
                closeChannels(openedReadChannel, openedWriteChannel);
            } catch (IOException | RuntimeException | Error cleanupFailure) {
                if (e != cleanupFailure) {
                    e.addSuppressed(cleanupFailure);
                }
            }

            if (e instanceof IndexBuildException ie) {
                throw new SegmentIOException("Failed to create index for segment file " + path, ie);
            }

            if (e instanceof IOException || e instanceof UnsupportedOperationException || e instanceof SecurityException) {
                throw new SegmentIOException("Failed to open the segment file " + path, e);
            }
            if (e instanceof RuntimeException unexpected) {
                throw unexpected;
            }
            throw (Error) e;
        }
    }

    public LookupResult get(ByteKey key) {
        try {
            return segmentReader.get(key);
        } catch (RecordIOException e) {
            throw new SegmentIOException("Failed to read from segment " + path, e);
        }
    }

    public void put(ByteKey key, byte[] value) {
        try {
            segmentWriter.put(key, value);
        } catch (RecordIOException e) {
            throw new SegmentIOException("Failed to write to segment " + path, e);
        }
    }

    public void delete(ByteKey key) {
        try {
            segmentWriter.delete(key);
        } catch (RecordIOException e) {
            throw new SegmentIOException("Failed to write to segment " + path, e);
        }
    }

    public long size() {
        try {
            return readChannel.size();
        } catch (IOException e) {
            throw new SegmentIOException("Failed to get size of segment " + path, e);
        }
    }

    public Path path() {
        return path;
    }

    public void sync() {
        try {
            recordWriter.flushToStorage();
        } catch (RecordIOException e) {
            throw new SegmentIOException("Failed to flush segment " + path + " to durable storage", e);
        }
    }

    @Override
    public void close() {
        try {
            closeChannels(readChannel, writeChannel);
        } catch (IOException e) {
            throw new SegmentIOException("Failed to close segment " + path, e);
        }
    }

    private static void closeChannels(FileChannel readChannel, FileChannel writeChannel) throws IOException {
        try (writeChannel; readChannel) {
            // Close the read channel first and preserve a second close failure as suppressed.
        }
    }
}
