package com.ryszardzmija.shaledb.storage.hash;

import com.ryszardzmija.shaledb.storage.StorageEngineException;
import com.ryszardzmija.shaledb.storage.StorageEngineException.Operation;
import com.ryszardzmija.shaledb.storage.StorageEngineException.Reason;
import com.ryszardzmija.shaledb.storage.config.StorageConfig;
import com.ryszardzmija.shaledb.storage.durability.DurabilityMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.mockito.stubbing.OngoingStubbing;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

import static com.ryszardzmija.shaledb.storage.StorageEngineException.MutationOutcome.NOT_APPLICABLE;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.junit.jupiter.api.Assumptions.abort;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatabaseLockTest {
    @TempDir
    Path directory;
    private Path lockPath;

    @BeforeEach
    void locateLockPath() throws IOException {
        directory = directory.toRealPath();
        lockPath = directory.resolve("LOCK");
    }

    @Test
    void ownsTheDirectoryBeforeInitialSegmentCreation() throws IOException {
        var config = new StorageConfig(256, 128, directory, DurabilityMode.SYNC_EACH_WRITE);
        assertThat(lockPath).doesNotExist();
        try (var ownership = DatabaseLock.acquire(directory)) {
            assertThat(ownership.directory()).isEqualTo(directory);
            assertThat(lockPath).isRegularFile();
            assertThat(Files.size(lockPath)).isZero();
            assertThat(openFailure(Reason.DATABASE_LOCKED)).hasMessageContaining(lockPath.toString());
            StorageEngineException failure = catchThrowableOfType(() -> {
                new HashIndexEngine(config).close();
                throw new AssertionError("Contender opened an engine");
            }, StorageEngineException.class);
            assertThat(failure).isNotNull();
            assertThat(failure.operation()).isEqualTo(Operation.OPEN);
            assertThat(failure.reason()).isEqualTo(Reason.DATABASE_LOCKED);
            try (var files = Files.list(directory)) {
                assertThat(files).containsExactly(lockPath);
            }
        }
        var engine = new HashIndexEngine(config);
        try {
            assertThat(directory.resolve("segment_0000000000000000001.log")).isRegularFile();
        } finally {
            engine.close();
        }
    }

    @Test
    void reusesTheSameLockFileWithoutChangingItsContents() throws IOException {
        byte[] existing = {5, 6, 7};
        Files.write(lockPath, existing);
        Object initialKey = Files.readAttributes(lockPath, BasicFileAttributes.class).fileKey();

        var first = DatabaseLock.acquire(directory);
        try (first) {
            openFailure(Reason.DATABASE_LOCKED);
            assertThat(lockPath).isRegularFile();
        }
        try (var successor = DatabaseLock.acquire(directory)) {
            first.close();
            openFailure(Reason.DATABASE_LOCKED);
            assertThat(successor.directory()).isEqualTo(directory);
        }

        assertThat(Files.readAllBytes(lockPath)).containsExactly(existing);
        if (initialKey != null) {
            assertThat(Files.readAttributes(lockPath, BasicFileAttributes.class).fileKey()).isEqualTo(initialKey);
        }
    }

    @Test
    void rejectsARegularFileAsTheDatabaseDirectory() throws IOException {
        Path notDirectory = Files.createFile(directory.resolve("file"));
        StorageEngineException failure = catchThrowableOfType(
                () -> DatabaseLock.acquire(notDirectory), StorageEngineException.class);

        assertThat(failure).isNotNull().hasRootCauseInstanceOf(NotDirectoryException.class);
        assertThat(failure.operation()).isEqualTo(Operation.OPEN);
        assertThat(failure.reason()).isEqualTo(Reason.OWNERSHIP_IO);
    }

    @Test
    void rejectsADirectoryAtTheLockPathAndAllowsAcquisitionAfterItIsRemoved() throws IOException {
        Files.createDirectory(lockPath);
        openFailure(Reason.INVALID_LOCK_FILE);
        assertThat(lockPath).isDirectory();
        Files.delete(lockPath);

        try (var ownership = DatabaseLock.acquire(directory)) {
            assertThat(lockPath).isRegularFile();
        }
    }

    @Test
    void rejectsLockSymlinksWhetherOrNotTheirTargetExists() throws IOException {
        Path target = Files.write(directory.resolve("target"), new byte[] {8});
        try {
            Files.createSymbolicLink(lockPath, target);
        } catch (UnsupportedOperationException | FileSystemException e) {
            abort("Symbolic links unavailable: " + e);
        }

        openFailure(Reason.INVALID_LOCK_FILE);
        assertThat(Files.readSymbolicLink(lockPath)).isEqualTo(target);
        assertThat(Files.readAllBytes(target)).containsExactly(8);
        Files.delete(target);

        openFailure(Reason.INVALID_LOCK_FILE);
        assertThat(Files.readSymbolicLink(lockPath)).isEqualTo(target);
        assertThat(target).doesNotExist();
    }

    @Test
    void rejectsHardLinkedLockFilesWhenTheProviderExposesLinkCounts() throws IOException {
        assumeTrue(directory.getFileSystem().supportedFileAttributeViews().contains("unix"),
                "Unix hard-link counts are unavailable");
        Path target = Files.write(directory.resolve("target"), new byte[] {8});
        Files.createLink(lockPath, target);

        openFailure(Reason.INVALID_LOCK_FILE);
        assertThat(Files.isSameFile(target, lockPath)).isTrue();
        assertThat(Files.readAllBytes(target)).containsExactly(8);
    }

    @Test
    void releasesTheReservationAfterAnOpenFailure() throws IOException {
        Files.createFile(lockPath);
        IOException denied = new AccessDeniedException(lockPath.toString());
        try (var channels = mockStatic(FileChannel.class)) {
            stubOpen(channels).thenThrow(denied);
            assertThat(openFailure(Reason.OWNERSHIP_IO)).hasCause(denied);
            assertThat(lockPath).isRegularFile();
        }
        try (var ownership = DatabaseLock.acquire(directory)) {
            assertThat(ownership.directory()).isEqualTo(directory);
        }
    }

    @Test
    void closesTheAttemptChannelWhenLockingIsUnsupported() throws IOException {
        FileChannel channel = mock(FileChannel.class);
        var unsupported = new UnsupportedOperationException("tryLock unavailable");
        when(channel.tryLock()).thenThrow(unsupported);
        try (var channels = mockStatic(FileChannel.class)) {
            stubOpen(channels).thenReturn(channel);
            assertThat(openFailure(Reason.OWNERSHIP_IO)).hasCause(unsupported);
        }
        verify(channel).close();
        try (var ownership = DatabaseLock.acquire(directory)) {
            assertThat(ownership.directory()).isEqualTo(directory);
        }
    }

    @Test
    void doesNotCloseAChannelThatUnexpectedlyOverlapsAnUnmanagedJvmLock() throws IOException {
        FileChannel channel = mock(FileChannel.class);
        var overlap = new OverlappingFileLockException();
        when(channel.tryLock()).thenThrow(overlap);
        try (var channels = mockStatic(FileChannel.class)) {
            stubOpen(channels).thenReturn(channel);
            assertThat(openFailure(Reason.DATABASE_LOCKED)).hasCause(overlap);
        }
        verify(channel, never()).close();
        openFailure(Reason.DATABASE_LOCKED);
    }

    @Test
    void preservesAnAcquisitionFailureAndRetainsTheReservationIfTheAttemptChannelCannotClose() throws IOException {
        FileChannel channel = mock(FileChannel.class);
        var lockFailure = new IOException("failed to acquire lock");
        var closeFailure = new IOException("failed to close attempt channel");
        when(channel.tryLock()).thenThrow(lockFailure);
        doThrow(closeFailure).when(channel).close();
        try (var channels = mockStatic(FileChannel.class)) {
            stubOpen(channels).thenReturn(channel);
            StorageEngineException failure = openFailure(Reason.OWNERSHIP_IO);
            assertThat(failure).hasCause(lockFailure);
            assertThat(failure.getSuppressed()).singleElement().satisfies(cleanup ->
                    assertThat(cleanup).isInstanceOf(StorageEngineException.class).hasCause(closeFailure));
        }
        openFailure(Reason.DATABASE_LOCKED);
    }

    @Test
    void preservesBothFailuresAndKeepsTheReservationWhenReleaseAndChannelCloseFail() throws IOException {
        FileChannel channel = mock(FileChannel.class);
        FileLock lock = mock(FileLock.class);
        var releaseFailure = new IOException("failed to release lock");
        var closeFailure = new IOException("failed to close lock channel");
        when(channel.tryLock()).thenReturn(lock);
        doThrow(releaseFailure).when(lock).release();
        doThrow(closeFailure).when(channel).close();

        DatabaseLock ownership;
        try (var channels = mockStatic(FileChannel.class)) {
            stubOpen(channels).thenReturn(channel);
            ownership = DatabaseLock.acquire(directory);
        }
        StorageEngineException failure = catchThrowableOfType(ownership::close, StorageEngineException.class);
        assertThat(failure).isNotNull().hasCause(releaseFailure);
        assertThat(failure.operation()).isEqualTo(Operation.CLOSE);
        assertThat(failure.reason()).isEqualTo(Reason.OWNERSHIP_IO);
        assertThat(releaseFailure.getSuppressed()).containsExactly(closeFailure);
        ownership.close();
        verify(lock).release();
        verify(channel).close();
        openFailure(Reason.DATABASE_LOCKED);
    }

    private StorageEngineException openFailure(Reason reason) {
        StorageEngineException failure = catchThrowableOfType(() -> {
            try (var unexpected = DatabaseLock.acquire(directory)) {
                throw new AssertionError("Unexpected successful acquisition");
            }
        }, StorageEngineException.class);
        assertThat(failure).isNotNull();
        assertThat(failure.operation()).isEqualTo(Operation.OPEN);
        assertThat(failure.reason()).isEqualTo(reason);
        assertThat(failure.mutationOutcome()).isEqualTo(NOT_APPLICABLE);
        return failure;
    }

    private OngoingStubbing<FileChannel> stubOpen(MockedStatic<FileChannel> channels) {
        return channels.when(() -> FileChannel.open(lockPath, WRITE, CREATE, NOFOLLOW_LINKS));
    }
}
