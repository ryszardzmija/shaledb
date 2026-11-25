package com.ryszardzmija.shaledb.storage;

import com.ryszardzmija.shaledb.storage.StorageEngineException.MutationOutcome;
import com.ryszardzmija.shaledb.storage.StorageEngineException.Operation;
import com.ryszardzmija.shaledb.storage.StorageEngineException.Reason;
import com.ryszardzmija.shaledb.storage.config.StorageConfig;
import com.ryszardzmija.shaledb.storage.durability.DurabilityMode;
import com.ryszardzmija.shaledb.storage.hash.index.ByteKey;
import com.ryszardzmija.shaledb.storage.hash.index.KeyDirectory;
import com.ryszardzmija.shaledb.storage.hash.index.RecordLocation;
import com.ryszardzmija.shaledb.storage.hash.segment.model.IndexConsistencyException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.ryszardzmija.shaledb.storage.KeyValueStoreTestSupport.assertFailed;
import static com.ryszardzmija.shaledb.storage.KeyValueStoreTestSupport.assertFailure;
import static com.ryszardzmija.shaledb.storage.KeyValueStoreTestSupport.assertValue;
import static com.ryszardzmija.shaledb.storage.KeyValueStoreTestSupport.config;
import static com.ryszardzmija.shaledb.storage.KeyValueStoreTestSupport.encodeRecord;
import static com.ryszardzmija.shaledb.storage.KeyValueStoreTestSupport.mockChannels;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.withSettings;

class KeyValueStoreDirectoryTest {
    private static final byte[] KEY = {0, -1};
    private static final byte[] COLLISION = {-1, 30};
    private static final byte[] OTHER = {2};
    private static final byte[] MISSING = {3};
    private static final byte[] ORDER_KEY = {4};
    private static final byte[] EMPTY = {};
    private static final byte[] EARLIER = {42};
    private static final byte[] LATEST = {43};

    @TempDir
    Path directory;

    @Test
    void aPointReadTouchesOnlyItsReferencedSegmentAndAMissTouchesNone() throws IOException {
        directory = directory.toRealPath();
        byte[] first = encodeRecord(0, KEY, EARLIER);
        Path oldest = writeSegment("9", first, encodeRecord(0, COLLISION, LATEST));
        Path middle = writeSegment("10", encodeRecord(0, OTHER, EMPTY));
        Path active = writeSegment(Long.toString(Long.MAX_VALUE), encodeRecord(1, KEY, EMPTY),
                encodeRecord(0, MISSING, EARLIER));
        try (FileChannel oldestFile = FileChannel.open(oldest, READ);
             FileChannel middleFile = FileChannel.open(middle, READ);
             FileChannel activeFile = FileChannel.open(active, READ)) {
            FileChannel oldestReader = mock(FileChannel.class, delegatesTo(oldestFile));
            FileChannel middleReader = mock(FileChannel.class, delegatesTo(middleFile));
            FileChannel activeReader = mock(FileChannel.class, delegatesTo(activeFile));
            try (var channels = mockChannels(directory)) {
                channels.when(() -> FileChannel.open(oldest, READ)).thenReturn(oldestReader);
                channels.when(() -> FileChannel.open(middle, READ)).thenReturn(middleReader);
                channels.when(() -> FileChannel.open(active, READ)).thenReturn(activeReader);
                channels.when(() -> FileChannel.open(active, WRITE)).thenAnswer(call ->
                        active.getFileSystem().provider().newFileChannel(active, Set.of(WRITE)));
                try (KeyValueStore store = open()) {
                    clearInvocations(oldestReader, middleReader, activeReader);
                    assertThat(store.get(KEY)).isEmpty();
                    assertThat(store.get(new byte[] {99})).isEmpty();
                    verifyNoInteractions(oldestReader, middleReader, activeReader);

                    assertValue(store, COLLISION, LATEST);
                    assertRecordRead(oldestReader, first.length);
                    verifyNoInteractions(middleReader, activeReader);

                    clearInvocations(oldestReader);
                    assertValue(store, OTHER, EMPTY);
                    assertRecordRead(middleReader, 0);
                    verifyNoInteractions(oldestReader, activeReader);

                    clearInvocations(middleReader);
                    assertValue(store, MISSING, EARLIER);
                    assertRecordRead(activeReader, encodeRecord(1, KEY, EMPTY).length);
                    verifyNoInteractions(oldestReader, middleReader);
                }
            }
        }
    }

