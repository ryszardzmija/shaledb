package com.ryszardzmija.shaledb.storage.hash.segment.files;

import com.ryszardzmija.shaledb.storage.durability.DurabilityException;
import com.ryszardzmija.shaledb.storage.durability.FileSystemSync;
import com.ryszardzmija.shaledb.storage.hash.segment.model.SegmentChannelCleanup;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

public class SegmentFileFactory {
    private final Path segmentDir;
    private final FileSystemSync fileSystemSync;
    private final SegmentChannelCleanup channelCleanup;
    private long lastId;

    /** @param lastId greatest discovered ID, or zero for an empty layout */
    public SegmentFileFactory(Path segmentDir, FileSystemSync fileSystemSync, long lastId) {
        this(segmentDir, fileSystemSync, lastId, new SegmentChannelCleanup());
    }

    public SegmentFileFactory(Path segmentDir, FileSystemSync fileSystemSync, long lastId,
                              SegmentChannelCleanup channelCleanup) {
        this.segmentDir = Objects.requireNonNull(segmentDir);
        this.fileSystemSync = Objects.requireNonNull(fileSystemSync);
        this.channelCleanup = Objects.requireNonNull(channelCleanup);
        if (lastId < 0) {
            throw new IllegalArgumentException("Last segment ID must be nonnegative: " + lastId);
        }
        this.lastId = lastId;
    }

    /**
     * Creates and synchronizes a new segment file and its directory entry.
     * An allocated ID is consumed even if creation fails.
     * @throws SegmentFileException if there is no successor ID or creation fails
     */
    public Path createSegmentFile() {
        if (lastId == Long.MAX_VALUE) {
            throw new SegmentFileException.IdExhausted("Segment IDs exhausted at " + lastId + " in " + segmentDir);
        }
        Path segmentPath = segmentDir.resolve(FileFormatInfo.filename(++lastId));

        FileChannel channel;
        try {
            channel = FileChannel.open(segmentPath, StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            throw new SegmentFileException("Failed to create a segment file " + segmentPath, e);
        }

        try {
            try {
                fileSystemSync.forceFile(channel);
            } catch (RuntimeException | Error e) {
                channelCleanup.closeAfterFailure(segmentPath, e, channel);
                throw e;
            }
            channelCleanup.close(segmentPath, channel);
            try {
                fileSystemSync.forceDirectory(segmentDir);
            } catch (UncheckedIOException e) {
                // The directory force completed before its channel failed to close.
                throw new SegmentFileException("Failed to close the directory after publishing " + segmentPath, e);
            }
        } catch (SegmentFileException e) {
            // A directory close failure after a successful force leaves the published file in place.
            throw e;
        } catch (IOException | RuntimeException | Error e) {
            discardFailedCreation(segmentPath, e);
            if (e instanceof IOException || e instanceof UncheckedIOException || e instanceof DurabilityException
                    || e instanceof UnsupportedOperationException || e instanceof SecurityException) {
                throw new SegmentFileException("Failed to flush segment file creation: " + segmentPath, e);
            }
            if (e instanceof RuntimeException unexpected) {
                throw unexpected;
            }
            throw (Error) e;
        }
        return segmentPath;
    }

    public Path getSegmentDir() {
        return segmentDir;
    }

    private void discardFailedCreation(Path segmentPath, Throwable failure) {
        try {
            Files.deleteIfExists(segmentPath);
            fileSystemSync.forceDirectory(segmentDir);
        } catch (IOException | RuntimeException | Error e) {
            SegmentChannelCleanup.suppress(failure, e);
        }
    }
}
