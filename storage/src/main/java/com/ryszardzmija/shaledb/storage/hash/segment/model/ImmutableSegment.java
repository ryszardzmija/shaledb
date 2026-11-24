package com.ryszardzmija.shaledb.storage.hash.segment.model;

import com.ryszardzmija.shaledb.storage.hash.index.Index;
import com.ryszardzmija.shaledb.storage.serialization.io.RecordIOException;
import com.ryszardzmija.shaledb.storage.serialization.io.RecordReader;
import com.ryszardzmija.shaledb.storage.hash.index.ByteKey;
import com.ryszardzmija.shaledb.storage.hash.index.IndexBuildException;
import com.ryszardzmija.shaledb.storage.hash.index.IndexBuilder;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

public class ImmutableSegment implements AutoCloseable {
    private final Path path;
    private final FileChannel readChannel;
    private final SegmentReader segmentReader;
    private final SegmentChannelCleanup channelCleanup;
    private boolean closeStarted;

    public ImmutableSegment(Path path, SegmentConfig config, SegmentChannelCleanup channelCleanup) {
        this.path = Objects.requireNonNull(path);
        this.channelCleanup = Objects.requireNonNull(channelCleanup);

        FileChannel openedReadChannel = null;
        try {
            openedReadChannel = FileChannel.open(path, StandardOpenOption.READ);

            this.readChannel = openedReadChannel;
            Index index = new IndexBuilder(readChannel, config.maxPayloadSize()).build();
            this.segmentReader = new SegmentReader(new RecordReader(readChannel, config.maxPayloadSize()), index);
        } catch (IOException | RuntimeException | Error e) {
            channelCleanup.closeAfterFailure(path, e, openedReadChannel);

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

    public long size() {
        try {
            return readChannel.size();
        } catch (IOException e) {
            throw new SegmentIOException("Failed to get size of segment " + path, e);
        }
    }

    @Override
    public void close() {
        if (closeStarted) {
            return;
        }
        closeStarted = true;
        try {
            channelCleanup.close(path, readChannel);
        } catch (IOException e) {
            throw new SegmentIOException("Failed to close segment " + path, e);
        }
    }
}