    @Test
    void replaysCompatibleNamesAndOffsetsInNumericOrderWithoutRewritingHistory() throws IOException {
        byte[] keyOld = encodeRecord(0, KEY, EARLIER);
        byte[] keyLatest = encodeRecord(0, KEY, LATEST);
        byte[] keyDeleted = encodeRecord(1, KEY, EMPTY);
        byte[] otherOld = encodeRecord(0, OTHER, EARLIER);
        byte[] otherDeleted = encodeRecord(1, OTHER, EMPTY);
        byte[] collisionEmpty = encodeRecord(0, COLLISION, EMPTY);
        byte[] orderEarlier = encodeRecord(0, ORDER_KEY, EARLIER);
        byte[] orderLatest = encodeRecord(0, ORDER_KEY, LATEST);
        Path oldest = writeSegment("9", keyOld, keyLatest, otherOld, orderEarlier);
        Path middle = writeSegment("10", keyDeleted, otherDeleted, orderEarlier, orderLatest);
        Path localized = writeSegment("٠٠٠٠٢١", keyDeleted, keyOld, otherDeleted, collisionEmpty);
        Path active = writeSegment("0000000000000000300");
        var before = Map.of(oldest, Files.readAllBytes(oldest), middle, Files.readAllBytes(middle),
                localized, Files.readAllBytes(localized), active, EMPTY);

        for (int i = 0; i < 2; i++) {
            try (KeyValueStore store = open()) {
                assertValue(store, KEY, EARLIER);
                assertValue(store, COLLISION, EMPTY);
                assertValue(store, ORDER_KEY, LATEST);
                assertThat(store.get(OTHER)).isEmpty();
                assertThat(store.get(MISSING)).isEmpty();
            }
            for (var file : before.entrySet()) {
                assertThat(Files.readAllBytes(file.getKey())).containsExactly(file.getValue());
            }
        }
    }

    @Test
    void verifiesEveryTombstoneAcrossRepeatedRolloverAndReopen() throws IOException {
        var config = new StorageConfig(17, 4, directory, DurabilityMode.SYNC_EACH_WRITE);
        try (var store = new KeyValueStore(config)) {
            store.put(KEY, EARLIER);
            assertValue(store, KEY, EARLIER);
            store.put(KEY, LATEST);
            assertValue(store, KEY, LATEST);
            store.delete(KEY);
            assertThat(store.get(KEY)).isEmpty();
            store.delete(KEY);
            assertThat(store.get(KEY)).isEmpty();
        }
        try (var store = new KeyValueStore(config)) {
            assertThat(store.get(KEY)).isEmpty();
            store.put(KEY, LATEST);
            assertValue(store, KEY, LATEST);
            store.put(KEY, EMPTY);
            assertValue(store, KEY, EMPTY);
            store.delete(MISSING);
            assertThat(store.get(MISSING)).isEmpty();
        }
        byte[][] expected = {encodeRecord(0, KEY, EARLIER), encodeRecord(0, KEY, LATEST),
                encodeRecord(1, KEY, EMPTY), encodeRecord(1, KEY, EMPTY), encodeRecord(0, KEY, LATEST),
                encodeRecord(0, KEY, EMPTY), encodeRecord(1, MISSING, EMPTY)};
        for (int i = 0; i < expected.length; i++) {
            Path path = directory.resolve(String.format(Locale.ROOT, "segment_%019d.log", i + 1));
            assertThat(Files.readAllBytes(path)).containsExactly(expected[i]);
        }
        try (var store = new KeyValueStore(config)) {
            assertValue(store, KEY, EMPTY);
            assertThat(store.get(MISSING)).isEmpty();
        }
    }

