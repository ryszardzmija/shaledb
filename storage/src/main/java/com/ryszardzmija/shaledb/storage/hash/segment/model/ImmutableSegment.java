package com.ryszardzmija.shaledb.storage.hash.segment.model;

import com.ryszardzmija.shaledb.storage.hash.index.ByteKey;
import com.ryszardzmija.shaledb.storage.hash.segment.files.SegmentFile;
import com.ryszardzmija.shaledb.storage.serialization.io.ReadResult;
import com.ryszardzmija.shaledb.storage.serialization.io.RecordIOException;
import com.ryszardzmija.shaledb.storage.serialization.io.RecordReader;
import com.ryszardzmija.shaledb.storage.serialization.io.SegmentScanner;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.function.ObjLongConsumer;

public class ImmutableSegment implements AutoCloseable {
    private final SegmentFile file;
    private final FileChannel readChannel;
    private final SegmentReader segmentReader;
    private final SegmentChannelCleanup channelCleanup;
    private boolean closeStarted;

    ImmutableSegment(SegmentFile file, FileChannel readChannel, SegmentReader segmentReader,
                      SegmentChannelCleanup channelCleanup) {
        this.file = file;
        this.readChannel = readChannel;
        this.segmentReader = segmentReader;
        this.channelCleanup = channelCleanup;
    }

    public ImmutableSegment(SegmentFile file, SegmentConfig config, SegmentChannelCleanup channelCleanup,
                            ObjLongConsumer<ReadResult> replayRecord) {
        this.file = Objects.requireNonNull(file);
        this.channelCleanup = Objects.requireNonNull(channelCleanup);

        try {
            this.readChannel = FileChannel.open(file.path(), StandardOpenOption.READ);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            throw new SegmentIOException("Failed to open the segment file " + file.path(), e);
        }
        try {
            new SegmentScanner(readChannel, config.maxPayloadSize()).scanComplete(replayRecord);
            this.segmentReader = new SegmentReader(new RecordReader(readChannel, config.maxPayloadSize()));
        } catch (RuntimeException | Error e) {
            channelCleanup.closeAfterFailure(file.path(), e, readChannel);
            if (e instanceof SegmentScanner.ScanException scanFailure) {
                throw new SegmentIOException("Failed to replay historical segment " + file.path(), scanFailure);
            }
            throw e;
        }
    }

    public long id() {
        return file.id();
    }

    public byte[] readValue(long offset, ByteKey expectedKey) {
        try {
            return segmentReader.readValue(offset, expectedKey);
        } catch (RecordIOException e) {
            throw new SegmentIOException("Failed to read from segment " + file.path(), e);
        }
    }

    @Override
    public void close() {
        if (closeStarted) {
            return;
        }
        closeStarted = true;
        try {
            channelCleanup.close(file.path(), readChannel);
        } catch (IOException e) {
            throw new SegmentIOException("Failed to close segment " + file.path(), e);
        }
    }
}
