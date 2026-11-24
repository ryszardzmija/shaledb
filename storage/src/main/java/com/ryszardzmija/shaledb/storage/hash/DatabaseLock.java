package com.ryszardzmija.shaledb.storage.hash;

import com.ryszardzmija.shaledb.storage.StorageEngineException;
import com.ryszardzmija.shaledb.storage.StorageEngineException.Operation;
import com.ryszardzmija.shaledb.storage.StorageEngineException.Reason;
import com.ryszardzmija.shaledb.storage.hash.segment.model.SegmentChannelCleanup;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static com.ryszardzmija.shaledb.storage.StorageEngineException.MutationOutcome.NOT_APPLICABLE;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;

/** Exclusive ownership of the directory that contains the database's segments. */
final class DatabaseLock implements AutoCloseable {
    private static final String FILENAME = "LOCK";
    // Keep a strong reference even when an unsafe cleanup prevents returning an engine.
    private static final ConcurrentMap<DirectoryIdentity, DatabaseLock> OWNERS = new ConcurrentHashMap<>();

    private record DirectoryIdentity(FileSystem fileSystem, Object key) {}

    private final Path directory;
    private final Path lockPath;
    private final DirectoryIdentity identity;
    // The reservation keeps pending segment channels reachable even if open fails or a caller drops the engine.
    private final SegmentChannelCleanup segmentCleanup = new SegmentChannelCleanup();
    private FileChannel channel;
    private FileLock fileLock;
    private boolean released;
    private Throwable unresolvedRelease;

    private DatabaseLock(Path directory, Object fileKey) {
        this.directory = directory;
        this.lockPath = directory.resolve(FILENAME);
        this.identity = new DirectoryIdentity(directory.getFileSystem(), fileKey == null ? directory : fileKey);
    }

    static DatabaseLock acquire(Path configuredDirectory) {
        DatabaseLock ownership;
        try {
            Path directory = configuredDirectory.toRealPath();
            BasicFileAttributes attributes = Files.readAttributes(directory, BasicFileAttributes.class);
            if (!attributes.isDirectory()) {
                throw new NotDirectoryException(directory.toString());
            }
            ownership = new DatabaseLock(directory, attributes.fileKey());
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            throw failure(Operation.OPEN, Reason.OWNERSHIP_IO,
                    "Failed to resolve database directory " + configuredDirectory, e);
        }

        // Closing a second channel can drop this JVM's existing lock on some systems.
        if (OWNERS.putIfAbsent(ownership.identity, ownership) != null) {
            throw failure(Operation.OPEN, Reason.DATABASE_LOCKED,
                    "Database already reserved in this JVM: " + ownership.lockPath, null);
        }

        String operation = "open";
        try {
            ownership.validateLockFile();
            ownership.channel = FileChannel.open(ownership.lockPath, WRITE, CREATE, NOFOLLOW_LINKS);
            operation = "lock";
            ownership.fileLock = ownership.channel.tryLock();
            if (ownership.fileLock == null) {
                throw failure(Operation.OPEN, Reason.DATABASE_LOCKED,
                        "Database already locked by another process: " + ownership.lockPath, null);
            }
            return ownership;
        } catch (OverlappingFileLockException e) {
            // An unmanaged channel or unsupported alias bypassed the registry. Closing this
            // channel could release that JVM lock, so retain the reservation and channel.
            throw failure(Operation.OPEN, Reason.DATABASE_LOCKED,
                    "Unexpected overlapping JVM lock on " + ownership.lockPath, e);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            StorageEngineException failure = failure(Operation.OPEN, Reason.OWNERSHIP_IO,
                    "Failed to " + operation + " database lock file " + ownership.lockPath, e);
            ownership.closeAfterFailedOpen(failure);
            throw failure;
        } catch (RuntimeException | Error e) {
            ownership.closeAfterFailedOpen(e);
            throw e;
        }
    }

    Path directory() {
        return directory;
    }

    SegmentChannelCleanup segmentCleanup() {
        return segmentCleanup;
    }

    private void validateLockFile() throws IOException {
        try {
            if (!Files.readAttributes(lockPath, BasicFileAttributes.class, NOFOLLOW_LINKS).isRegularFile()) {
                throw failure(Operation.OPEN, Reason.INVALID_LOCK_FILE,
                        "Database lock must be a regular file: " + lockPath, null);
            }
            if (lockPath.getFileSystem().supportedFileAttributeViews().contains("unix")
                    && (int) Files.getAttribute(lockPath, "unix:nlink", NOFOLLOW_LINKS) != 1) {
                throw failure(Operation.OPEN, Reason.INVALID_LOCK_FILE,
                        "Database lock must have a single filesystem link: " + lockPath, null);
            }
        } catch (NoSuchFileException ignored) {
            // A concurrent creator uses the same pathname. CREATE opens the existing entry if present.
        }
    }

    void closeAfterFailedOpen(Throwable failure) {
        try {
            close();
        } catch (RuntimeException | Error cleanupFailure) {
            if (failure != cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
        }
    }

    @Override
    public synchronized void close() {
        if (released) {
            return;
        }
        if (unresolvedRelease != null) {
            throw failure(Operation.CLOSE, Reason.OWNERSHIP_IO,
                    "Database ownership release remains uncertain at " + lockPath, unresolvedRelease);
        }

        Throwable releaseFailure = null;
        try {
            if (fileLock != null) {
                fileLock.release();
            }
        } catch (IOException | RuntimeException | Error e) {
            releaseFailure = e;
        }

        try {
            if (channel != null) {
                channel.close();
            }
            // A successful channel close also releases any lock that explicit release missed.
            OWNERS.remove(identity, this);
            released = true;
        } catch (IOException | RuntimeException | Error e) {
            releaseFailure = SegmentChannelCleanup.suppress(releaseFailure, e);
            // Java's channel-closed flag alone cannot establish that the native lock handle was released.
            unresolvedRelease = releaseFailure;
        }

        if (releaseFailure instanceof Error e) {
            throw e;
        }
        if (releaseFailure != null) {
            throw failure(Operation.CLOSE, Reason.OWNERSHIP_IO,
                    "Failed to release database ownership at " + lockPath, releaseFailure);
        }
    }

    private static StorageEngineException failure(Operation operation, Reason reason, String message, Throwable cause) {
        return new StorageEngineException(message, cause, operation, reason, NOT_APPLICABLE);
    }
}
