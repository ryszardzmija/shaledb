package com.ryszardzmija.shaledb.storage;

import com.ryszardzmija.shaledb.storage.StorageEngineException.MutationOutcome;
import com.ryszardzmija.shaledb.storage.StorageEngineException.Operation;
import com.ryszardzmija.shaledb.storage.StorageEngineException.Reason;
import com.ryszardzmija.shaledb.storage.config.StorageConfig;
import com.ryszardzmija.shaledb.storage.durability.DurabilityException;
import com.ryszardzmija.shaledb.storage.durability.DurabilityMode;
import com.ryszardzmija.shaledb.storage.durability.FileSystemSync;
import com.ryszardzmija.shaledb.storage.hash.index.ByteKey;
import com.ryszardzmija.shaledb.storage.hash.index.HashIndex;
import com.ryszardzmija.shaledb.storage.hash.segment.loader.LoadedSegments;
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
    void partialHeaderAppendLeavesAnExactUnmodifiedSuffixAfterClose() throws IOException {
        assertAppendFailure(Operation.PUT, OVERWRITE, 3);
        assertFailure(this::openAndClose, Operation.OPEN, Reason.INCOMPLETE_RECORD, MutationOutcome.NOT_APPLICABLE);
    }

    @Test
    void partialPayloadAppendLeavesAnExactUnmodifiedSuffixAfterClose() throws IOException {
        assertAppendFailure(Operation.PUT, OVERWRITE, FormatInfo.getHeaderSize() + 1);
        assertFailure(this::openAndClose, Operation.OPEN, Reason.INCOMPLETE_RECORD, MutationOutcome.NOT_APPLICABLE);
    }

    @Test
    void partialTombstoneAppendLeavesAnExactUnmodifiedSuffixAfterClose() throws IOException {
        assertAppendFailure(Operation.DELETE, TOMBSTONE, FormatInfo.getHeaderSize() + 1);
        assertFailure(this::openAndClose, Operation.OPEN, Reason.INCOMPLETE_RECORD, MutationOutcome.NOT_APPLICABLE);
    }

    private void assertAppendFailure(Operation operation, byte[] record, int prefixLength) throws IOException {
        IOException writeFailure = new IOException("append failed");
        byte[] expected = historyWith(record, prefixLength);
        try (FileChannel channel = FileChannel.open(active, WRITE)) {
            FileChannel writer = mock(FileChannel.class, delegatesTo(channel));
            try (KeyValueStore store = openWithWriter(writer)) {
                store.put(KEY, VALUE);
                assertValue(store, KEY, VALUE);
                clearInvocations(writer);
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
                assertThat(Files.readAllBytes(active)).containsExactly(expected);
                assertRejectionsPreserveHistory(store, failure, expected, writer);
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
        HashIndex originalIndex = new HashIndex();
        byte[] expected = historyWith(OVERWRITE, OVERWRITE.length);
        try (FileChannel channel = FileChannel.open(active, WRITE);
             var indexes = mockConstruction(HashIndex.class, withSettings().defaultAnswer(delegatesTo(originalIndex)))) {
            FileChannel writer = mock(FileChannel.class, delegatesTo(channel));
            try (KeyValueStore store = openWithWriter(writer)) {
                store.put(KEY, VALUE);
                HashIndex index = indexes.constructed().getFirst();
                clearInvocations(writer, index);
                doAnswer(call -> {
                    assertThat(Files.readAllBytes(active)).containsExactly(expected);
                    channel.force(true);
                    return null;
                }).when(writer).force(true);

                store.put(KEY, REPLACEMENT);

                var order = inOrder(writer, index);
                order.verify(writer).write(any(ByteBuffer.class));
                order.verify(writer).force(true);
                order.verify(index).markPresent(new ByteKey(KEY), FIRST_RECORD.length);
                assertValue(store, KEY, REPLACEMENT);
            }
        }
    }

    private void assertRecordForceFailure(Operation operation, byte[] record) throws IOException {
        IOException syncFailure = new IOException("file force reported failure");
        byte[] expected = historyWith(record, record.length);
        HashIndex originalIndex = new HashIndex();
        try (FileChannel channel = FileChannel.open(active, WRITE);
             var indexes = mockConstruction(HashIndex.class, withSettings().defaultAnswer(delegatesTo(originalIndex)))) {
            FileChannel writer = mock(FileChannel.class, delegatesTo(channel));
            try (KeyValueStore store = openWithWriter(writer)) {
                store.put(KEY, VALUE);
                HashIndex index = indexes.constructed().getFirst();
                clearInvocations(writer, index);
                doAnswer(call -> {
                    assertThat(Files.readAllBytes(active)).containsExactly(expected);
                    throw syncFailure;
                }).when(writer).force(true);

                StorageEngineException failure = assertFailure(mutation(store, operation), operation, Reason.DURABILITY,
                        MutationOutcome.UNKNOWN);
                assertThat(failure).hasRootCause(syncFailure);
                verify(writer).force(true);
                verifyNoInteractions(index);
                assertRejectionsPreserveHistory(store, failure, expected, writer, index);
            }
            verify(writer, never()).force(true);
        }
        assertThat(Files.readAllBytes(active)).containsExactly(expected);
    }

    @Test
    void forceCompletesBeforeAnOverwriteFailsOnItsWayToIndexPublication() throws IOException {
        var failure = new IllegalArgumentException("unexpected failure obtaining the written offset");
        byte[] expected = historyWith(OVERWRITE, OVERWRITE.length);
        HashIndex originalIndex = new HashIndex();
        try (FileChannel channel = FileChannel.open(active, WRITE);
             var indexes = mockConstruction(HashIndex.class, withSettings().defaultAnswer(delegatesTo(originalIndex)))) {
            FileChannel writer = mock(FileChannel.class, delegatesTo(channel));
            try (KeyValueStore store = openWithWriter(writer)) {
                store.put(KEY, VALUE);
                HashIndex index = indexes.constructed().getFirst();
                clearInvocations(writer, index);
                try (var results = mockConstruction(WriteResult.class, (result, context) ->
                        when(result.writeOffset()).thenThrow(failure))) {
                    assertThatThrownBy(() -> store.put(KEY, REPLACEMENT)).isSameAs(failure);
                    var order = inOrder(writer, results.constructed().getFirst());
                    order.verify(writer).write(any(ByteBuffer.class));
                    order.verify(writer).force(true);
                    order.verify(results.constructed().getFirst()).writeOffset();
                }
                verifyNoInteractions(index);
                assertRejectionsPreserveHistory(store, failure, expected, writer, index);
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
    void forcedDeleteFailsBeforePublishingItsTombstoneAndReplaysOnReopen() throws IOException {
        assertDeletePublicationFailure(false);
    }

    @Test
    void interruptedIndexPublicationCanLeaveBothADeletedMarkerAndAPresentOffset() throws IOException {
        assertDeletePublicationFailure(true);
    }

    private void assertDeletePublicationFailure(boolean interruptUpdate) throws IOException {
        var failure = new IllegalStateException("index publication failed");
        var key = new ByteKey(KEY);
        byte[] expected = historyWith(TOMBSTONE, TOMBSTONE.length);
        HashIndex originalIndex = new HashIndex();
        try (FileChannel channel = FileChannel.open(active, WRITE);
             var indexes = mockConstruction(HashIndex.class, withSettings().defaultAnswer(delegatesTo(originalIndex)))) {
            FileChannel writer = mock(FileChannel.class, delegatesTo(channel));
            try (KeyValueStore store = openWithWriter(writer)) {
                store.put(KEY, VALUE);
                HashIndex index = indexes.constructed().getFirst();
                clearInvocations(writer, index);
                doAnswer(call -> {
                    assertThat(Files.readAllBytes(active)).containsExactly(expected);
                    if (interruptUpdate) {
                        // Simulate adding the marker before removing the original offset.
                        doReturn(true).when(index).isDeleted(key);
                    }
                    throw failure;
                }).when(index).markDeleted(key);

                assertThatThrownBy(() -> store.delete(KEY)).isSameAs(failure);
                var order = inOrder(writer, index);
                order.verify(writer).write(any(ByteBuffer.class));
                order.verify(writer).force(true);
                order.verify(index).markDeleted(key);
                assertThat(index.isDeleted(key)).isEqualTo(interruptUpdate);
                assertThat(index.getKeyOffset(key)).contains(0L);
                assertRejectionsPreserveHistory(store, failure, expected, writer, index);
                assertLocked();
            }
            verify(writer, never()).force(true);
        }
        assertThat(Files.readAllBytes(active)).containsExactly(expected);
        try (KeyValueStore reopened = open()) {
            assertThat(reopened.get(KEY)).isEmpty();
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
    void interruptedLayoutPublicationKeepsReplacementChannelsEligibleForCleanup() throws IOException {
        var failure = new AssertionError("history publication failed");
        var history = new ArrayList<ImmutableSegment>() {
            @Override
            public void addFirst(ImmutableSegment segment) {
                throw failure;
            }
        };
        StorageConfig config = new StorageConfig(17, 4, directory, DurabilityMode.SYNC_EACH_WRITE);
        try (var loaded = mockConstruction(LoadedSegments.class, (result, context) -> {
            when(result.immutableSegments()).thenReturn(history);
            when(result.mutableSegment()).thenReturn((MutableSegment) context.arguments().get(1));
        }); var handoffs = mockConstruction(RolloverResult.class, (result, context) -> {
            when(result.newMutableSegment()).thenReturn((MutableSegment) context.arguments().get(0));
            when(result.newImmutableSegment()).thenReturn((ImmutableSegment) context.arguments().get(1));
        }); KeyValueStore store = new KeyValueStore(config)) {
            assertThat(loaded.constructed()).hasSize(1);
            store.put(KEY, VALUE);
            assertThatThrownBy(() -> store.delete(KEY)).isSameAs(failure);
            var handoff = handoffs.constructed().getFirst();
            assertThatThrownBy(handoff.newImmutableSegment()::size).hasRootCauseInstanceOf(ClosedChannelException.class);
            assertThat(handoff.newMutableSegment().size()).isZero();
            assertFailed(store, failure, KEY);
            assertLocked();
            assertThat(Files.readAllBytes(active)).containsExactly(FIRST_RECORD);
            assertThat(segment(2)).isEmptyFile();

            store.close();
            assertThatThrownBy(handoff.newMutableSegment()::size).hasRootCauseInstanceOf(ClosedChannelException.class);
        }
        try (KeyValueStore reopened = new KeyValueStore(config)) {
            assertValue(reopened, KEY, VALUE);
        }
    }

    @Test
    void rolloverRetiresTheWriterAndPublishesTheSuccessorBeforeAppendingAndIndexing() throws IOException {
        assertRolloverAndTombstonePublication(false);
    }

    @Test
    void forcedButUnacknowledgedTombstoneInTheSuccessorShadowsHistoryOnReopen() throws IOException {
        assertRolloverAndTombstonePublication(true);
    }

    private void assertRolloverAndTombstonePublication(boolean interruptPublication) throws IOException {
        var config = new StorageConfig(FIRST_RECORD.length, 4, directory, DurabilityMode.SYNC_EACH_WRITE);
        var oldIndex = new HashIndex();
        var newIndex = new HashIndex();
        var key = new ByteKey(KEY);
        var injected = new IllegalStateException("successor index publication failed");
        Path successor = segment(2);
        try (var syncs = mockConstruction(FileSystemSync.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
             var indexes = mockConstruction(HashIndex.class,
                     context -> withSettings().defaultAnswer(delegatesTo(context.getCount() == 1 ? oldIndex : newIndex)),
                     (index, context) -> {
                         if (context.getCount() == 2 && interruptPublication) {
                             doThrow(injected).when(index).markDeleted(key);
                         }
                     });
             KeyValueStore store = new KeyValueStore(config)) {
            store.put(KEY, VALUE);
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
                assertThat(newIndex.isDeleted(key)).isFalse();
            } else {
                store.delete(KEY);
                assertThat(store.get(KEY)).isEmpty();
                assertThat(newIndex.isDeleted(key)).isTrue();
            }

            assertThat(indexes.constructed()).hasSize(2); // Retirement reused the live index.
            FileSystemSync successorSync = syncs.constructed().get(2);
            HashIndex successorIndex = indexes.constructed().get(1);
            var ordered = inOrder(publisher, successorSync, successorIndex);
            ordered.verify(publisher).forceFile(any(FileChannel.class));
            ordered.verify(publisher).forceDirectory(directory);
            ordered.verify(successorSync).forceFile(any(FileChannel.class));
            ordered.verify(successorIndex).markDeleted(key);
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
        assertFailure(this::openAndClose, Operation.OPEN, Reason.INCOMPLETE_RECORD, MutationOutcome.NOT_APPLICABLE);
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