    @Test
    void rejectsCorruptionInOverwrittenHistoryBeforeRecoveringTheActiveTail() throws IOException {
        assertObsoleteCorruption(encodeRecord(0, KEY, LATEST));
    }

    @Test
    void rejectsCorruptionInDeletedHistoryBeforeRecoveringTheActiveTail() throws IOException {
        assertObsoleteCorruption(encodeRecord(1, KEY, EMPTY));
    }

    @Test
    void unknownSegmentReferenceFailsWithItsIdAndOffsetWithoutReadingOlderVersions() throws IOException {
        writeSegment("9", encodeRecord(0, KEY, EARLIER));
        writeSegment("10", encodeRecord(0, KEY, LATEST));
        var actualDirectory = new KeyDirectory();
        try (var directories = mockConstruction(KeyDirectory.class, withSettings().defaultAnswer(delegatesTo(actualDirectory)));
             KeyValueStore store = open()) {
            assertThat(actualDirectory.lookup(new ByteKey(KEY))).contains(new RecordLocation(10, 0));
            doReturn(Optional.of(new RecordLocation(600, 39))).when(directories.constructed().getFirst())
                    .lookup(new ByteKey(KEY));
            var failure = assertFailure(() -> store.get(KEY), Operation.GET, Reason.INVALID_INDEX_REFERENCE,
                    MutationOutcome.NOT_APPLICABLE);
            assertThat(failure.getCause()).isInstanceOfSatisfying(IndexConsistencyException.class, cause -> {
                assertThat(cause).hasMessageContaining("segment ID 600");
                assertThat(cause.recordOffset()).isEqualTo(39);
            });
            assertFailed(store, failure, KEY);
            assertFailure(this::reopenAndClose, Operation.OPEN, Reason.DATABASE_LOCKED, MutationOutcome.NOT_APPLICABLE);
        }
        try (KeyValueStore store = open()) {
            assertValue(store, KEY, LATEST);
        }
    }

    private void assertObsoleteCorruption(byte[] replacement) throws IOException {
        byte[] invalid = encodeRecord(0, KEY, EARLIER);
        invalid[0] ^= 1;
        Path historical = writeSegment("1", invalid);
        byte[] tail = Arrays.copyOf(encodeRecord(0, OTHER, LATEST), 3);
        Path active = writeSegment("2", replacement, tail);
        byte[] interrupted = Files.readAllBytes(active);
        var failure = assertFailure(this::reopenAndClose, Operation.OPEN, Reason.CHECKSUM_CORRUPTION,
                MutationOutcome.NOT_APPLICABLE);
        assertThat(failure.getCause().getCause()).hasMessageContaining(historical.toString());
        assertThat(Files.readAllBytes(active)).containsExactly(interrupted);
        assertThat(Files.readAllBytes(historical)).containsExactly(invalid);
    }

    private static void assertRecordRead(FileChannel reader, long offset) throws IOException {
        verify(reader).size();
        verify(reader).read(any(ByteBuffer.class), eq(offset));
        verify(reader).read(any(ByteBuffer.class), eq(offset + 13));
        verifyNoMoreInteractions(reader);
    }

    private Path writeSegment(String id, byte[]... records) throws IOException {
        Path path = directory.resolve("segment_" + id + ".log");
        try (var stream = Files.newOutputStream(path)) {
            for (byte[] record : records) {
                stream.write(record);
            }
        }
        return path;
    }

    private KeyValueStore open() {
        return new KeyValueStore(config(directory, 256));
    }

    private void reopenAndClose() {
        open().close();
    }
}
