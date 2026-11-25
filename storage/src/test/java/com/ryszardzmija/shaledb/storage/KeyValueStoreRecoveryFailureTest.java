package com.ryszardzmija.shaledb.storage;

import com.ryszardzmija.shaledb.storage.StorageEngineException.Operation;
import com.ryszardzmija.shaledb.storage.StorageEngineException.Reason;
import com.ryszardzmija.shaledb.storage.config.StorageConfig;
import com.ryszardzmija.shaledb.storage.durability.DurabilityException;
import com.ryszardzmija.shaledb.storage.durability.DurabilityMode;
import com.ryszardzmija.shaledb.storage.hash.index.ByteKey;
import com.ryszardzmija.shaledb.storage.hash.index.HashIndex;
import com.ryszardzmija.shaledb.storage.hash.segment.model.SegmentWriter;
import com.ryszardzmija.shaledb.storage.serialization.io.IncompleteRecordException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.ryszardzmija.shaledb.storage.KeyValueStoreTestSupport.assertFailure;
import static com.ryszardzmija.shaledb.storage.KeyValueStoreTestSupport.assertValue;
import static com.ryszardzmija.shaledb.storage.KeyValueStoreTestSupport.hex;
import static com.ryszardzmija.shaledb.storage.KeyValueStoreTestSupport.mockChannels;
import static com.ryszardzmija.shaledb.storage.StorageEngineException.MutationOutcome.NOT_APPLICABLE;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class KeyValueStoreRecoveryFailureTest {
    private static final byte[] KEY = {1, 2};
    private static final byte[] VALUE = {3, 4};
    private static final byte[] FIRST = hex("f930972d00020000000200000001020304");
    private static final byte[] OVERWRITE = hex("82048c0f0002000000030000000102050607");
    private static final byte[] INTERRUPTED = hex("f930972d0002000000020000000102030482048c0f00020000000300000001");
    private static final long BOUNDARY = FIRST.length;

    @TempDir
    Path directory;
    private Path active;

    @BeforeEach
    void createInterruptedAppendFixture() throws IOException {
        directory = directory.toRealPath();
        active = Files.write(segment(3), INTERRUPTED);
    }

    @Test
    void ordinaryReadFailureAfterAValidPrefixPreventsRecovery() throws IOException {
        IOException injected = new IOException("scan read failed");
        try (FileChannel readFile = FileChannel.open(active, READ);
             FileChannel writeFile = FileChannel.open(active, WRITE)) {
            FileChannel reader = mock(FileChannel.class, delegatesTo(readFile));
            FileChannel writer = mock(FileChannel.class, delegatesTo(writeFile));
            doThrow(injected).when(reader).read(any(ByteBuffer.class), eq(BOUNDARY));

            try (var channels = useActiveChannels(reader, writer, null)) {
                assertThat(failedOpen(Reason.STORAGE_IO)).hasRootCause(injected);
            }
            verify(reader).read(any(ByteBuffer.class), eq(BOUNDARY));
            verify(writer, never()).truncate(anyLong());
            verify(writer, never()).force(true);
            assertThat(readFile.isOpen()).isFalse();
            assertThat(writeFile.isOpen()).isFalse();
        }
        assertThat(Files.readAllBytes(active)).containsExactly(INTERRUPTED);
        assertReopenRecovers();
    }

    @Test
    void replayFailurePreventsRecoveryAndClosesAllAcquiredSegmentChannels() throws IOException {
        var replayFailure = new IllegalStateException("index replay failed");
        var cleanupFailure = new IOException("reader close reported an error");
        Path historical = Files.createFile(segment(2));
        try (FileChannel historicalReader = FileChannel.open(historical, READ);
             FileChannel readFile = FileChannel.open(active, READ);
             FileChannel writeFile = FileChannel.open(active, WRITE)) {
            FileChannel reader = mock(FileChannel.class, delegatesTo(readFile));
            FileChannel writer = mock(FileChannel.class, delegatesTo(writeFile));
            doAnswer(call -> {
                readFile.close();
                throw cleanupFailure;
            }).when(reader).close();

            try (var channels = useActiveChannels(reader, writer, null);
                 var indexes = mockConstruction(HashIndex.class, (index, context) -> {
                     if (context.getCount() == 2) {
                         doThrow(replayFailure).when(index).markPresent(new ByteKey(KEY), 0);
                     }
                 })) {
                channels.when(() -> FileChannel.open(historical, READ)).thenReturn(historicalReader);
                assertThatThrownBy(this::open).isSameAs(replayFailure).hasSuppressedException(cleanupFailure);
                assertThat(indexes.constructed()).hasSize(2);
            }

            verify(reader, never()).read(any(ByteBuffer.class), eq(BOUNDARY));
            verify(writer, never()).truncate(anyLong());
            verify(writer, never()).force(true);
            assertThat(historicalReader.isOpen()).isFalse();
            assertThat(readFile.isOpen()).isFalse();
            assertThat(writeFile.isOpen()).isFalse();
        }
        assertThat(Files.readAllBytes(active)).containsExactly(INTERRUPTED);
        assertReopenRecovers();
    }

    @Test
    void unexpectedTransferEofIsIoFailureEvenWhenARecoverableTailIsPresent() throws IOException {
        for (long position : new long[] {0, 13}) {
            try (FileChannel readFile = FileChannel.open(active, READ);
                 FileChannel writeFile = FileChannel.open(active, WRITE)) {
                FileChannel reader = mock(FileChannel.class, delegatesTo(readFile));
                FileChannel writer = mock(FileChannel.class, delegatesTo(writeFile));
                doReturn(-1).when(reader).read(any(ByteBuffer.class), eq(position));

                try (var channels = useActiveChannels(reader, writer, null)) {
                    StorageEngineException failure = failedOpen(Reason.STORAGE_IO);
                    assertThat(failure.getCause().getCause().getCause().getCause())
                            .isInstanceOfSatisfying(IncompleteRecordException.class, cause -> {
                                assertThat(cause.origin()).isEqualTo(IncompleteRecordException.Origin.TRANSFER_EOF);
                                assertThat(cause.section()).isEqualTo(position == 0
                                        ? IncompleteRecordException.Section.HEADER : IncompleteRecordException.Section.PAYLOAD);
                            });
                }
                verify(reader).read(any(ByteBuffer.class), eq(position));
                verify(writer, never()).truncate(anyLong());
                verify(writer, never()).force(true);
            }
            assertThat(Files.readAllBytes(active)).containsExactly(INTERRUPTED);
        }
        assertReopenRecovers();
    }

    @Test
    void scanExtentChangePreventsTruncation() throws IOException {
        try (FileChannel readFile = FileChannel.open(active, READ);
             FileChannel writeFile = FileChannel.open(active, WRITE)) {
            FileChannel reader = mock(FileChannel.class, delegatesTo(readFile));
            FileChannel writer = mock(FileChannel.class, delegatesTo(writeFile));
            doReturn((long) INTERRUPTED.length).doReturn(BOUNDARY).when(reader).size();
            try (var channels = useActiveChannels(reader, writer, null)) {
                assertThat(failedOpen(Reason.STORAGE_IO)).hasRootCauseInstanceOf(IOException.class);
            }
            verify(reader).read(any(ByteBuffer.class), eq(BOUNDARY));
            verify(writer, never()).truncate(anyLong());
        }
        assertThat(Files.readAllBytes(active)).containsExactly(INTERRUPTED);
        assertReopenRecovers();
    }

    @Test
    void truncationFailureBeforeFileChangeLeavesTheOriginalTailForReopen() throws IOException {
        assertTruncationFailure(false);
    }

    @Test
    void truncationFailureAfterFileChangeLeavesTheRetainedPrefixForReopen() throws IOException {
        assertTruncationFailure(true);
    }

    private void assertTruncationFailure(boolean modifiedFile) throws IOException {
        IOException injected = new IOException("truncate failed");
        try (FileChannel readFile = FileChannel.open(active, READ);
             FileChannel writeFile = FileChannel.open(active, WRITE)) {
            FileChannel writer = mock(FileChannel.class, delegatesTo(writeFile));
            doAnswer(call -> {
                if (modifiedFile) {
                    writeFile.truncate(call.getArgument(0, Long.class));
                }
                throw injected;
            }).when(writer).truncate(BOUNDARY);
            try (var channels = useActiveChannels(readFile, writer, null)) {
                assertThat(failedOpen(Reason.STORAGE_IO)).hasRootCause(injected);
            }
            verify(writer).truncate(BOUNDARY);
            verify(writer, never()).force(true);
            assertThat(writeFile.isOpen()).isFalse();
        }
        assertThat(Files.readAllBytes(active)).containsExactly(modifiedFile ? FIRST : INTERRUPTED);
        assertReopenRecovers();
    }

    @Test
    void boundaryVerificationFailureAfterTruncationPreventsOpen() throws IOException {
        IOException injected = new IOException("failed to get recovered writer extent");
        try (FileChannel readFile = FileChannel.open(active, READ);
             FileChannel writeFile = FileChannel.open(active, WRITE)) {
            FileChannel writer = mock(FileChannel.class, delegatesTo(writeFile));
            doReturn((long) INTERRUPTED.length).doThrow(injected).when(writer).size();
            try (var channels = useActiveChannels(readFile, writer, null)) {
                assertThat(failedOpen(Reason.STORAGE_IO)).hasRootCause(injected);
            }
            var ordered = inOrder(writer);
            ordered.verify(writer).size();
            ordered.verify(writer).truncate(BOUNDARY);
            ordered.verify(writer).size();
            verify(writer, never()).force(true);
        }
        assertThat(Files.readAllBytes(active)).containsExactly(FIRST);
        assertReopenRecovers();
    }

    @Test
    void rejectsTruncationThatReportsSuccessWithoutChangingTheLength() throws IOException {
        try (FileChannel readFile = FileChannel.open(active, READ);
             FileChannel writeFile = FileChannel.open(active, WRITE)) {
            FileChannel writer = mock(FileChannel.class, delegatesTo(writeFile));
            doReturn(writer).when(writer).truncate(BOUNDARY);
            try (var channels = useActiveChannels(readFile, writer, null)) {
                assertThat(failedOpen(Reason.STORAGE_IO)).hasRootCauseInstanceOf(IOException.class);
            }
            verify(writer).truncate(BOUNDARY);
            verify(writer, never()).force(true);
        }
        assertThat(Files.readAllBytes(active)).containsExactly(INTERRUPTED);
        assertReopenRecovers();
    }

    @Test
    void failedFileForcePreservesCleanupFailuresAndReopenForcesTheAlreadyTruncatedFile() throws IOException {
        IOException forceFailure = new IOException("active force failed");
        IOException cleanupFailure = new IOException("active close reported failure after closing");
        List<Path> history = List.of(Files.createFile(segment(1)), Files.createFile(segment(2)));
        List<FileChannel> historicalReaders = new ArrayList<>();
        try (FileChannel readFile = FileChannel.open(active, READ);
             FileChannel writeFile = FileChannel.open(active, WRITE)) {
            FileChannel writer = mock(FileChannel.class, delegatesTo(writeFile));
            FileChannel directoryForSync = mock(FileChannel.class);
            doThrow(forceFailure).when(writer).force(true);
            doAnswer(call -> {
                writeFile.close();
                throw cleanupFailure;
            }).when(writer).close();
            try (var channels = useActiveChannels(readFile, writer, directoryForSync)) {
                for (Path path : history) {
                    channels.when(() -> FileChannel.open(path, READ)).thenAnswer(call -> {
                        FileChannel channel = path.getFileSystem().provider().newFileChannel(path, Set.of(READ));
                        historicalReaders.add(channel);
                        return channel;
                    });
                }
                StorageEngineException failure = failedOpen(Reason.DURABILITY);
                assertThat(failure).hasRootCause(forceFailure);
                assertThat(failure.getCause().getCause().getSuppressed()).singleElement()
                        .satisfies(cleanup -> assertThat(cleanup).hasCause(cleanupFailure));
                assertThat(historicalReaders).hasSize(2).allMatch(channel -> !channel.isOpen());
            } finally {
                for (FileChannel channel : historicalReaders) {
                    channel.close();
                }
            }
            var ordered = inOrder(writer);
            ordered.verify(writer).truncate(BOUNDARY);
            ordered.verify(writer).force(true);
            ordered.verify(writer).close();
            verify(directoryForSync, never()).force(true);
            assertThat(readFile.isOpen()).isFalse();
            assertThat(writeFile.isOpen()).isFalse();
        }
        assertThat(Files.readAllBytes(active)).containsExactly(FIRST);

        try (FileChannel readFile = FileChannel.open(active, READ);
             FileChannel writeFile = FileChannel.open(active, WRITE);
             FileChannel dirFile = FileChannel.open(directory, READ)) {
            FileChannel writer = mock(FileChannel.class, delegatesTo(writeFile));
            FileChannel directoryForSync = mock(FileChannel.class, delegatesTo(dirFile));
            try (var channels = useActiveChannels(readFile, writer, directoryForSync)) {
                for (Path path : history) {
                    channels.when(() -> FileChannel.open(path, READ)).thenAnswer(call ->
                            path.getFileSystem().provider().newFileChannel(path, Set.of(READ)));
                }
                try (KeyValueStore store = open()) {
                    var ordered = inOrder(writer, directoryForSync);
                    ordered.verify(writer).force(true);
                    ordered.verify(directoryForSync).force(true);
                    verify(writer, never()).truncate(anyLong());
                    assertValue(store, KEY, VALUE);
                }
            }
        }
        for (Path path : history) {
            assertThat(path).isEmptyFile();
        }
    }

    @Test
    void failureAfterTheFileBarrierPreventsOpenAndLeavesTheRetainedPrefix() throws IOException {
        IOException failure = new IOException("directory force failed");
        try (FileChannel readFile = FileChannel.open(active, READ);
             FileChannel writeFile = FileChannel.open(active, WRITE)) {
            FileChannel writer = mock(FileChannel.class, delegatesTo(writeFile));
            FileChannel directoryForSync = mock(FileChannel.class);
            doThrow(failure).when(directoryForSync).force(true);
            try (var channels = useActiveChannels(readFile, writer, directoryForSync)) {
                StorageEngineException openFailure = failedOpen(Reason.DURABILITY);
                assertThat(openFailure.getCause().getCause()).isInstanceOfSatisfying(DurabilityException.class,
                        cause -> assertThat(cause).hasCause(failure));
            }
            var ordered = inOrder(writer, directoryForSync);
            ordered.verify(writer).truncate(BOUNDARY);
            ordered.verify(writer).force(true);
            ordered.verify(directoryForSync).force(true);
            verify(directoryForSync).close();
            assertThat(writeFile.isOpen()).isFalse();
        }
        assertThat(Files.readAllBytes(active)).containsExactly(FIRST);
        assertReopenRecovers();
    }

    @Test
    void failureConstructingTheSegmentWriterAfterTruncationCleansUpBothChannels() throws IOException {
        var injected = new IllegalStateException("segment writer construction failed");
        try (FileChannel readFile = FileChannel.open(active, READ);
             FileChannel writeFile = FileChannel.open(active, WRITE)) {
            FileChannel writer = mock(FileChannel.class, delegatesTo(writeFile));
            try (var channels = useActiveChannels(readFile, writer, null);
                 var construction = mockConstruction(SegmentWriter.class, (mock, context) -> { throw injected; })) {
                assertThatThrownBy(this::open).hasRootCause(injected);
            }
            verify(writer).truncate(BOUNDARY);
            verify(writer, never()).force(true);
            assertThat(readFile.isOpen()).isFalse();
            assertThat(writeFile.isOpen()).isFalse();
        }
        assertThat(Files.readAllBytes(active)).containsExactly(FIRST);
        assertReopenRecovers();
    }

    @Test
    void cleanupFailureAfterBothBarriersStillPreventsOpen() throws IOException {
        IOException injected = new IOException("directory close failed after closing");
        try (FileChannel readFile = FileChannel.open(active, READ);
             FileChannel writeFile = FileChannel.open(active, WRITE);
             FileChannel dirFile = FileChannel.open(directory, READ)) {
            FileChannel writer = mock(FileChannel.class, delegatesTo(writeFile));
            FileChannel directoryForSync = mock(FileChannel.class, delegatesTo(dirFile));
            doAnswer(call -> {
                dirFile.close();
                throw injected;
            }).when(directoryForSync).close();
            try (var channels = useActiveChannels(readFile, writer, directoryForSync)) {
                assertThat(failedOpen(Reason.STORAGE_IO)).hasRootCause(injected);
            }
            var ordered = inOrder(writer, directoryForSync);
            ordered.verify(writer).truncate(BOUNDARY);
            ordered.verify(writer).force(true);
            ordered.verify(directoryForSync).force(true);
            ordered.verify(directoryForSync).close();
            assertThat(writeFile.isOpen()).isFalse();
        }
        assertThat(Files.readAllBytes(active)).containsExactly(FIRST);
        assertReopenRecovers();
    }

    @Test
    void completeUnacknowledgedHistoryIsForcedBeforeTheWriterCanBeRetired() throws IOException {
        Files.write(active, OVERWRITE);
        StorageConfig config = new StorageConfig(OVERWRITE.length, 5, directory, DurabilityMode.SYNC_EACH_WRITE);
        try (FileChannel readFile = FileChannel.open(active, READ);
             FileChannel writeFile = FileChannel.open(active, WRITE);
             FileChannel dirFile = FileChannel.open(directory, READ)) {
            FileChannel writer = mock(FileChannel.class, delegatesTo(writeFile));
            FileChannel directoryForSync = mock(FileChannel.class, delegatesTo(dirFile));
            KeyValueStore reopened;
            try (var channels = useActiveChannels(readFile, writer, directoryForSync)) {
                reopened = new KeyValueStore(config);
            }
            try (reopened) {
                assertValue(reopened, KEY, new byte[] {5, 6, 7});
                var ordered = inOrder(writer, directoryForSync);
                ordered.verify(writer).force(true);
                ordered.verify(directoryForSync).force(true);
                verify(writer, never()).truncate(anyLong());
                reopened.put(KEY, VALUE);
                ordered.verify(writer).close();
                assertThat(Files.readAllBytes(active)).containsExactly(OVERWRITE);
                assertThat(Files.readAllBytes(segment(4))).containsExactly(FIRST);
            }
        }
    }

    @Test
    void holdsOwnershipThroughTruncationAndFileForceBeforeItsFirstMutation() throws IOException {
        try (FileChannel readFile = FileChannel.open(active, READ);
             FileChannel writeFile = FileChannel.open(active, WRITE);
             FileChannel dirFile = FileChannel.open(directory, READ)) {
            FileChannel writer = mock(FileChannel.class, delegatesTo(writeFile));
            FileChannel directoryForSync = mock(FileChannel.class, delegatesTo(dirFile));
            doAnswer(call -> {
                failedOpen(Reason.DATABASE_LOCKED);
                return writeFile.truncate(call.getArgument(0, Long.class));
            }).when(writer).truncate(BOUNDARY);
            doAnswer(call -> {
                failedOpen(Reason.DATABASE_LOCKED);
                writeFile.force(true);
                return null;
            }).when(writer).force(true);
            try (var channels = useActiveChannels(readFile, writer, directoryForSync);
                 KeyValueStore store = open()) {
                var ordered = inOrder(writer, directoryForSync);
                ordered.verify(writer).truncate(BOUNDARY);
                ordered.verify(writer).force(true);
                ordered.verify(directoryForSync).force(true);
                assertValue(store, KEY, VALUE);
                store.put(KEY, new byte[] {5, 6, 7});
                ordered.verify(writer).position(BOUNDARY);
                ordered.verify(writer).write(any(ByteBuffer.class));
                ordered.verify(writer).force(true);
            }
        }
        assertThat(Files.readAllBytes(active)).containsExactly(
                ByteBuffer.allocate(FIRST.length + OVERWRITE.length).put(FIRST).put(OVERWRITE).array());
    }

    private MockedStatic<FileChannel> useActiveChannels(FileChannel reader, FileChannel writer, FileChannel dirChannel) {
        var channels = mockChannels(directory, dirChannel);
        channels.when(() -> FileChannel.open(active, READ)).thenReturn(reader);
        channels.when(() -> FileChannel.open(active, WRITE)).thenReturn(writer);
        return channels;
    }

    private void assertReopenRecovers() throws IOException {
        try (KeyValueStore reopened = open()) {
            assertValue(reopened, KEY, VALUE);
            assertThat(Files.readAllBytes(active)).containsExactly(FIRST);
        }
    }

    private KeyValueStore open() {
        return new KeyValueStore(KeyValueStoreTestSupport.config(directory, 256));
    }

    private StorageEngineException failedOpen(Reason reason) {
        return assertFailure(this::open, Operation.OPEN, reason, NOT_APPLICABLE);
    }

    private Path segment(long id) {
        return directory.resolve("segment_%019d.log".formatted(id));
    }
}
