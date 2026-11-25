package com.ryszardzmija.shaledb.storage.hash.segment.loader;

import com.ryszardzmija.shaledb.storage.durability.DurabilityConfig;
import com.ryszardzmija.shaledb.storage.durability.DurabilityException;
import com.ryszardzmija.shaledb.storage.durability.FileSystemSync;
import com.ryszardzmija.shaledb.storage.hash.index.ByteKey;
import com.ryszardzmija.shaledb.storage.hash.index.KeyDirectory;
import com.ryszardzmija.shaledb.storage.hash.index.RecordLocation;
import com.ryszardzmija.shaledb.storage.hash.segment.files.SegmentFile;
import com.ryszardzmija.shaledb.storage.hash.segment.files.SegmentLayout;
import com.ryszardzmija.shaledb.storage.hash.segment.model.ImmutableSegment;
import com.ryszardzmija.shaledb.storage.hash.segment.model.MutableSegment;
import com.ryszardzmija.shaledb.storage.hash.segment.model.SegmentChannelCleanup;
import com.ryszardzmija.shaledb.storage.hash.segment.model.SegmentConfig;
import com.ryszardzmija.shaledb.storage.hash.segment.model.SegmentIOException;
import com.ryszardzmija.shaledb.storage.serialization.io.ReadResult;
import com.ryszardzmija.shaledb.storage.serialization.record.RecordType;

import java.io.UncheckedIOException;
import java.util.Map;
import java.util.Objects;

public class SegmentLoader {
    private final SegmentConfig segmentConfig;
    private final DurabilityConfig durabilityConfig;
    private final SegmentChannelCleanup channelCleanup;
    private final FileSystemSync fileSystemSync;

    public SegmentLoader(SegmentConfig segmentConfig, DurabilityConfig durabilityConfig,
                         SegmentChannelCleanup channelCleanup, FileSystemSync fileSystemSync) {
        this.segmentConfig = Objects.requireNonNull(segmentConfig);
        this.durabilityConfig = Objects.requireNonNull(durabilityConfig);
        this.channelCleanup = Objects.requireNonNull(channelCleanup);
        this.fileSystemSync = Objects.requireNonNull(fileSystemSync);
    }

    /**
     * Loads into the caller's provisional directory and empty registry. The caller takes ownership of
     * all segments on success and discards both collections on failure.
     * @param reopening whether the active file was discovered rather than created for this open
     */
    public MutableSegment loadSegments(SegmentLayout segmentLayout, boolean reopening, KeyDirectory directory,
                                       Map<Long, ImmutableSegment> immutableSegments) {
        MutableSegment mutableSegment = null;
        ImmutableSegment openingHistorical = null;
        boolean forcingActive = false;

        try {
            for (SegmentFile file : segmentLayout.historicalFiles()) {
                openingHistorical = new ImmutableSegment(file, segmentConfig, channelCleanup,
                        (record, offset) -> applyRecord(directory, file.id(), record, offset));
                if (immutableSegments.putIfAbsent(file.id(), openingHistorical) != null) {
                    throw new IllegalStateException("Duplicate historical segment ID " + file.id());
                }
                openingHistorical = null;
            }

            SegmentFile active = segmentLayout.activeFile();
            if (reopening) {
                mutableSegment = MutableSegment.reopen(active, segmentConfig, durabilityConfig, channelCleanup,
                        (record, offset) -> applyRecord(directory, active.id(), record, offset));
                // Force retained records and any new file length before establishing publication durability.
                forcingActive = true;
                mutableSegment.sync();
                fileSystemSync.forceDirectory(active.path().getParent());
            } else {
                mutableSegment = new MutableSegment(active, segmentConfig, durabilityConfig, channelCleanup);
            }

            return mutableSegment;
        } catch (RuntimeException | Error e) {
            if (mutableSegment != null) {
                closeAfterFailure(mutableSegment, e);
            }
            if (openingHistorical != null) {
                // Insertion can throw even after linking the entry. Segment close is idempotent.
                closeAfterFailure(openingHistorical, e);
            }
            for (ImmutableSegment segment : immutableSegments.values()) {
                closeAfterFailure(segment, e);
            }
            if (e instanceof SegmentIOException || (forcingActive && (e instanceof DurabilityException
                    || e instanceof UncheckedIOException || e instanceof UnsupportedOperationException
                    || e instanceof SecurityException))) {
                throw new SegmentLoadingException("Failed to load segments", e);
            }
            throw e;
        }
    }

    private static void applyRecord(KeyDirectory directory, long segmentId, ReadResult record, long offset) {
        ByteKey key = new ByteKey(record.recordPayload().key());
        if (record.recordType() == RecordType.TOMBSTONE) {
            directory.remove(key);
        } else {
            directory.markPresent(key, new RecordLocation(segmentId, offset));
        }
    }

    private static void closeAfterFailure(AutoCloseable segment, Throwable failure) {
        try {
            segment.close();
        } catch (Exception | Error cleanupFailure) {
            SegmentChannelCleanup.suppress(failure, cleanupFailure);
        }
    }
}
