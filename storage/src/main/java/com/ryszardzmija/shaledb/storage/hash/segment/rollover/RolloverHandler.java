package com.ryszardzmija.shaledb.storage.hash.segment.rollover;

import com.ryszardzmija.shaledb.storage.durability.DurabilityConfig;
import com.ryszardzmija.shaledb.storage.hash.segment.files.SegmentFileException;
import com.ryszardzmija.shaledb.storage.hash.segment.files.SegmentFileFactory;
import com.ryszardzmija.shaledb.storage.hash.segment.model.ImmutableSegment;
import com.ryszardzmija.shaledb.storage.hash.segment.model.MutableSegment;
import com.ryszardzmija.shaledb.storage.hash.segment.model.SegmentChannelCleanup;
import com.ryszardzmija.shaledb.storage.hash.segment.model.SegmentConfig;
import com.ryszardzmija.shaledb.storage.hash.segment.model.SegmentIOException;

import java.nio.file.Path;
import java.util.Objects;

public class RolloverHandler {
    private final SegmentFileFactory segmentFileFactory;
    private final SegmentConfig segmentConfig;
    private final DurabilityConfig durabilityConfig;
    private final SegmentChannelCleanup channelCleanup;

    public RolloverHandler(SegmentFileFactory segmentFileFactory, SegmentConfig segmentConfig,
                           DurabilityConfig durabilityConfig,
                           SegmentChannelCleanup channelCleanup) {
        this.segmentFileFactory = Objects.requireNonNull(segmentFileFactory);
        this.segmentConfig = Objects.requireNonNull(segmentConfig);
        this.durabilityConfig = Objects.requireNonNull(durabilityConfig);
        this.channelCleanup = Objects.requireNonNull(channelCleanup);
    }

    public RolloverResult rollOver(MutableSegment segment) {
        segmentFileFactory.requireSuccessorId();
        MutableSegment newMutableSegment = null;
        ImmutableSegment newImmutableSegment = null;
        try {
            // Replay and successful synchronous appends leave a complete, forced history.
            // Fail-stop prevents an ambiguous append from reaching this point.
            newImmutableSegment = segment.retire();
            Path newSegmentPath = segmentFileFactory.createSegmentFile();
            newMutableSegment = new MutableSegment(newSegmentPath, segmentConfig, durabilityConfig, channelCleanup);
            return new RolloverResult(newMutableSegment, newImmutableSegment);
        } catch (RuntimeException | Error e) {
            closeAfterFailure(newImmutableSegment, e);
            closeAfterFailure(newMutableSegment, e);
            // A successfully published empty successor is valid at the next open.
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
