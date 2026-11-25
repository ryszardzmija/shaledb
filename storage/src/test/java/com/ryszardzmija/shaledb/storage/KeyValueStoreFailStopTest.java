package com.ryszardzmija.shaledb.storage;

import com.ryszardzmija.shaledb.storage.StorageEngineException.MutationOutcome;
import com.ryszardzmija.shaledb.storage.StorageEngineException.Operation;
import com.ryszardzmija.shaledb.storage.StorageEngineException.Reason;
import com.ryszardzmija.shaledb.storage.config.StorageConfig;
import com.ryszardzmija.shaledb.storage.durability.DurabilityException;
import com.ryszardzmija.shaledb.storage.durability.DurabilityMode;
import com.ryszardzmija.shaledb.storage.durability.FileSystemSync;
import com.ryszardzmija.shaledb.storage.hash.HashIndexEngine;
import com.ryszardzmija.shaledb.storage.hash.index.ByteKey;
import com.ryszardzmija.shaledb.storage.hash.index.KeyDirectory;
import com.ryszardzmija.shaledb.storage.hash.index.RecordLocation;
import com.ryszardzmija.shaledb.storage.hash.segment.model.ImmutableSegment;
import com.ryszardzmija.shaledb.storage.hash.segment.model.MutableSegment;
import com.ryszardzmija.shaledb.storage.hash.segment.rollover.RolloverResult;
import com.ryszardzmija.shaledb.storage.serialization.io.RecordIOException;
import com.ryszardzmija.shaledb.storage.serialization.io.WriteResult;
import com.ryszardzmija.shaledb.storage.serialization.spec.FormatInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static com.ryszardzmija.shaledb.storage.KeyValueStoreTestSupport.assertFailed;
import static com.ryszardzmija.shaledb.storage.KeyValueStoreTestSupport.assertFailure;
import static com.ryszardzmija.shaledb.storage.KeyValueStoreTestSupport.assertValue;
import static com.ryszardzmija.shaledb.storage.KeyValueStoreTestSupport.hex;
import static com.ryszardzmija.shaledb.storage.KeyValueStoreTestSupport.mockChannels;
import static com.ryszardzmija.shaledb.storage.KeyValueStoreTestSupport.segmentCount;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class KeyValueStoreFailStopTest {
    private static final byte[] KEY = {1, 2};
    private static final byte[] VALUE = {3, 4};
    private static final byte[] REPLACEMENT = {5, 6, 7};
    private static final byte[] MISSING = {8};

    private static final byte[] FIRST_RECORD = hex("f930972d00020000000200000001020304");
    private static final byte[] OVERWRITE = hex("82048c0f0002000000030000000102050607");
    private static final byte[] TOMBSTONE = hex("0228795c0102000000000000000102");

    @TempDir
    Path directory;
    private Path active;

    @BeforeEach
    void createActiveSegment() throws IOException {
        directory = directory.toRealPath();
        active = Files.createFile(segment(1));
    }

    @Test
    void immediateAppendFailureIsTerminalAndRetainsOwnership() throws IOException {
        assertAppendFailure(Operation.PUT, OVERWRITE, 0);
        try (KeyValueStore reopened = open()) {
            assertValue(reopened, KEY, VALUE);
        }
    }

    @Test
    void immediateTombstoneAppendFailureKeepsThePreviousValueOnReopen() throws IOException {
        assertAppendFailure(Operation.DELETE, TOMBSTONE, 0);
        try (KeyValueStore reopened = open()) {
            assertValue(reopened, KEY, VALUE);
        }
    }

    @Test
    void partialHeaderAppendLeavesAnExactUnmodifiedSuffixAfterClose() throws IOException {
        assertAppendFailure(Operation.PUT, OVERWRITE, 3);
        assertActiveRecoveryKeepsOriginalValue();
    }

    @Test
    void partialPayloadAppendLeavesAnExactUnmodifiedSuffixAfterClose() throws IOException {
        assertAppendFailure(Operation.PUT, OVERWRITE, FormatInfo.getHeaderSize() + 1);
        assertActiveRecoveryKeepsOriginalValue();
    }

    @Test
    void partialTombstoneAppendLeavesAnExactUnmodifiedSuffixAfterClose() throws IOException {
        assertAppendFailure(Operation.DELETE, TOMBSTONE, FormatInfo.getHeaderSize() + 1);
        assertActiveRecoveryKeepsOriginalValue();
    }

    private void assertAppendFailure(Operation operation, byte[] record, int prefixLength) throws IOException {
        IOException writeFailure = new IOException("append failed");
        byte[] expected = historyWith(record, prefixLength);
        KeyDirectory actualDirectory = new KeyDirectory();
        try (FileChannel channel = FileChannel.open(active, WRITE);
             var directories = mockConstruction(KeyDirectory.class, withSettings().defaultAnswer(delegatesTo(actualDirectory)))) {
            FileChannel writer = mock(FileChannel.class, delegatesTo(channel));
            try (KeyValueStore store = openWithWriter(writer)) {
                store.put(KEY, VALUE);
                assertValue(store, KEY, VALUE);
                KeyDirectory directoryMock = directories.constructed().getFirst();
                clearInvocations(writer, directoryMock);
                if (prefixLength == 0) {
                    doThrow(writeFailure).when(writer).write(any(ByteBuffer.class));
                } else {
                    doAnswer(call -> {
                        ByteBuffer buffer = call.getArgument(0);
                        int limit = buffer.limit();
                        buffer.limit(buffer.position() + prefixLength);
                        try {
                            while (buffer.hasRemaining()) {
                                assertThat(channel.write(buffer)).isPositive();
                            }
                            return prefixLength;
                        } finally {
                            buffer.limit(limit);
                        }
                    }).doThrow(writeFailure).when(writer).write(any(ByteBuffer.class));
                }

                StorageEngineException failure = assertFailure(mutation(store, operation), operation, Reason.STORAGE_IO,
                        MutationOutcome.UNKNOWN);
                assertThat(failure).hasRootCause(writeFailure);
                verify(writer, times(prefixLength == 0 ? 1 : 2)).write(any(ByteBuffer.class));
                verify(writer, never()).force(true);
                verifyNoInteractions(directoryMock);
                assertThat(actualDirectory.lookup(new ByteKey(KEY))).contains(new RecordLocation(1, 0));
                assertThat(Files.readAllBytes(active)).containsExactly(expected);
                assertRejectionsPreserveHistory(store, failure, expected, writer, directoryMock);
                assertLocked();
            }
            verify(writer, never()).force(true);
        }
        assertThat(Files.readAllBytes(active)).containsExactly(expected);
        // Check physical ownership release independently of segment replay.
        try (FileChannel lockChannel = FileChannel.open(directory.resolve("LOCK"), WRITE);
             var lock = lockChannel.tryLock()) {
            assertThat(lock).isNotNull();
        }
    }

    @Test
    void zeroProgressAppendStopsAfterTheBoundedNumberOfAttempts() throws IOException {
        try (FileChannel channel = FileChannel.open(active, WRITE)) {
            FileChannel writer = mock(FileChannel.class, delegatesTo(channel));
            try (KeyValueStore store = openWithWriter(writer)) {
                store.put(KEY, VALUE);
                clearInvocations(writer);
                doReturn(0).when(writer).write(any(ByteBuffer.class));

                StorageEngineException failure = assertFailure(() -> store.put(KEY, REPLACEMENT), Operation.PUT,
                        Reason.STORAGE_IO, MutationOutcome.UNKNOWN);
                assertThat(failure).hasRootCauseInstanceOf(RecordIOException.NoProgress.class);
                verify(writer, times(3)).write(any(ByteBuffer.class));
                verify(writer, never()).force(true);
                assertRejectionsPreserveHistory(store, failure, FIRST_RECORD, writer);
            }
            verify(writer, never()).force(true);
        }
    }

    @Test
    void failedOverwriteForceLeavesACompleteRecordAndSkipsPublication() throws IOException {
        assertRecordForceFailure(Operation.PUT, OVERWRITE);
    }

    @Test
    void failedTombstoneForceLeavesACompleteRecordAndSkipsPublication() throws IOException {
        assertRecordForceFailure(Operation.DELETE, TOMBSTONE);
    }

    @Test
    void successfulOverwriteReturnsAfterCompleteAppendForceAndPublication() throws IOException {
        KeyDirectory actualDirectory = new KeyDirectory();
        byte[] expected = historyWith(OVERWRITE, OVERWRITE.length);
        try (FileChannel channel = FileChannel.open(active, WRITE);
             var directories = mockConstruction(KeyDirectory.class, withSettings().defaultAnswer(delegatesTo(actualDirectory)))) {
            FileChannel writer = mock(FileChannel.class, delegatesTo(channel));
            try (KeyValueStore store = openWithWriter(writer)) {
                store.put(KEY, VALUE);
                KeyDirectory directoryMock = directories.constructed().getFirst();
                clearInvocations(writer, directoryMock);
                doAnswer(call -> {
                    assertThat(Files.readAllBytes(active)).containsExactly(expected);
                    channel.force(true);
                    return null;
                }).when(writer).force(true);

                store.put(KEY, REPLACEMENT);

                var order = inOrder(writer, directoryMock);
                order.verify(writer).write(any(ByteBuffer.class));
                order.verify(writer).force(true);
                order.verify(directoryMock).markPresent(new ByteKey(KEY), new RecordLocation(1, FIRST_RECORD.length));
                assertValue(store, KEY, REPLACEMENT);
            }
        }
    }

    private void assertRecordForceFailure(Operation operation, byte[] record) throws IOException {
        IOException syncFailure = new IOException("file force reported failure");
        byte[] expected = historyWith(record, record.length);
        KeyDirectory actualDirectory = new KeyDirectory();
        try (FileChannel channel = FileChannel.open(active, WRITE);
             var directories = mockConstruction(KeyDirectory.class, withSettings().defaultAnswer(delegatesTo(actualDirectory)))) {
            FileChannel writer = mock(FileChannel.class, delegatesTo(channel));
            try (KeyValueStore store = openWithWriter(writer)) {
                store.put(KEY, VALUE);
                KeyDirectory directoryMock = directories.constructed().getFirst();
                clearInvocations(writer, directoryMock);
                doAnswer(call -> {
                    assertThat(Files.readAllBytes(active)).containsExactly(expected);
                    throw syncFailure;
                }).when(writer).force(true);

                StorageEngineException failure = assertFailure(mutation(store, operation), operation, Reason.DURABILITY,
                        MutationOutcome.UNKNOWN);
                assertThat(failure).hasRootCause(syncFailure);
                verify(writer).force(true);
                verifyNoInteractions(directoryMock);
                assertThat(actualDirectory.lookup(new ByteKey(KEY))).contains(new RecordLocation(1, 0));
                assertRejectionsPreserveHistory(store, failure, expected, writer, directoryMock);
            }
            verify(writer, never()).force(true);
        }
        assertThat(Files.readAllBytes(active)).containsExactly(expected);
    }

    @Test
    void forceCompletesBeforeAnOverwriteFailsOnItsWayToDirectoryPublication() throws IOException {
        var failure = new IllegalArgumentException("unexpected failure obtaining the written offset");
        byte[] expected = historyWith(OVERWRITE, OVERWRITE.length);
        KeyDirectory actualDirectory = new KeyDirectory();
        try (FileChannel channel = FileChannel.open(active, WRITE);
             var directories = mockConstruction(KeyDirectory.class, withSettings().defaultAnswer(delegatesTo(actualDirectory)))) {
            FileChannel writer = mock(FileChannel.class, delegatesTo(channel));
            try (KeyValueStore store = openWithWriter(writer)) {
                store.put(KEY, VALUE);
                KeyDirectory directoryMock = directories.constructed().getFirst();
                clearInvocations(writer, directoryMock);
                try (var results = mockConstruction(WriteResult.class, (result, context) ->
                        when(result.writeOffset()).thenThrow(failure))) {
                    assertThatThrownBy(() -> store.put(KEY, REPLACEMENT)).isSameAs(failure);
                    var order = inOrder(writer, results.constructed().getFirst());
                    order.verify(writer).write(any(ByteBuffer.class));
                    order.verify(writer).force(true);
                    order.verify(results.constructed().getFirst()).writeOffset();
                }
                verifyNoInteractions(directoryMock);
                assertThat(actualDirectory.lookup(new ByteKey(KEY))).contains(new RecordLocation(1, 0));
                assertRejectionsPreserveHistory(store, failure, expected, writer, directoryMock);
                assertLocked();
            }
            verify(writer, never()).force(true);
        }
        assertThat(Files.readAllBytes(active)).containsExactly(expected);
        try (KeyValueStore reopened = open()) {
            assertValue(reopened, KEY, REPLACEMENT);
        }
    }

    @Test
    void publicationFailureBeforeOverwriteIsTerminalAndReplaysOnReopen() throws IOException {
        assertDirectoryPublicationFailure(Operation.PUT, false);
    }

    @Test
    void publicationFailureAfterOverwriteIsTerminalAndReplaysOnReopen() throws IOException {
        assertDirectoryPublicationFailure(Operation.PUT, true);
    }

    @Test
    void publicationFailureBeforeDeleteIsTerminalAndReplaysOnReopen() throws IOException {
        assertDirectoryPublicationFailure(Operation.DELETE, false);
    }

    @Test
    void publicationFailureAfterDeleteIsTerminalAndReplaysOnReopen() throws IOException {
        assertDirectoryPublicationFailure(Operation.DELETE, true);
    }

    private void assertDirectoryPublicationFailure(Operation operation, boolean afterUpdate) throws IOException {
        var failure = new IllegalStateException("directory publication failed");
        var key = new ByteKey(KEY);
        boolean overwrite = operation == Operation.PUT;
        var oldLocation = new RecordLocation(1, 0);
        var newLocation = new RecordLocation(1, FIRST_RECORD.length);
        byte[] record = overwrite ? OVERWRITE : TOMBSTONE;
        byte[] expected = historyWith(record, record.length);
        KeyDirectory actualDirectory = new KeyDirectory();
        try (FileChannel channel = FileChannel.open(active, WRITE);
             var directories = mockConstruction(KeyDirectory.class,
                     withSettings().defaultAnswer(delegatesTo(actualDirectory)))) {
            FileChannel writer = mock(FileChannel.class, delegatesTo(channel));
            try (KeyValueStore store = openWithWriter(writer)) {
                store.put(KEY, VALUE);
                KeyDirectory directoryMock = directories.constructed().getFirst();
                clearInvocations(writer, directoryMock);
                var interrupted = doAnswer(call -> {
                    assertThat(Files.readAllBytes(active)).containsExactly(expected);
                    if (afterUpdate && overwrite) {
                        actualDirectory.markPresent(key, newLocation);
                    } else if (afterUpdate) {
                        actualDirectory.remove(key);
                    }
                    throw failure;
                }).when(directoryMock);
                if (overwrite) {
                    interrupted.markPresent(key, newLocation);
                } else {
                    interrupted.remove(key);
                }

                assertThatThrownBy(mutation(store, operation)::run).isSameAs(failure);
                var order = inOrder(writer, directoryMock);
                order.verify(writer).write(any(ByteBuffer.class));
                order.verify(writer).force(true);
                if (overwrite) {
                    order.verify(directoryMock).markPresent(key, newLocation);
                } else {
                    order.verify(directoryMock).remove(key);
                }
                if (!afterUpdate) {
                    assertThat(actualDirectory.lookup(key)).contains(oldLocation);
                } else if (overwrite) {
                    assertThat(actualDirectory.lookup(key)).contains(newLocation);
                } else {
                    assertThat(actualDirectory.lookup(key)).isEmpty();
                }
                assertRejectionsPreserveHistory(store, failure, expected, writer, directoryMock);
                assertLocked();
            }
            verify(writer, never()).force(true);
        }
        assertThat(Files.readAllBytes(active)).containsExactly(expected);
        try (KeyValueStore reopened = open()) {
            if (overwrite) {
                assertValue(reopened, KEY, REPLACEMENT);
            } else {
                assertThat(reopened.get(KEY)).isEmpty();
            }
        }
    }

    @Test
    void successorFileForceFailureIsTerminalBeforeTheIncomingAppend() throws IOException {
        assertSuccessorForceFailure(false);
    }

    @Test
    void successorDirectoryForceFailureIsTerminalBeforeTheIncomingAppend() throws IOException {
        assertSuccessorForceFailure(true);
    }

    private void assertSuccessorForceFailure(boolean directoryForce) throws IOException {
        Path triggeringSegment = Files.move(active, segment(2));
        Files.write(segment(1), FIRST_RECORD);
        StorageConfig config = new StorageConfig(32, 19, directory, DurabilityMode.SYNC_EACH_WRITE);
        IOException cause = new IOException("successor force reported failure");
        var injected = new DurabilityException("successor publication failed", cause);
        try (var syncs = mockConstruction(FileSystemSync.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
             KeyValueStore store = new KeyValueStore(config)) {
            store.put(KEY, VALUE);
            FileSystemSync segmentPublication = syncs.constructed().getFirst();
            if (directoryForce) {
                doThrow(injected).doCallRealMethod().when(segmentPublication).forceDirectory(directory);
            } else {
                doThrow(injected).when(segmentPublication).forceFile(any(FileChannel.class));
            }

            StorageEngineException failure = assertFailure(() -> store.put(KEY, REPLACEMENT), Operation.PUT,
                    Reason.DURABILITY, MutationOutcome.NOT_APPENDED);
            assertThat(failure).hasRootCause(cause);
            assertFailed(store, failure, KEY);
            assertThat(segment(3)).doesNotExist();
            assertThat(Files.readAllBytes(triggeringSegment)).containsExactly(FIRST_RECORD);
            assertThat(Files.readAllBytes(segment(1))).containsExactly(FIRST_RECORD);
            assertLocked();
        }
        try (KeyValueStore reopened = new KeyValueStore(config)) {
            assertValue(reopened, KEY, VALUE);
        }
    }

    @Test
    void rolloverRegistryFailureBeforeInsertionClosesTheUnregisteredReader() throws Exception {
        assertRolloverRegistryFailure(false);
    }

    @Test
    void rolloverRegistryFailureAfterInsertionRetainsTheReaderForPendingCleanup() throws Exception {
        assertRolloverRegistryFailure(true);
    }

    private void assertRolloverRegistryFailure(boolean afterInsertion) throws Exception {
        var failure = new AssertionError("historical registry publication failed");
        var closeFailure = new IOException("retired reader remains open");
        Path retiring = Files.write(segment(2), FIRST_RECORD);
        Files.write(active, FIRST_RECORD);
        var config = new StorageConfig(17, 4, directory, DurabilityMode.SYNC_EACH_WRITE);
        var key = new ByteKey(KEY);
        try (FileChannel retiringFile = FileChannel.open(retiring, READ)) {
            FileChannel retiringReader = mock(FileChannel.class, delegatesTo(retiringFile));
            if (afterInsertion) {
                doThrow(closeFailure).doAnswer(call -> {
                    retiringFile.close();
                    return null;
                }).when(retiringReader).close();
            }
            HashIndexEngine engine;
            try (var channels = mockChannels(directory)) {
                channels.when(() -> FileChannel.open(active, READ)).thenAnswer(call ->
                        active.getFileSystem().provider().newFileChannel(active, Set.of(READ)));
                channels.when(() -> FileChannel.open(retiring, READ)).thenReturn(retiringReader);
                channels.when(() -> FileChannel.open(retiring, WRITE)).thenAnswer(call ->
                        retiring.getFileSystem().provider().newFileChannel(retiring, Set.of(WRITE)));
                engine = new HashIndexEngine(config);
            }
            try (var handoffs = mockConstruction(RolloverResult.class, (result, context) -> {
                when(result.newMutableSegment()).thenReturn((MutableSegment) context.arguments().get(0));
                when(result.newImmutableSegment()).thenReturn((ImmutableSegment) context.arguments().get(1));
            })) {
                var history = new HashMap<Long, ImmutableSegment>() {
                    @Override
                    public ImmutableSegment putIfAbsent(Long id, ImmutableSegment segment) {
                        if (afterInsertion) {
                            super.putIfAbsent(id, segment);
                        }
                        throw failure;
                    }
                };
                var registry = HashIndexEngine.class.getDeclaredField("immutableSegments");
                registry.setAccessible(true);
                var retained = (ImmutableSegment) ((Map<?, ?>) registry.get(engine)).get(1L);
                history.put(1L, retained);
                registry.set(engine, history);

                assertThatThrownBy(() -> engine.delete(KEY)).isSameAs(failure);
                var handoff = handoffs.constructed().getFirst();
                assertThat(history.get(1L)).isSameAs(retained);
                assertThat(history.containsKey(2L)).isEqualTo(afterInsertion);
                assertThat(retained.readValue(0, key)).containsExactly(VALUE);
                assertThat(retiringFile.isOpen()).isEqualTo(afterInsertion);
                if (afterInsertion) {
                    assertThat(failure.getSuppressed()).singleElement().satisfies(e -> assertThat(e).hasCause(closeFailure));
                }
                assertThat(handoff.newMutableSegment().size()).isZero();
                assertThatIllegalStateException().isThrownBy(() -> engine.get(KEY)).withCause(failure);
                assertThatIllegalStateException().isThrownBy(() -> engine.put(KEY, VALUE)).withCause(failure);
                assertThatIllegalStateException().isThrownBy(() -> engine.delete(KEY)).withCause(failure);
                assertLocked();
                assertThat(Files.readAllBytes(retiring)).containsExactly(FIRST_RECORD);
                assertThat(segment(3)).isEmptyFile();

                engine.close();
                engine.close();
                verify(retiringReader, times(afterInsertion ? 2 : 1)).close();
                assertThat(retiringFile.isOpen()).isFalse();
                assertThatThrownBy(handoff.newMutableSegment()::size).hasRootCauseInstanceOf(ClosedChannelException.class);
                assertThatThrownBy(() -> retained.readValue(0, key)).hasRootCauseInstanceOf(ClosedChannelException.class);
            } finally {
                engine.close();
            }
        }
        try (KeyValueStore reopened = new KeyValueStore(config)) {
            assertValue(reopened, KEY, VALUE);
        }
    }

    @Test
    void rolloverPublishesAWriteAtTheActualSuccessorLocationAndPreservesExistingEntries() {
        var config = new StorageConfig(17, 4, directory, DurabilityMode.SYNC_EACH_WRITE);
        var actualDirectory = new KeyDirectory();
        try (var directories = mockConstruction(KeyDirectory.class, withSettings().defaultAnswer(delegatesTo(actualDirectory)));
             KeyValueStore store = new KeyValueStore(config)) {
            var oldKey = new ByteKey(KEY);
            var newKey = new ByteKey(MISSING);
            store.put(KEY, VALUE);
            RecordLocation oldLocation = actualDirectory.lookup(oldKey).orElseThrow();
            assertThat(oldLocation).isEqualTo(new RecordLocation(1, 0));
            var directoryMock = directories.constructed().getFirst();
            clearInvocations(directoryMock);

            store.put(MISSING, REPLACEMENT);

            verify(directoryMock).markPresent(newKey, new RecordLocation(2, 0));
            verifyNoMoreInteractions(directoryMock);
            assertThat(actualDirectory.lookup(oldKey).orElseThrow()).isSameAs(oldLocation);
            assertValue(store, KEY, VALUE);
            assertValue(store, MISSING, REPLACEMENT);
        }
    }

    @Test
    void rolloverRetiresTheWriterAndPublishesTheSuccessorBeforePublishingTheDeletion() throws IOException {
        assertRolloverAndTombstonePublication(false);
    }

    @Test
    void forcedButUnacknowledgedTombstoneInTheSuccessorShadowsHistoryOnReopen() throws IOException {
        assertRolloverAndTombstonePublication(true);
    }

    private void assertRolloverAndTombstonePublication(boolean interruptPublication) throws IOException {
        var config = new StorageConfig(FIRST_RECORD.length, 4, directory, DurabilityMode.SYNC_EACH_WRITE);
        var actualDirectory = new KeyDirectory();
        var key = new ByteKey(KEY);
        var injected = new IllegalStateException("successor directory publication failed");
        Path successor = segment(2);
        try (var syncs = mockConstruction(FileSystemSync.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
             var directories = mockConstruction(KeyDirectory.class,
                     withSettings().defaultAnswer(delegatesTo(actualDirectory)));
             KeyValueStore store = new KeyValueStore(config)) {
            store.put(KEY, VALUE);
            KeyDirectory directoryMock = directories.constructed().getFirst();
            clearInvocations(directoryMock);
            if (interruptPublication) {
                doThrow(injected).when(directoryMock).remove(key);
            }
            FileSystemSync publisher = syncs.constructed().getFirst();
            FileSystemSync oldSync = syncs.constructed().get(1);
            var oldWriter = ArgumentCaptor.forClass(FileChannel.class);
            verify(oldSync, times(2)).forceFile(oldWriter.capture());
            clearInvocations(publisher, oldSync);
            doAnswer(call -> {
                assertThat(oldWriter.getValue().isOpen()).isFalse();
                assertThat(successor).isEmptyFile();
                return call.callRealMethod();
            }).when(publisher).forceFile(any(FileChannel.class));
            doAnswer(call -> {
                assertThat(successor).isEmptyFile();
                return call.callRealMethod();
            }).when(publisher).forceDirectory(directory);

            if (interruptPublication) {
                assertThatThrownBy(() -> store.delete(KEY)).isSameAs(injected);
                assertFailed(store, injected, KEY);
                assertLocked();
                assertThat(actualDirectory.lookup(key)).contains(new RecordLocation(1, 0));
            } else {
                store.delete(KEY);
                assertThat(store.get(KEY)).isEmpty();
                assertThat(actualDirectory.lookup(key)).isEmpty();
            }

            assertThat(directories.constructed()).hasSize(1);
            FileSystemSync successorSync = syncs.constructed().get(2);
            var ordered = inOrder(publisher, successorSync, directoryMock);
            ordered.verify(publisher).forceFile(any(FileChannel.class));
            ordered.verify(publisher).forceDirectory(directory);
            ordered.verify(successorSync).forceFile(any(FileChannel.class));
            ordered.verify(directoryMock).remove(key);
            verify(directoryMock, never()).markPresent(any(), any());
            verifyNoInteractions(oldSync);
            assertThat(Files.readAllBytes(active)).containsExactly(FIRST_RECORD);
            assertThat(Files.readAllBytes(successor)).containsExactly(TOMBSTONE);
        }
        try (KeyValueStore reopened = new KeyValueStore(config)) {
            assertThat(reopened.get(KEY)).isEmpty();
        }
    }

    @Test
    void partialAppendAfterRolloverLeavesTheTailOnlyInTheHighestSegment() throws IOException {
        var config = new StorageConfig(OVERWRITE.length, 5, directory, DurabilityMode.SYNC_EACH_WRITE);
        Path successor = segment(2);
        IOException injected = new IOException("interrupted successor append");
        byte[] prefix = Arrays.copyOf(OVERWRITE, 3);
        var openedWriters = new ArrayList<FileChannel>();
        try (KeyValueStore store = new KeyValueStore(config)) {
            store.put(KEY, VALUE);
            try (var channels = mockChannels(directory)) {
                channels.when(() -> FileChannel.open(successor, WRITE, CREATE_NEW)).thenAnswer(call ->
                        successor.getFileSystem().provider().newFileChannel(successor, Set.of(WRITE, CREATE_NEW)));
                channels.when(() -> FileChannel.open(successor, READ)).thenAnswer(call ->
                        successor.getFileSystem().provider().newFileChannel(successor, Set.of(READ)));
                channels.when(() -> FileChannel.open(successor, WRITE)).thenAnswer(call -> {
                    FileChannel file = successor.getFileSystem().provider().newFileChannel(successor, Set.of(WRITE));
                    openedWriters.add(file);
                    FileChannel writer = mock(FileChannel.class, delegatesTo(file));
                    doAnswer(write -> {
                        ByteBuffer buffer = write.getArgument(0);
                        ByteBuffer partial = buffer.slice(0, prefix.length);
                        while (partial.hasRemaining()) {
                            assertThat(file.write(partial)).isPositive();
                        }
                        buffer.position(buffer.position() + prefix.length);
                        return prefix.length;
                    }).doThrow(injected).when(writer).write(any(ByteBuffer.class));
                    return writer;
                });

                StorageEngineException failure = assertFailure(() -> store.put(KEY, REPLACEMENT), Operation.PUT,
                        Reason.STORAGE_IO, MutationOutcome.UNKNOWN);
                assertThat(failure).hasRootCause(injected);
                assertFailed(store, failure, KEY);
                assertLocked();
                assertThat(segmentCount(directory)).isEqualTo(2);
                assertThat(Files.readAllBytes(active)).containsExactly(FIRST_RECORD);
                assertThat(Files.readAllBytes(successor)).containsExactly(prefix);
            }
        }
        assertThat(openedWriters).hasSize(1).allMatch(channel -> !channel.isOpen());
        assertThat(Files.readAllBytes(active)).containsExactly(FIRST_RECORD);
        assertThat(Files.readAllBytes(successor)).containsExactly(prefix);
        try (FileChannel lockChannel = FileChannel.open(directory.resolve("LOCK"), WRITE);
             var lock = lockChannel.tryLock()) {
            assertThat(lock).isNotNull();
        }
        try (KeyValueStore reopened = new KeyValueStore(config)) {
            assertValue(reopened, KEY, VALUE);
            assertThat(successor).isEmptyFile();
            assertThat(Files.readAllBytes(active)).containsExactly(FIRST_RECORD);
        }
    }

    @Test
    void reopenForcesAnEmptySuccessorAndDirectoryBeforeItAcceptsItsFirstWrite() throws IOException {
        var config = new StorageConfig(32, 4, directory, DurabilityMode.SYNC_EACH_WRITE);
        try (KeyValueStore store = new KeyValueStore(config)) {
            store.put(KEY, VALUE);
            store.put(MISSING, VALUE);
            store.delete(KEY);
        }
        Path emptySuccessor = Files.createFile(segment(3));
        byte[] oldestBytes = Files.readAllBytes(active);
        byte[] tombstoneBytes = Files.readAllBytes(segment(2));
        try (var syncs = mockConstruction(FileSystemSync.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
             KeyValueStore store = new KeyValueStore(config)) {
            FileSystemSync publisher = syncs.constructed().getFirst();
            FileSystemSync activeSync = syncs.constructed().get(1);
            var ordered = inOrder(activeSync, publisher);
            ordered.verify(activeSync).forceFile(any(FileChannel.class));
            ordered.verify(publisher).forceDirectory(directory);
            assertThat(emptySuccessor).isEmptyFile();
            assertValue(store, MISSING, VALUE);
            assertThat(store.get(KEY)).isEmpty();

            store.put(KEY, VALUE);
            ordered.verify(activeSync).forceFile(any(FileChannel.class));
            assertThat(Files.readAllBytes(emptySuccessor)).containsExactly(FIRST_RECORD);
            assertThat(segmentCount(directory)).isEqualTo(3);
            assertThat(Files.readAllBytes(active)).containsExactly(oldestBytes);
            assertThat(Files.readAllBytes(segment(2))).containsExactly(tombstoneBytes);
        }
        try (KeyValueStore reopened = new KeyValueStore(config)) {
            assertValue(reopened, KEY, VALUE);
            assertValue(reopened, MISSING, VALUE);
        }
    }

    private void assertRejectionsPreserveHistory(KeyValueStore store, Throwable failure, byte[] expected,
                                                Object... collaborators) throws IOException {
        clearInvocations(collaborators);
        assertFailed(store, failure, KEY);
        assertFailed(store, failure, MISSING);
        verifyNoInteractions(collaborators);
        assertThat(Files.readAllBytes(active)).containsExactly(expected);
        try (var files = Files.list(directory)) {
            assertThat(files).containsExactlyInAnyOrder(active, directory.resolve("LOCK"));
        }
    }

    private void assertLocked() {
        assertFailure(this::openAndClose, Operation.OPEN, Reason.DATABASE_LOCKED, MutationOutcome.NOT_APPLICABLE);
    }

    private KeyValueStore openWithWriter(FileChannel writer) {
        try (var channels = mockChannels(directory)) {
            channels.when(() -> FileChannel.open(active, READ))
                    .thenAnswer(call -> active.getFileSystem().provider().newFileChannel(active, Set.of(READ)));
            channels.when(() -> FileChannel.open(active, WRITE)).thenReturn(writer);
            return open();
        }
    }

    private void assertActiveRecoveryKeepsOriginalValue() throws IOException {
        try (KeyValueStore reopened = open()) {
            assertValue(reopened, KEY, VALUE);
            assertThat(Files.readAllBytes(active)).containsExactly(FIRST_RECORD);
        }
    }

    private void openAndClose() {
        try (KeyValueStore ignored = open()) {
            throw new AssertionError("Unexpected successful open");
        }
    }

    private KeyValueStore open() {
        return new KeyValueStore(KeyValueStoreTestSupport.config(directory, 256));
    }

    private Path segment(long id) {
        return directory.resolve("segment_%019d.log".formatted(id));
    }

    private static Runnable mutation(KeyValueStore store, Operation operation) {
        return operation == Operation.PUT ? () -> store.put(KEY, REPLACEMENT) : () -> store.delete(KEY);
    }

    private static byte[] historyWith(byte[] record, int prefixLength) {
        return ByteBuffer.allocate(FIRST_RECORD.length + prefixLength)
                .put(FIRST_RECORD).put(record, 0, prefixLength).array();
    }
}
