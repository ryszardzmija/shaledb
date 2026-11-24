package com.ryszardzmija.shaledb.storage;

import com.ryszardzmija.shaledb.storage.StorageEngineException.MutationOutcome;
import com.ryszardzmija.shaledb.storage.StorageEngineException.Operation;
import com.ryszardzmija.shaledb.storage.StorageEngineException.Reason;
import com.ryszardzmija.shaledb.storage.durability.DurabilityException;
import com.ryszardzmija.shaledb.storage.serialization.spec.FormatInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static com.ryszardzmija.shaledb.storage.KeyValueStoreTestSupport.assertFailure;
import static com.ryszardzmija.shaledb.storage.KeyValueStoreTestSupport.invalidHeader;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KeyValueStoreIoFailureTest {
    private static final byte[] KEY = {1};
    private static final byte[] VALUE = {2};

    @TempDir
    Path directory;

    private final FileChannel reader = mock(FileChannel.class);
    private final FileChannel writer = mock(FileChannel.class);

    @BeforeEach
    void useCanonicalDirectoryForChannelStubs() throws IOException {
        directory = directory.toRealPath();
    }

    @Test
    void lookupReadFailurePreservesTheChannelExceptionAndDoesNotReturnAbsence() throws IOException {
        IOException readFailure = new IOException("failed read");
        completeWrites();
        try (KeyValueStore store = openWithChannels()) {
            store.put(KEY, VALUE);
            when(reader.size()).thenReturn((long) FormatInfo.getHeaderSize() + 2);
            when(reader.read(any(ByteBuffer.class), eq(0L))).thenThrow(readFailure);

            assertThat(store.get(new byte[] {3})).isEmpty();
            assertThat(assertFailure(() -> store.get(KEY), Operation.GET, Reason.STORAGE_IO, MutationOutcome.NOT_APPLICABLE))
                    .hasRootCause(readFailure);
        }
    }

    @Test
    void partialAppendFailurePreservesTheCauseAndSkipsForceAndPublication() throws IOException {
        IOException writeFailure = new IOException("failed append");
        when(writer.write(any(ByteBuffer.class))).thenAnswer(call -> {
            ByteBuffer buffer = call.getArgument(0);
            buffer.position(buffer.position() + 2);
            return 2;
        }).thenThrow(writeFailure);

        try (KeyValueStore store = openWithChannels()) {
            assertThatIllegalArgumentException().isThrownBy(() -> store.put(KEY, null));
            verify(writer, never()).write(any(ByteBuffer.class));

            assertThat(assertFailure(() -> store.put(KEY, VALUE), Operation.PUT, Reason.STORAGE_IO, MutationOutcome.UNKNOWN))
                    .hasRootCause(writeFailure);
            verify(writer, never()).force(true);
            assertThat(store.get(KEY)).isEmpty();
        }
    }

    @Test
    void failedDeleteForceLeavesPersistenceUncertainAndDoesNotPublishTheTombstone() throws IOException {
        IOException forceFailure = new IOException("failed force");
        completeWrites();
        try (KeyValueStore store = openWithChannels()) {
            store.put(KEY, VALUE);
            doThrow(forceFailure).doNothing().when(writer).force(true);

            StorageEngineException failure = assertFailure(() -> store.delete(KEY),
                    Operation.DELETE, Reason.DURABILITY, MutationOutcome.UNKNOWN);
            assertThat(failure).hasRootCause(forceFailure);
            assertThat(failure.getCause().getCause().getCause())
                    .isInstanceOfSatisfying(DurabilityException.class,
                            cause -> assertThat(cause.target()).isEqualTo(DurabilityException.Target.FILE));
            // The original indexed record still drives the lookup, so it tries to read its header.
            when(reader.size()).thenThrow(new IOException("indexed record requires I/O"));
            assertFailure(() -> store.get(KEY), Operation.GET, Reason.STORAGE_IO, MutationOutcome.NOT_APPLICABLE);
        }
    }

    @Test
    void failedSizeCheckAfterDeleteReportsThatTheForceCompleted() throws IOException {
        IOException sizeFailure = new IOException("failed rollover size check");
        completeWrites();
        try (KeyValueStore store = openWithChannels()) {
            when(reader.size()).thenThrow(sizeFailure);

            assertThat(assertFailure(() -> store.delete(KEY), Operation.DELETE, Reason.STORAGE_IO,
                    MutationOutcome.FORCE_COMPLETED)).hasRootCause(sizeFailure);
            verify(writer).force(true);
            assertThat(store.get(KEY)).isEmpty();
        }
    }

    @Test
    void failedRolloverKeepsTheOpenFailurePrimaryWhenReplacementCleanupFails() throws IOException {
        Path active = createSegment(1);
        Path replacement = directory.resolve("segment_0000000000000000002.log");
        FileChannel replacementReader = mock(FileChannel.class);
        FileChannel replacementWriter = mock(FileChannel.class);
        FileChannel directoryChannel = mock(FileChannel.class);
        IOException immutableOpenFailure = new IOException("failed immutable open");
        IOException closeFailure = new IOException("failed replacement read close");
        IOException directoryForceFailure = new IOException("failed directory force after deletion");
        completeWrites();
        doThrow(closeFailure).when(replacementReader).close();
        doNothing().doThrow(directoryForceFailure).when(directoryChannel).force(true);
        try (MockedStatic<FileChannel> channels = mockChannels()) {
            channels.when(() -> FileChannel.open(active, READ)).thenReturn(reader).thenThrow(immutableOpenFailure);
            channels.when(() -> FileChannel.open(active, WRITE)).thenReturn(writer);
            createRealFileThroughMock(channels, replacement);
            channels.when(() -> FileChannel.open(replacement, READ)).thenReturn(replacementReader);
            channels.when(() -> FileChannel.open(replacement, WRITE)).thenReturn(replacementWriter);
            channels.when(() -> FileChannel.open(directory, READ)).thenReturn(directoryChannel);
            try (KeyValueStore store = open()) {
                when(reader.size()).thenReturn(256L);

                StorageEngineException failure = assertFailure(() -> store.put(KEY, VALUE),
                        Operation.PUT, Reason.STORAGE_IO, MutationOutcome.FORCE_COMPLETED);
                assertThat(failure).hasRootCause(immutableOpenFailure);
                Throwable[] cleanup = failure.getCause().getCause().getSuppressed();
                assertThat(cleanup).hasSize(2);
                assertThat(cleanup[0]).hasCause(closeFailure);
                assertThat(cleanup[1]).isInstanceOf(DurabilityException.class).hasCause(directoryForceFailure);
                verify(replacementWriter).close();
                assertThat(replacement).doesNotExist();
            }
        }
    }

    @Test
    void failedStartupClosesTheCorruptSegmentAndOlderChannelsAndRetainsCleanupFailures() throws IOException {
        Path older = createSegment(1);
        Path active = createSegment(2);
        FileChannel olderReader = mock(FileChannel.class);
        IOException localCloseFailure = new IOException("failed active read close");
        IOException olderCloseFailure = new IOException("failed older read close");
        byte[] header = invalidHeader();
        when(reader.size()).thenReturn((long) header.length);
        when(reader.read(any(ByteBuffer.class), eq(0L))).thenAnswer(call -> {
            call.<ByteBuffer>getArgument(0).put(header);
            return header.length;
        });
        doThrow(localCloseFailure).when(reader).close();
        doThrow(olderCloseFailure).when(olderReader).close();

        try (MockedStatic<FileChannel> channels = mockChannels()) {
            FileLock lock = stubOwnershipLock(channels);
            channels.when(() -> FileChannel.open(older, READ)).thenReturn(olderReader);
            channels.when(() -> FileChannel.open(active, READ)).thenReturn(reader);
            channels.when(() -> FileChannel.open(active, WRITE)).thenReturn(writer);

            StorageEngineException failure = assertFailure(this::open, Operation.OPEN,
                    Reason.STRUCTURAL_CORRUPTION, MutationOutcome.NOT_APPLICABLE);
            Throwable segmentFailure = failure.getCause().getCause();
            assertThat(segmentFailure.getCause().getSuppressed()).containsExactly(localCloseFailure);
            assertThat(segmentFailure.getSuppressed()).singleElement()
                    .satisfies(cleanup -> assertThat(cleanup).hasCause(olderCloseFailure));
            verify(reader).close();
            verify(writer).close();
            verify(olderReader).close();
            verify(lock, never()).release();
            assertLocked();
        }
    }

    @Test
    void closeKeepsForceFailurePrimaryAndAttemptsEveryChannelClose() throws IOException {
        Path older = createSegment(1);
        Path active = createSegment(2);
        FileChannel olderReader = mock(FileChannel.class);
        IOException forceFailure = new IOException("failed final force");
        IOException readerFailure = new IOException("failed active read close");
        IOException writerFailure = new IOException("failed active write close");
        IOException olderFailure = new IOException("failed older read close");
        doThrow(forceFailure).when(writer).force(true);
        doThrow(readerFailure).when(reader).close();
        doThrow(writerFailure).when(writer).close();
        doThrow(olderFailure).when(olderReader).close();
        KeyValueStore store;
        FileLock lock;
        try (MockedStatic<FileChannel> channels = mockChannels()) {
            lock = stubOwnershipLock(channels);
            channels.when(() -> FileChannel.open(older, READ)).thenReturn(olderReader);
            channels.when(() -> FileChannel.open(active, READ)).thenReturn(reader);
            channels.when(() -> FileChannel.open(active, WRITE)).thenReturn(writer);
            store = open();
        }

        StorageEngineException failure = assertFailure(store::close,
                Operation.CLOSE, Reason.DURABILITY, MutationOutcome.NOT_APPLICABLE);
        assertThat(failure).hasRootCause(forceFailure);
        Throwable[] cleanup = failure.getCause().getSuppressed();
        assertThat(cleanup).hasSize(2);
        assertThat(cleanup[0]).hasCause(readerFailure);
        assertThat(readerFailure.getSuppressed()).containsExactly(writerFailure);
        assertThat(cleanup[1]).hasCause(olderFailure);
        verify(reader).close();
        verify(writer).close();
        verify(olderReader).close();
        verify(lock, never()).release();
        assertLocked();
        assertThatIllegalStateException().isThrownBy(() -> store.get(KEY));
    }

    @Test
    void closeReportsAStandaloneChannelCloseFailure() throws IOException {
        IOException closeFailure = new IOException("failed close");
        doThrow(closeFailure).when(writer).close();
        Path active = createSegment(1);
        KeyValueStore store;
        try (MockedStatic<FileChannel> channels = mockChannels()) {
            stubOwnershipLock(channels);
            channels.when(() -> FileChannel.open(active, READ)).thenReturn(reader);
            channels.when(() -> FileChannel.open(active, WRITE)).thenReturn(writer);
            store = open();
        }

        assertThat(assertFailure(store::close, Operation.CLOSE, Reason.STORAGE_IO, MutationOutcome.NOT_APPLICABLE))
                .hasRootCause(closeFailure);
        verify(reader).close();
    }

    @Test
    void closeKeepsOwnershipUntilEverySegmentChannelHasClosed() throws IOException {
        Path older = createSegment(1);
        Path active = createSegment(2);
        FileChannel olderReader = mock(FileChannel.class);

        try (MockedStatic<FileChannel> channels = mockChannels()) {
            FileLock lock = stubOwnershipLock(channels);
            doAnswer(call -> {
                verify(reader).close();
                verify(writer).close();
                verify(lock, never()).release();
                assertLocked();
                return null;
            }).when(olderReader).close();
            channels.when(() -> FileChannel.open(older, READ)).thenReturn(olderReader);
            channels.when(() -> FileChannel.open(active, READ)).thenReturn(reader);
            channels.when(() -> FileChannel.open(active, WRITE)).thenReturn(writer);
            try (KeyValueStore store = open()) {
                assertThat(store.get(KEY)).isEmpty();
            }
            verify(lock).release();
        }
        verify(olderReader).close();
        try (KeyValueStore reopened = open()) {
            assertThat(reopened.get(KEY)).isEmpty();
        }
    }

    @Test
    void startupKeepsSegmentOpenFailurePrimaryWhenOwnershipReleaseAlsoFails() throws IOException {
        Path active = createSegment(1);
        IOException openFailure = new IOException("failed segment write open");
        IOException releaseFailure = new IOException("failed ownership release");
        try (MockedStatic<FileChannel> channels = mockChannels()) {
            FileLock lock = stubOwnershipLock(channels);
            doThrow(releaseFailure).when(lock).release();
            channels.when(() -> FileChannel.open(active, READ)).thenReturn(reader);
            channels.when(() -> FileChannel.open(active, WRITE)).thenThrow(openFailure);

            StorageEngineException failure = assertFailure(this::open, Operation.OPEN, Reason.STORAGE_IO,
                    MutationOutcome.NOT_APPLICABLE);
            assertThat(failure).hasRootCause(openFailure);
            assertThat(failure.getCause().getSuppressed()).singleElement()
                    .isInstanceOfSatisfying(StorageEngineException.class, cleanup -> {
                        assertThat(cleanup.operation()).isEqualTo(Operation.CLOSE);
                        assertThat(cleanup.reason()).isEqualTo(Reason.OWNERSHIP_IO);
                        assertThat(cleanup).hasCause(releaseFailure);
                    });
            verify(reader).close();
        }
        try (KeyValueStore reopened = open()) {
            assertThat(reopened.get(KEY)).isEmpty();
        }
    }

    @Test
    void closeKeepsForceFailurePrimaryWhenOwnershipReleaseAlsoFails() throws IOException {
        Path active = createSegment(1);
        IOException forceFailure = new IOException("failed final force");
        IOException releaseFailure = new IOException("failed ownership release");
        doThrow(forceFailure).when(writer).force(true);
        KeyValueStore store;
        try (MockedStatic<FileChannel> channels = mockChannels()) {
            FileLock lock = stubOwnershipLock(channels);
            doThrow(releaseFailure).when(lock).release();
            channels.when(() -> FileChannel.open(active, READ)).thenReturn(reader);
            channels.when(() -> FileChannel.open(active, WRITE)).thenReturn(writer);
            store = open();
        }

        StorageEngineException failure = assertFailure(store::close,
                Operation.CLOSE, Reason.DURABILITY, MutationOutcome.NOT_APPLICABLE);
        assertThat(failure).hasRootCause(forceFailure);
        assertThat(failure.getCause().getSuppressed()).singleElement()
                .isInstanceOfSatisfying(StorageEngineException.class, cleanup -> {
                    assertThat(cleanup.reason()).isEqualTo(Reason.OWNERSHIP_IO);
                    assertThat(cleanup).hasCause(releaseFailure);
                });
        try (KeyValueStore reopened = open()) {
            assertThat(reopened.get(KEY)).isEmpty();
        }
    }

    @Test
    void startupPreservesFileForceFailureAndSuppressedFileCloseFailure() throws IOException {
        FileChannel newFile = mock(FileChannel.class);
        FileChannel directoryChannel = mock(FileChannel.class);
        Path first = directory.resolve("segment_0000000000000000001.log");
        IOException forceFailure = new IOException("failed new file force");
        IOException closeFailure = new IOException("failed new file close");
        doThrow(forceFailure).when(newFile).force(true);
        doThrow(closeFailure).when(newFile).close();
        try (MockedStatic<FileChannel> channels = mockChannels()) {
            FileLock lock = stubOwnershipLock(channels);
            channels.when(() -> FileChannel.open(first, WRITE, CREATE_NEW)).thenAnswer(call -> {
                Files.createFile(first);
                return newFile;
            });
            channels.when(() -> FileChannel.open(directory, READ)).thenReturn(directoryChannel);

            StorageEngineException failure = assertFailure(this::open, Operation.OPEN, Reason.DURABILITY,
                    MutationOutcome.NOT_APPLICABLE);
            assertThat(failure).hasRootCause(forceFailure);
            assertThat(failure.getCause().getCause()).isInstanceOfSatisfying(DurabilityException.class, cause -> {
                assertThat(cause.target()).isEqualTo(DurabilityException.Target.FILE);
                assertThat(cause.getSuppressed()).containsExactly(closeFailure);
            });
            assertThat(first).doesNotExist();
            verify(newFile).close();
            verify(directoryChannel).force(true);
            verify(lock, never()).release();
            assertLocked();
        }
    }

    @Test
    void startupPreservesDirectoryForceFailureAndCleanupFailure() throws IOException {
        FileChannel directoryChannel = mock(FileChannel.class);
        IOException forceFailure = new IOException("failed directory force");
        IOException cleanupFailure = new IOException("failed cleanup directory force");
        doThrow(forceFailure).doThrow(cleanupFailure).when(directoryChannel).force(true);
        try (MockedStatic<FileChannel> channels = mockChannels()) {
            createRealFileThroughMock(channels, directory.resolve("segment_0000000000000000001.log"));
            channels.when(() -> FileChannel.open(directory, READ)).thenReturn(directoryChannel);

            StorageEngineException failure = assertFailure(this::open, Operation.OPEN, Reason.DURABILITY,
                    MutationOutcome.NOT_APPLICABLE);
            assertThat(failure).hasRootCause(forceFailure);
            assertThat(failure.getCause().getCause()).isInstanceOfSatisfying(DurabilityException.class, cause -> {
                assertThat(cause.target()).isEqualTo(DurabilityException.Target.DIRECTORY);
                assertThat(cause.getSuppressed()).singleElement()
                        .satisfies(cleanup -> assertThat(cleanup).hasCause(cleanupFailure));
            });
            assertThat(KeyValueStoreTestSupport.segmentCount(directory)).isZero();
        }
        try (KeyValueStore reopened = open()) {
            assertThat(reopened.get(KEY)).isEmpty();
        }
    }

    @Test
    void startupReportsDirectoryCloseFailureAfterForceWithoutDeletingThePublishedFile() throws IOException {
        FileChannel directoryChannel = mock(FileChannel.class);
        IOException closeFailure = new IOException("failed directory close");
        doThrow(closeFailure).when(directoryChannel).close();
        try (MockedStatic<FileChannel> channels = mockChannels()) {
            createRealFileThroughMock(channels, directory.resolve("segment_0000000000000000001.log"));
            channels.when(() -> FileChannel.open(directory, READ)).thenReturn(directoryChannel);

            assertThat(assertFailure(this::open, Operation.OPEN, Reason.STORAGE_IO, MutationOutcome.NOT_APPLICABLE))
                    .hasRootCause(closeFailure);
            assertThat(KeyValueStoreTestSupport.segmentCount(directory)).isOne();
        }
    }

    private static void createRealFileThroughMock(MockedStatic<FileChannel> channels, Path path) {
        channels.when(() -> FileChannel.open(path, WRITE, CREATE_NEW))
                .thenAnswer(call -> path.getFileSystem().provider().newFileChannel(path, Set.of(WRITE, CREATE_NEW)));
    }

    private MockedStatic<FileChannel> mockChannels() {
        Path lockPath = directory.resolve("LOCK");
        MockedStatic<FileChannel> channels = mockStatic(FileChannel.class);
        channels.when(() -> FileChannel.open(lockPath, WRITE, CREATE, NOFOLLOW_LINKS))
                .thenAnswer(call -> lockPath.getFileSystem().provider().newFileChannel(lockPath,
                        Set.of(WRITE, CREATE, NOFOLLOW_LINKS)));
        return channels;
    }

    private FileLock stubOwnershipLock(MockedStatic<FileChannel> channels) throws IOException {
        FileChannel lockChannel = mock(FileChannel.class);
        FileLock lock = mock(FileLock.class);
        when(lockChannel.tryLock()).thenReturn(lock);
        channels.when(() -> FileChannel.open(directory.resolve("LOCK"), WRITE, CREATE, NOFOLLOW_LINKS))
                .thenReturn(lockChannel);
        return lock;
    }

    private void assertLocked() {
        assertFailure(() -> {
            try (KeyValueStore unexpected = open()) {
                throw new AssertionError("Contender acquired ownership during cleanup");
            }
        }, Operation.OPEN, Reason.DATABASE_LOCKED, MutationOutcome.NOT_APPLICABLE);
    }

    private void completeWrites() throws IOException {
        when(writer.write(any(ByteBuffer.class))).thenAnswer(call -> {
            ByteBuffer buffer = call.getArgument(0);
            int bytes = buffer.remaining();
            buffer.position(buffer.limit());
            return bytes;
        });
    }

    private KeyValueStore openWithChannels() throws IOException {
        Path path = createSegment(1);
        try (MockedStatic<FileChannel> channels = mockChannels()) {
            channels.when(() -> FileChannel.open(path, READ)).thenReturn(reader);
            channels.when(() -> FileChannel.open(path, WRITE)).thenReturn(writer);
            return open();
        }
    }

    private KeyValueStore open() {
        return new KeyValueStore(KeyValueStoreTestSupport.config(directory, 256));
    }

    private Path createSegment(long id) throws IOException {
        return Files.createFile(directory.resolve("segment_%019d.log".formatted(id)));
    }
}
