package com.ryszardzmija.shaledb.storage.hash.segment.files;

import com.ryszardzmija.shaledb.storage.durability.DurabilityException;
import com.ryszardzmija.shaledb.storage.durability.FileSystemSync;

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
    private long lastId;

    /** @param lastId greatest discovered ID, or zero for an empty layout */
    public SegmentFileFactory(Path segmentDir, FileSystemSync fileSystemSync, long lastId) {
        this.segmentDir = Objects.requireNonNull(segmentDir);
        this.fileSystemSync = Objects.requireNonNull(fileSystemSync);
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
            try (channel) {
                fileSystemSync.forceFile(channel);
            }
            try {
                fileSystemSync.forceDirectory(segmentDir);
            } catch (UncheckedIOException e) {
                // The directory force completed before its channel failed to close.
                throw new SegmentFileException("Failed to close the directory after publishing " + segmentPath, e);
            }
        } catch (IOException | UncheckedIOException | DurabilityException | UnsupportedOperationException | SecurityException e) {
            discardFailedCreation(segmentPath, e);
            throw new SegmentFileException("Failed to flush segment file creation: " + segmentPath, e);
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
        } catch (IOException | UncheckedIOException | DurabilityException | UnsupportedOperationException | SecurityException e) {
            if (failure != e) {
                failure.addSuppressed(e);
            }
        }
    }
}
