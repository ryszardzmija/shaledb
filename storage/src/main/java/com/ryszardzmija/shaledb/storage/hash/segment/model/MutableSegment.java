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
    private final SegmentChannelCleanup channelCleanup;
    private boolean writeCloseStarted;
    private boolean closeStarted;

    public MutableSegment(Path path, SegmentConfig segmentConfig, DurabilityConfig durabilityConfig) {
        this(path, segmentConfig, durabilityConfig, new SegmentChannelCleanup());
    }

    public MutableSegment(Path path, SegmentConfig segmentConfig, DurabilityConfig durabilityConfig,
                          SegmentChannelCleanup channelCleanup) {
        this(path, segmentConfig, durabilityConfig, channelCleanup, false);
    }

    /** Reopens the discovered active segment after the loader has validated historical segments. */
    public static MutableSegment reopen(Path path, SegmentConfig segmentConfig, DurabilityConfig durabilityConfig,
                                        SegmentChannelCleanup channelCleanup) {
        return new MutableSegment(path, segmentConfig, durabilityConfig, channelCleanup, true);
    }

    private MutableSegment(Path path, SegmentConfig segmentConfig, DurabilityConfig durabilityConfig,
                           SegmentChannelCleanup channelCleanup, boolean reopening) {
        this.path = Objects.requireNonNull(path);
        this.channelCleanup = Objects.requireNonNull(channelCleanup);

        FileChannel openedReadChannel = null;
        FileChannel openedWriteChannel = null;
        try {
            openedReadChannel = FileChannel.open(path, StandardOpenOption.READ);
            openedWriteChannel = FileChannel.open(path, StandardOpenOption.WRITE);
            IndexBuilder indexBuilder = new IndexBuilder(openedReadChannel, segmentConfig.maxPayloadSize());
            Index index = reopening ? recoverIndex(indexBuilder, openedWriteChannel) : indexBuilder.build();

            this.readChannel = openedReadChannel;
            this.writeChannel = openedWriteChannel;
            this.segmentReader = new SegmentReader(new RecordReader(readChannel, segmentConfig.maxPayloadSize()), index);

            this.recordWriter = new RecordWriter(writeChannel, new FileSystemSync(), segmentConfig.maxPayloadSize());
            this.segmentWriter = new SegmentWriter(recordWriter, index, durabilityConfig.durabilityMode());
        } catch (IOException | RuntimeException | Error e) {
            channelCleanup.closeAfterFailure(path, e, openedReadChannel, openedWriteChannel);

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

    private Index recoverIndex(IndexBuilder indexBuilder, FileChannel writer) {
        IndexBuilder.ScanResult scan = indexBuilder.scan();
        long boundary = scan.validSize();
        String operation = "verify the scanned extent of";
        try {
            long writerSize = writer.size();
            if (writerSize != scan.fileSize()) {
                throw new IOException("Write channel size %d differs from scanned size %d"
                        .formatted(writerSize, scan.fileSize()));
            }
            if (scan.incompleteTail() != null) {
                operation = "truncate and verify";
                writer.truncate(boundary);
                long recoveredSize = writer.size();
                if (recoveredSize != boundary) {
                    throw new IOException("Segment size after truncation is " + recoveredSize);
                }
            }
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            throw new SegmentIOException("Failed to %s active segment %s at validated boundary %d"
                    .formatted(operation, path, boundary), e);
        }
        return scan.index();
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

    /** Retires the writer and transfers the read channel and live index to a historical view. */
    public ImmutableSegment retire() {
        if (closeStarted || writeCloseStarted) {
            throw new IllegalStateException("Segment writer has already been retired: " + path);
        }
        writeCloseStarted = true;
        try {
            channelCleanup.close(path, writeChannel);
        } catch (IOException e) {
            throw new SegmentIOException("Failed to retire the writer for segment " + path, e);
        }
        ImmutableSegment historical = new ImmutableSegment(path, readChannel, segmentReader, channelCleanup);
        closeStarted = true; // The historical view now owns the reader.
        return historical;
    }

    @Override
    public void close() {
        if (closeStarted) {
            return;
        }
        closeStarted = true;
        try {
            channelCleanup.close(path, readChannel, writeCloseStarted ? null : writeChannel);
        } catch (IOException e) {
            throw new SegmentIOException("Failed to close segment " + path, e);
        }
    }

    /** Whether this segment has begun releasing its channels. */
    public boolean closeStarted() {
        return closeStarted;
    }
}
