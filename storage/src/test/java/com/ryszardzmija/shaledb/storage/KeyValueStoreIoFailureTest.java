package com.ryszardzmija.shaledb.storage;

import com.ryszardzmija.shaledb.storage.StorageEngineException.MutationOutcome;
import com.ryszardzmija.shaledb.storage.StorageEngineException.Operation;
import com.ryszardzmija.shaledb.storage.StorageEngineException.Reason;
import com.ryszardzmija.shaledb.storage.config.StorageConfig;
import com.ryszardzmija.shaledb.storage.durability.DurabilityException;
import com.ryszardzmija.shaledb.storage.durability.DurabilityMode;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.ryszardzmija.shaledb.storage.KeyValueStoreTestSupport.assertFailed;
import static com.ryszardzmija.shaledb.storage.KeyValueStoreTestSupport.assertFailure;
import static com.ryszardzmija.shaledb.storage.KeyValueStoreTestSupport.assertValue;
import static com.ryszardzmija.shaledb.storage.KeyValueStoreTestSupport.invalidHeader;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class KeyValueStoreIoFailureTest {
    private static final byte[] KEY = {1};
    private static final byte[] VALUE = {2};

    // Keep directories alive while fault-injection tests retain their file keys in the JVM registry.
    @TempDir
    static Path testRoot;

    private Path directory;

    private final FileChannel reader = mock(FileChannel.class);
    private final FileChannel writer = mock(FileChannel.class);

    @BeforeEach
    void createDatabaseDirectory() throws IOException {
        directory = Files.createTempDirectory(testRoot, "database-").toRealPath();
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
            StorageEngineException failure = assertFailure(() -> store.get(KEY), Operation.GET, Reason.STORAGE_IO,
                    MutationOutcome.NOT_APPLICABLE);
            assertThat(failure).hasRootCause(readFailure);
            clearInvocations(reader, writer);
            assertFailed(store, failure, KEY);
            assertFailed(store, failure, new byte[] {3});
            verifyNoInteractions(reader, writer);
        }
    }

    @Test
    void partialAppendFailurePreservesTheCauseAndSkipsForce() throws IOException {
        IOException writeFailure = new IOException("failed append");
        when(writer.write(any(ByteBuffer.class))).thenAnswer(call -> {
            ByteBuffer buffer = call.getArgument(0);
            buffer.position(buffer.position() + 2);
            return 2;
        }).thenThrow(writeFailure);

        try (KeyValueStore store = openWithChannels()) {
            assertThatIllegalArgumentException().isThrownBy(() -> store.put(KEY, null));
            verify(writer, never()).write(any(ByteBuffer.class));

            StorageEngineException failure = assertFailure(() -> store.put(KEY, VALUE), Operation.PUT, Reason.STORAGE_IO,
                    MutationOutcome.UNKNOWN);
            assertThat(failure).hasRootCause(writeFailure);
            verify(writer, never()).force(true);
            assertFailed(store, failure, KEY);
        }
    }

    @Test
    void failedDeleteForceReportsUncertainPersistenceAndDisablesOperations() throws IOException {
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
            assertFailed(store, failure, KEY);
        }
        verify(writer, times(2)).force(true);
    }

    @Test
    void failedSizeCheckBeforeDeleteSkipsAppendAndDisablesOperations() throws IOException {
        IOException sizeFailure = new IOException("failed rollover size check");
        completeWrites();
        try (KeyValueStore store = openWithChannels()) {
            when(reader.size()).thenThrow(sizeFailure);

            StorageEngineException failure = assertFailure(() -> store.delete(KEY), Operation.DELETE, Reason.STORAGE_IO,
                    MutationOutcome.NOT_APPENDED);
            assertThat(failure).hasRootCause(sizeFailure);
            verifyNoInteractions(writer);
            assertFailed(store, failure, KEY);
        }
    }

    @Test
    void failedSuccessorEmptyCheckKeepsThePrimaryFailureAndThePublishedEmptyFile() throws IOException {
        Path active = createSegment(1);
        Path replacement = directory.resolve("segment_0000000000000000002.log");
        FileChannel replacementReader = mock(FileChannel.class);
        FileChannel replacementWriter = mock(FileChannel.class);
        IOException initializationFailure = new IOException("failed to check successor extent");
        IOException closeFailure = new IOException("failed replacement read close");
        IOException historyCloseFailure = new IOException("failed historical read close");
        when(replacementReader.size()).thenThrow(initializationFailure);
        doThrow(closeFailure).when(replacementReader).close();
        doThrow(historyCloseFailure).when(reader).close();
        try (MockedStatic<FileChannel> channels = mockChannels()) {
            channels.when(() -> FileChannel.open(active, READ)).thenReturn(reader);
            channels.when(() -> FileChannel.open(active, WRITE)).thenReturn(writer);
            createRealFileThroughMock(channels, replacement);
            channels.when(() -> FileChannel.open(replacement, READ)).thenReturn(replacementReader);
            channels.when(() -> FileChannel.open(replacement, WRITE)).thenReturn(replacementWriter);
            try (KeyValueStore store = open()) {
                when(reader.size()).thenReturn(256L);

                StorageEngineException failure = assertFailure(() -> store.put(KEY, VALUE),
                        Operation.PUT, Reason.STORAGE_IO, MutationOutcome.NOT_APPENDED);
                assertThat(failure).hasRootCause(initializationFailure);
                Throwable segmentFailure = failure.getCause().getCause();
                assertThat(segmentFailure.getCause().getSuppressed()).containsExactly(closeFailure);
                assertThat(segmentFailure.getSuppressed()).singleElement().satisfies(e -> assertThat(e).hasCause(historyCloseFailure));
                verify(replacementWriter).close();
                assertThat(active).isEmptyFile();
                assertThat(replacement).isEmptyFile();
                assertFailed(store, failure, KEY);
                assertLocked();
            }
        }
        try (KeyValueStore reopened = open()) {
            reopened.put(KEY, VALUE);
            assertThat(active).isEmptyFile();
            assertThat(Files.size(replacement)).isEqualTo(FormatInfo.getHeaderSize() + 2);
        }
    }

    @Test
    void failedSuccessorWriterOpenClosesTheAcquiredReaderAndLeavesTheEmptyFile() throws IOException {
        Path active = createSegment(1);
        Path successor = directory.resolve("segment_0000000000000000002.log");
        FileChannel successorReader = mock(FileChannel.class);
        IOException injected = new IOException("failed to open successor writer");
        try (MockedStatic<FileChannel> channels = mockChannels()) {
            channels.when(() -> FileChannel.open(active, READ)).thenReturn(reader);
            channels.when(() -> FileChannel.open(active, WRITE)).thenReturn(writer);
            createRealFileThroughMock(channels, successor);
            channels.when(() -> FileChannel.open(successor, READ)).thenReturn(successorReader);
            channels.when(() -> FileChannel.open(successor, WRITE)).thenThrow(injected);
            try (KeyValueStore store = open()) {
                when(reader.size()).thenReturn(256L);
                StorageEngineException failure = assertFailure(() -> store.put(KEY, VALUE),
                        Operation.PUT, Reason.STORAGE_IO, MutationOutcome.NOT_APPENDED);
                assertThat(failure).hasRootCause(injected);
                verify(successorReader).close();
                verify(reader).close();
                verify(writer).close();
                assertThat(active).isEmptyFile();
                assertThat(successor).isEmptyFile();
                assertFailed(store, failure, KEY);
                assertLocked();
            }
        }
        try (KeyValueStore reopened = open()) {
            assertThat(reopened.get(KEY)).isEmpty();
        }
    }

    @Test
    void unexpectedRolloverFailureClosesTheUnpublishedSuccessor() throws IOException {
        Path active = createSegment(1);
        Path replacement = directory.resolve("segment_0000000000000000002.log");
        FileChannel replacementReader = mock(FileChannel.class);
        FileChannel replacementWriter = mock(FileChannel.class);
        var failure = new AssertionError("unexpected successor initialization failure");
        when(replacementReader.size()).thenThrow(failure);
        try (MockedStatic<FileChannel> channels = mockChannels()) {
            channels.when(() -> FileChannel.open(active, READ)).thenReturn(reader);
            channels.when(() -> FileChannel.open(active, WRITE)).thenReturn(writer);
            createRealFileThroughMock(channels, replacement);
            channels.when(() -> FileChannel.open(replacement, READ)).thenReturn(replacementReader);
            channels.when(() -> FileChannel.open(replacement, WRITE)).thenReturn(replacementWriter);
            try (KeyValueStore store = open()) {
                when(reader.size()).thenReturn(256L);
                assertThatThrownBy(() -> store.put(KEY, VALUE)).isSameAs(failure);

                verify(replacementReader).close();
                verify(replacementWriter).close();
                verify(reader).close();
                verify(writer).close();
                assertThat(replacement).isEmptyFile();
                assertThat(active).isEmptyFile();
                assertFailed(store, failure, KEY);
                assertLocked();
            }
        }
        try (KeyValueStore reopened = open()) {
            assertThat(reopened.get(KEY)).isEmpty();
        }
    }

    @Test
    void rolloverRetirementFailureDisablesOperationsBeforeCreatingTheSuccessor() throws IOException {
        Path active = createSegment(1);
        Path replacement = directory.resolve("segment_0000000000000000002.log");
        IOException closeFailure = new IOException("failed old writer close");
        var config = new StorageConfig(FormatInfo.getEncodedRecordSize(2), 2, directory, DurabilityMode.SYNC_EACH_WRITE);
        byte[] original;
        try (FileChannel fileReader = FileChannel.open(active, READ);
             FileChannel fileWriter = FileChannel.open(active, WRITE);
             MockedStatic<FileChannel> channels = mockChannels()) {
            FileChannel retiringReader = mock(FileChannel.class, delegatesTo(fileReader));
            FileChannel retiringWriter = mock(FileChannel.class, delegatesTo(fileWriter));
            doThrow(closeFailure).doAnswer(call -> {
                fileWriter.close();
                return null;
            }).when(retiringWriter).close();
            channels.when(() -> FileChannel.open(active, READ)).thenReturn(retiringReader);
            channels.when(() -> FileChannel.open(active, WRITE)).thenReturn(retiringWriter);
            try (KeyValueStore store = new KeyValueStore(config)) {
                store.put(KEY, VALUE);
                original = Files.readAllBytes(active);
                clearInvocations(retiringWriter);
                StorageEngineException failure = assertFailure(() -> store.put(KEY, VALUE),
                        Operation.PUT, Reason.STORAGE_IO, MutationOutcome.NOT_APPENDED);
                assertThat(failure).hasRootCause(closeFailure);
                assertFailed(store, failure, KEY);
                assertThat(fileReader.isOpen()).isTrue();
                assertThat(fileWriter.isOpen()).isTrue();
                verify(retiringWriter, never()).write(any(ByteBuffer.class));
                channels.verify(() -> FileChannel.open(replacement, WRITE, CREATE_NEW), never());
                assertThat(replacement).doesNotExist();
                assertThat(Files.readAllBytes(active)).containsExactly(original);
                assertLocked();
            }
            verify(retiringWriter, never()).force(true);
            verify(retiringWriter, times(2)).close();
            assertThat(fileReader.isOpen()).isFalse();
            assertThat(fileWriter.isOpen()).isFalse();
        }
        try (KeyValueStore reopened = open()) {
            assertValue(reopened, KEY, VALUE);
        }
        assertThat(Files.readAllBytes(active)).containsExactly(original);
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
        when(reader.isOpen()).thenReturn(true);
        when(olderReader.isOpen()).thenReturn(true);

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
    void corruptHistoricalSegmentClosesAllRealChannelsOpenedBeforeIt() throws IOException {
        Path older = createSegment(1);
        Path newer = createSegment(2);
        Path corrupt = createSegment(3);
        byte[] invalid = invalidHeader();
        Files.write(corrupt, invalid);
        Path active = createSegment(4);
        List<FileChannel> opened = new ArrayList<>();
        try (MockedStatic<FileChannel> channels = mockChannels()) {
            for (Path historical : List.of(older, newer, corrupt)) {
                channels.when(() -> FileChannel.open(historical, READ)).thenAnswer(call -> {
                    FileChannel channel = historical.getFileSystem().provider().newFileChannel(historical, Set.of(READ));
                    opened.add(channel);
                    return channel;
                });
            }
            assertFailure(this::open, Operation.OPEN, Reason.STRUCTURAL_CORRUPTION, MutationOutcome.NOT_APPLICABLE);
            assertThat(opened).hasSize(3).allMatch(channel -> !channel.isOpen());
            channels.verify(() -> FileChannel.open(active, READ), never());
        } finally {
            for (FileChannel channel : opened) {
                channel.close();
            }
        }

        assertThat(Files.readAllBytes(corrupt)).containsExactly(invalid);
        Files.write(corrupt, new byte[0]);
        try (KeyValueStore reopened = open()) {
            reopened.put(KEY, VALUE);
            assertThat(reopened.get(KEY)).hasValueSatisfying(value -> assertThat(value).containsExactly(VALUE));
        }
    }

    @Test
    void startupReleasesOwnershipWhenCleanupReportsAnErrorButTheChannelIsClosed() throws IOException {
        Path active = createSegment(1);
        IOException openFailure = new IOException("failed segment write open");
        IOException closeFailure = new IOException("read close reported an error after closing");
        when(reader.isOpen()).thenReturn(false);
        doThrow(closeFailure).when(reader).close();
        try (MockedStatic<FileChannel> channels = mockChannels()) {
            channels.when(() -> FileChannel.open(active, READ)).thenReturn(reader);
            channels.when(() -> FileChannel.open(active, WRITE)).thenThrow(openFailure);
            StorageEngineException failure = assertFailure(this::open, Operation.OPEN,
                    Reason.STORAGE_IO, MutationOutcome.NOT_APPLICABLE);
            assertThat(failure).hasRootCause(openFailure);
            assertThat(openFailure.getSuppressed()).containsExactly(closeFailure);
        }
        try (KeyValueStore reopened = open()) {
            assertThat(reopened.get(KEY)).isEmpty();
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
        doThrow(readerFailure).when(reader).close();
        doThrow(writerFailure).when(writer).close();
        doThrow(olderFailure).when(olderReader).close();
        when(writer.isOpen()).thenReturn(true);
        KeyValueStore store;
        FileLock lock;
        try (MockedStatic<FileChannel> channels = mockChannels()) {
            lock = stubOwnershipLock(channels);
            channels.when(() -> FileChannel.open(older, READ)).thenReturn(olderReader);
            channels.when(() -> FileChannel.open(active, READ)).thenReturn(reader);
            channels.when(() -> FileChannel.open(active, WRITE)).thenReturn(writer);
            store = open();
        }

        doThrow(forceFailure).when(writer).force(true);
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
        try (KeyValueStore reopened = open()) {
            store.close();
            assertLocked();
            assertThat(reopened.get(KEY)).isEmpty();
        }
        verify(writer).close();
    }

    @Test
    void repeatedCloseRetriesOnlyOutstandingChannelsAndReleasesOwnershipWhenSafe() throws IOException {
        assertRepeatedClose(false);
    }

    @Test
    void repeatedCloseAfterFailedAppendRetainsOwnershipAndSkipsForce() throws IOException {
        assertRepeatedClose(true);
    }

    private void assertRepeatedClose(boolean failAppend) throws IOException {
        Path oldest = createSegment(1);
        Path newer = createSegment(2);
        Path active = createSegment(3);
        FileChannel oldestReader = mock(FileChannel.class);
        FileChannel newerReader = mock(FileChannel.class);
        IOException activeFailure = new IOException("appender remains open");
        IOException newerFailure = new IOException("historical channel reported a close error");
        IOException retryFailure = new IOException("appender still open after retry");
        doThrow(newerFailure).when(newerReader).close();
        when(newerReader.isOpen()).thenReturn(false);
        doThrow(activeFailure).doThrow(retryFailure).doNothing().when(writer).close();
        when(writer.isOpen()).thenReturn(true);
        KeyValueStore store;
        try (MockedStatic<FileChannel> channels = mockChannels()) {
            channels.when(() -> FileChannel.open(oldest, READ)).thenReturn(oldestReader);
            channels.when(() -> FileChannel.open(newer, READ)).thenReturn(newerReader);
            channels.when(() -> FileChannel.open(active, READ)).thenReturn(reader);
            channels.when(() -> FileChannel.open(active, WRITE)).thenReturn(writer);
            store = open();
        }

        if (failAppend) {
            IOException appendFailure = new IOException("append failed before cleanup started");
            when(writer.write(any(ByteBuffer.class))).thenThrow(appendFailure);
            StorageEngineException failedPut = assertFailure(() -> store.put(KEY, VALUE), Operation.PUT,
                    Reason.STORAGE_IO, MutationOutcome.UNKNOWN);
            assertThat(failedPut).hasRootCause(appendFailure);
            assertFailed(store, failedPut, KEY);
        }

        StorageEngineException first = assertFailure(store::close, Operation.CLOSE,
                Reason.STORAGE_IO, MutationOutcome.NOT_APPLICABLE);
        assertThat(first).hasRootCause(activeFailure);
        assertThat(first.getCause().getSuppressed()).singleElement().satisfies(e -> assertThat(e).hasCause(newerFailure));
        verify(oldestReader).close();
        assertLocked();
        assertThatIllegalStateException().isThrownBy(() -> store.get(KEY));

        assertThat(assertFailure(store::close, Operation.CLOSE, Reason.STORAGE_IO, MutationOutcome.NOT_APPLICABLE))
                .hasRootCause(retryFailure);
        assertLocked();
        store.close();
        try (KeyValueStore reopened = open()) {
            store.close();
            assertLocked();
            assertThat(reopened.get(KEY)).isEmpty();
        }
        verify(writer, times(3)).close();
        verify(writer, times(failAppend ? 1 : 2)).force(true);
        verify(reader).close();
        verify(newerReader).close();
        verify(oldestReader).close();
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
        KeyValueStore store;
        try (MockedStatic<FileChannel> channels = mockChannels()) {
            FileLock lock = stubOwnershipLock(channels);
            doThrow(releaseFailure).when(lock).release();
            channels.when(() -> FileChannel.open(active, READ)).thenReturn(reader);
            channels.when(() -> FileChannel.open(active, WRITE)).thenReturn(writer);
            store = open();
        }

        doThrow(forceFailure).when(writer).force(true);
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
        when(newFile.isOpen()).thenReturn(true);
        try (MockedStatic<FileChannel> channels = KeyValueStoreTestSupport.mockChannels(directory, directoryChannel)) {
            FileLock lock = stubOwnershipLock(channels);
            channels.when(() -> FileChannel.open(first, WRITE, CREATE_NEW)).thenAnswer(call -> {
                Files.createFile(first);
                return newFile;
            });

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
        try (MockedStatic<FileChannel> channels = KeyValueStoreTestSupport.mockChannels(directory, directoryChannel)) {
            createRealFileThroughMock(channels, directory.resolve("segment_0000000000000000001.log"));

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
        try (MockedStatic<FileChannel> channels = KeyValueStoreTestSupport.mockChannels(directory, directoryChannel)) {
            createRealFileThroughMock(channels, directory.resolve("segment_0000000000000000001.log"));

            assertThat(assertFailure(this::open, Operation.OPEN, Reason.STORAGE_IO, MutationOutcome.NOT_APPLICABLE))
                    .hasRootCause(closeFailure);
            assertThat(KeyValueStoreTestSupport.segmentCount(directory)).isOne();
        }
    }

    @Test
    void reopeningFailsClosedWhenTheExistingSuccessorDirectoryForceFails() throws IOException {
        Path older;
        try (KeyValueStore store = open()) {
            store.put(KEY, VALUE);
            older = directory.resolve("segment_0000000000000000001.log");
        }
        byte[] original = Files.readAllBytes(older);
        Path successor = createSegment(2);
        FileChannel directoryChannel = mock(FileChannel.class);
        IOException injected = new IOException("failed to publish the discovered successor");
        doThrow(injected).when(directoryChannel).force(true);
        try (MockedStatic<FileChannel> channels = KeyValueStoreTestSupport.mockChannels(directory, directoryChannel)) {
            channels.when(() -> FileChannel.open(older, READ)).thenAnswer(call ->
                    older.getFileSystem().provider().newFileChannel(older, Set.of(READ)));
            channels.when(() -> FileChannel.open(successor, READ)).thenReturn(reader);
            channels.when(() -> FileChannel.open(successor, WRITE)).thenReturn(writer);

            assertThat(assertFailure(this::open, Operation.OPEN, Reason.DURABILITY, MutationOutcome.NOT_APPLICABLE))
                    .hasRootCause(injected);
            var order = inOrder(writer, directoryChannel);
            order.verify(writer).force(true);
            order.verify(directoryChannel).force(true);
            order.verify(writer).close();
            verify(reader).close();
            assertThat(successor).isEmptyFile();
            assertThat(Files.readAllBytes(older)).containsExactly(original);
        }
        try (KeyValueStore reopened = open()) {
            assertValue(reopened, KEY, VALUE);
        }
    }

    private static void createRealFileThroughMock(MockedStatic<FileChannel> channels, Path path) {
        channels.when(() -> FileChannel.open(path, WRITE, CREATE_NEW))
                .thenAnswer(call -> path.getFileSystem().provider().newFileChannel(path, Set.of(WRITE, CREATE_NEW)));
    }

    private MockedStatic<FileChannel> mockChannels() {
        return KeyValueStoreTestSupport.mockChannels(directory);
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
            KeyValueStore store = open();
            clearInvocations(writer);
            return store;
        }
    }

    private KeyValueStore open() {
        return new KeyValueStore(KeyValueStoreTestSupport.config(directory, 256));
    }

    private Path createSegment(long id) throws IOException {
        return Files.createFile(directory.resolve("segment_%019d.log".formatted(id)));
    }
}
