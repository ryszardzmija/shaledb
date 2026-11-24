package com.ryszardzmija.shaledb.storage.hash.segment.loader;

import com.ryszardzmija.shaledb.storage.durability.DurabilityConfig;
import com.ryszardzmija.shaledb.storage.durability.DurabilityException;
import com.ryszardzmija.shaledb.storage.durability.FileSystemSync;
import com.ryszardzmija.shaledb.storage.hash.segment.model.ImmutableSegment;
import com.ryszardzmija.shaledb.storage.hash.segment.model.MutableSegment;
import com.ryszardzmija.shaledb.storage.hash.segment.files.SegmentLayout;
import com.ryszardzmija.shaledb.storage.hash.segment.model.SegmentChannelCleanup;
import com.ryszardzmija.shaledb.storage.hash.segment.model.SegmentConfig;
import com.ryszardzmija.shaledb.storage.hash.segment.model.SegmentIOException;

import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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

    /** @param reopening whether the active file was discovered rather than created for this open */
    public LoadedSegments loadSegments(SegmentLayout segmentLayout, boolean reopening) {
        List<Path> immutableSegmentPaths = segmentLayout.immutableSegmentPaths();
        ArrayList<ImmutableSegment> immutableSegments = new ArrayList<>(immutableSegmentPaths.size());
        MutableSegment mutableSegment = null;

        try {
            // Iterate from the newest segment file to the oldest.
            for (int i = immutableSegmentPaths.size() - 1; i >= 0; i--) {
                immutableSegments.add(new ImmutableSegment(immutableSegmentPaths.get(i), segmentConfig, channelCleanup));
            }

            Path mutableSegmentPath = segmentLayout.mutableSegmentPath();
            if (reopening) {
                mutableSegment = MutableSegment.reopen(mutableSegmentPath, segmentConfig, durabilityConfig,
                        channelCleanup);
                // Force retained records and any new file length before establishing publication durability.
                mutableSegment.sync();
                fileSystemSync.forceDirectory(mutableSegmentPath.getParent());
            } else {
                mutableSegment = new MutableSegment(mutableSegmentPath, segmentConfig, durabilityConfig, channelCleanup);
            }

            return new LoadedSegments(immutableSegments, mutableSegment);
        } catch (RuntimeException | Error e) {
            if (mutableSegment != null) {
                closeAfterFailure(mutableSegment, e);
            }
            for (ImmutableSegment segment : immutableSegments) {
                closeAfterFailure(segment, e);
            }
            if (e instanceof SegmentIOException || e instanceof DurabilityException || e instanceof UncheckedIOException
                    || e instanceof UnsupportedOperationException || e instanceof SecurityException) {
                throw new SegmentLoadingException("Failed to load segments", e);
            }
            throw e;
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
