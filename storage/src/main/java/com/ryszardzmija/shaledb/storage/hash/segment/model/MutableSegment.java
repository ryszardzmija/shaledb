package com.ryszardzmija.shaledb.storage.hash.segment.model;

import com.ryszardzmija.shaledb.storage.durability.DurabilityConfig;
import com.ryszardzmija.shaledb.storage.durability.FileSystemSync;
import com.ryszardzmija.shaledb.storage.hash.index.ByteKey;
import com.ryszardzmija.shaledb.storage.hash.segment.files.SegmentFile;
import com.ryszardzmija.shaledb.storage.serialization.io.ReadResult;
import com.ryszardzmija.shaledb.storage.serialization.io.RecordIOException;
import com.ryszardzmija.shaledb.storage.serialization.io.RecordReader;
import com.ryszardzmija.shaledb.storage.serialization.io.RecordWriter;
import com.ryszardzmija.shaledb.storage.serialization.io.SegmentScanner;
import com.ryszardzmija.shaledb.storage.serialization.io.WriteResult;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.function.ObjLongConsumer;

public class MutableSegment implements AutoCloseable {
    private final SegmentFile file;
    private final FileChannel readChannel;
    private final FileChannel writeChannel;
    private final SegmentReader segmentReader;
    private final SegmentWriter segmentWriter;
    private final SegmentChannelCleanup channelCleanup;
    private boolean writeCloseStarted;
    private boolean closeStarted;

    public MutableSegment(SegmentFile file, SegmentConfig segmentConfig, DurabilityConfig durabilityConfig,
                          SegmentChannelCleanup channelCleanup) {
        this(file, segmentConfig, durabilityConfig, channelCleanup, null);
    }

    /** Reopens the discovered active segment after the loader has validated historical segments. */
    public static MutableSegment reopen(SegmentFile file, SegmentConfig segmentConfig, DurabilityConfig durabilityConfig,
                                        SegmentChannelCleanup channelCleanup, ObjLongConsumer<ReadResult> replayRecord) {
        return new MutableSegment(file, segmentConfig, durabilityConfig, channelCleanup,
                Objects.requireNonNull(replayRecord));
    }

    private MutableSegment(SegmentFile file, SegmentConfig segmentConfig, DurabilityConfig durabilityConfig,
                           SegmentChannelCleanup channelCleanup, ObjLongConsumer<ReadResult> replayRecord) {
        this.file = Objects.requireNonNull(file);
        this.channelCleanup = Objects.requireNonNull(channelCleanup);
        Path path = file.path();

        FileChannel openedReadChannel = null;
        FileChannel openedWriteChannel = null;
        try {
            openedReadChannel = FileChannel.open(path, StandardOpenOption.READ);
            openedWriteChannel = FileChannel.open(path, StandardOpenOption.WRITE);
            if (replayRecord == null && openedReadChannel.size() != 0) {
                throw new IOException("New segment is not empty");
            }
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            channelCleanup.closeAfterFailure(path, e, openedReadChannel, openedWriteChannel);
            throw new SegmentIOException("Failed to initialize the segment file " + path, e);
        } catch (RuntimeException | Error e) {
            channelCleanup.closeAfterFailure(path, e, openedReadChannel, openedWriteChannel);
            throw e;
        }

        try {
            if (replayRecord != null) {
                SegmentScanner scanner = new SegmentScanner(openedReadChannel, segmentConfig.maxPayloadSize());
                recoverTail(scanner.scan(replayRecord), openedWriteChannel);
            }

            this.readChannel = openedReadChannel;
            this.writeChannel = openedWriteChannel;
            this.segmentReader = new SegmentReader(new RecordReader(readChannel, segmentConfig.maxPayloadSize()));

            RecordWriter recordWriter = new RecordWriter(writeChannel, new FileSystemSync(),
                    segmentConfig.maxPayloadSize());
            this.segmentWriter = new SegmentWriter(recordWriter, durabilityConfig.durabilityMode());
        } catch (RuntimeException | Error e) {
            channelCleanup.closeAfterFailure(path, e, openedReadChannel, openedWriteChannel);

            if (e instanceof SegmentScanner.ScanException scanFailure) {
                throw new SegmentIOException("Failed to replay active segment " + path, scanFailure);
            }
            throw e;
        }
    }

    private void recoverTail(SegmentScanner.ScanResult scan, FileChannel writer) {
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
                    .formatted(operation, path(), boundary), e);
        }
    }

    public byte[] readValue(long offset, ByteKey expectedKey) {
        try {
            return segmentReader.readValue(offset, expectedKey);
        } catch (RecordIOException e) {
            throw new SegmentIOException("Failed to read from segment " + path(), e);
        }
    }

    public WriteResult appendValue(byte[] key, byte[] value) {
        try {
            return segmentWriter.appendValue(key, value);
        } catch (RecordIOException e) {
            throw new SegmentIOException("Failed to write to segment " + path(), e);
        }
    }

    public WriteResult appendTombstone(byte[] key) {
        try {
            return segmentWriter.appendTombstone(key);
        } catch (RecordIOException e) {
            throw new SegmentIOException("Failed to write to segment " + path(), e);
        }
    }

    public long size() {
        try {
            return readChannel.size();
        } catch (IOException e) {
            throw new SegmentIOException("Failed to get size of segment " + path(), e);
        }
    }

    public long id() {
        return file.id();
    }

    public Path path() {
        return file.path();
    }

    public void sync() {
        try {
            segmentWriter.sync();
        } catch (RecordIOException e) {
            throw new SegmentIOException("Failed to flush segment " + path() + " to durable storage", e);
        }
    }

    /** Retires the writer and transfers the reader and its channel, preserving the file identity. */
    public ImmutableSegment retire() {
        if (closeStarted || writeCloseStarted) {
            throw new IllegalStateException("Segment writer has already been retired: " + path());
        }
        writeCloseStarted = true;
        try {
            channelCleanup.close(path(), writeChannel);
        } catch (IOException e) {
            throw new SegmentIOException("Failed to retire the writer for segment " + path(), e);
        }
        ImmutableSegment historical = new ImmutableSegment(file, readChannel, segmentReader, channelCleanup);
        closeStarted = true; // The historical view now owns the read channel.
        return historical;
    }

    @Override
    public void close() {
        if (closeStarted) {
            return;
        }
        closeStarted = true;
        try {
            channelCleanup.close(path(), readChannel, writeCloseStarted ? null : writeChannel);
        } catch (IOException e) {
            throw new SegmentIOException("Failed to close segment " + path(), e);
        }
    }

    /** Whether this segment has begun releasing its channels. */
    public boolean closeStarted() {
        return closeStarted;
    }
}
