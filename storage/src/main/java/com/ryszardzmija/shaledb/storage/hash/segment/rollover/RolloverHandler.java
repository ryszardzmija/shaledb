package com.ryszardzmija.shaledb.storage.hash.segment.rollover;

import com.ryszardzmija.shaledb.storage.durability.DurabilityConfig;
import com.ryszardzmija.shaledb.storage.durability.FileSystemSync;
import com.ryszardzmija.shaledb.storage.hash.segment.files.SegmentFileException;
import com.ryszardzmija.shaledb.storage.hash.segment.files.SegmentFileFactory;
import com.ryszardzmija.shaledb.storage.hash.segment.model.ImmutableSegment;
import com.ryszardzmija.shaledb.storage.hash.segment.model.MutableSegment;
import com.ryszardzmija.shaledb.storage.hash.segment.model.SegmentChannelCleanup;
import com.ryszardzmija.shaledb.storage.hash.segment.model.SegmentConfig;
import com.ryszardzmija.shaledb.storage.hash.segment.model.SegmentIOException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public class RolloverHandler {
    private final SegmentFileFactory segmentFileFactory;
    private final FileSystemSync fileSystemSync;
    private final SegmentConfig segmentConfig;
    private final DurabilityConfig durabilityConfig;
    private final SegmentChannelCleanup channelCleanup;

    public RolloverHandler(SegmentFileFactory segmentFileFactory, FileSystemSync fileSystemSync,
                           SegmentConfig segmentConfig, DurabilityConfig durabilityConfig,
                           SegmentChannelCleanup channelCleanup) {
        this.segmentFileFactory = Objects.requireNonNull(segmentFileFactory);
        this.fileSystemSync = Objects.requireNonNull(fileSystemSync);
        this.segmentConfig = Objects.requireNonNull(segmentConfig);
        this.durabilityConfig = Objects.requireNonNull(durabilityConfig);
        this.channelCleanup = Objects.requireNonNull(channelCleanup);
    }

    public RolloverResult rollOver(MutableSegment segment) {
        Path newSegmentPath = null;
        MutableSegment newMutableSegment = null;
        ImmutableSegment newImmutableSegment = null;
        try {
            newSegmentPath = segmentFileFactory.createSegmentFile();
            newMutableSegment = new MutableSegment(newSegmentPath, segmentConfig, durabilityConfig, channelCleanup);

            segment.sync();
            newImmutableSegment = new ImmutableSegment(segment.path(), segmentConfig, channelCleanup);
            RolloverResult replacement = new RolloverResult(newMutableSegment, newImmutableSegment);
            segment.close();
            return replacement;
        } catch (RuntimeException | Error e) {
            closeAfterFailure(newImmutableSegment, e);
            closeAfterFailure(newMutableSegment, e);
            if (newSegmentPath != null) {
                try {
                    Files.delete(newSegmentPath);
                    fileSystemSync.forceDirectory(segmentFileFactory.getSegmentDir());
                } catch (IOException | RuntimeException | Error cleanupFailure) {
                    SegmentChannelCleanup.suppress(e, cleanupFailure);
                }
            }
            if (e instanceof SegmentFileException || e instanceof SegmentIOException) {
                throw new RolloverException("Failed to roll over the segment " + segment.path(), e);
            }
            throw e;
        }
    }

    private static void closeAfterFailure(AutoCloseable segment, Throwable failure) {
        if (segment != null) {
            try {
                segment.close();
            } catch (Exception | Error cleanupFailure) {
                SegmentChannelCleanup.suppress(failure, cleanupFailure);
            }
        }
    }
}
