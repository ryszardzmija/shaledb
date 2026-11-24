package com.ryszardzmija.shaledb.storage;

import com.ryszardzmija.shaledb.storage.StorageEngineException.MutationOutcome;
import com.ryszardzmija.shaledb.storage.StorageEngineException.Operation;
import com.ryszardzmija.shaledb.storage.StorageEngineException.Reason;
import com.ryszardzmija.shaledb.storage.config.StorageConfig;
import com.ryszardzmija.shaledb.storage.durability.DurabilityMode;
import com.ryszardzmija.shaledb.storage.hash.index.IndexBuildException;
import com.ryszardzmija.shaledb.storage.hash.segment.files.SegmentFileDiscoveryException;
import com.ryszardzmija.shaledb.storage.hash.segment.loader.SegmentLoadingException;
import com.ryszardzmija.shaledb.storage.hash.segment.model.IndexConsistencyException;
import com.ryszardzmija.shaledb.storage.serialization.io.DataCorruptionException;
import com.ryszardzmija.shaledb.storage.serialization.io.IncompleteRecordException;
import com.ryszardzmija.shaledb.storage.serialization.io.IncompleteRecordException.Section;
import com.ryszardzmija.shaledb.storage.serialization.io.ReadRequest;
import com.ryszardzmija.shaledb.storage.serialization.io.RecordIOException;
import com.ryszardzmija.shaledb.storage.serialization.spec.FormatInfo;
import com.ryszardzmija.shaledb.storage.serialization.spec.FormatInfo.PayloadLimitExceededException;
import com.ryszardzmija.shaledb.storage.serialization.spec.TypeDecodingException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Arrays;

import static com.ryszardzmija.shaledb.storage.KeyValueStoreTestSupport.assertFailed;
import static com.ryszardzmija.shaledb.storage.KeyValueStoreTestSupport.assertFailure;
import static com.ryszardzmija.shaledb.storage.KeyValueStoreTestSupport.assertValue;
import static com.ryszardzmija.shaledb.storage.KeyValueStoreTestSupport.invalidHeader;
import static com.ryszardzmija.shaledb.storage.KeyValueStoreTestSupport.segmentCount;
import static java.nio.file.StandardOpenOption.WRITE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class KeyValueStoreFailureTest {
    private static final int HEADER_SIZE = FormatInfo.getHeaderSize();
    private static final byte[] KEY = {1};
    private static final byte[] VALUE = {2, 3};
    private static final byte[] OTHER_KEY = {4};

    @TempDir
    Path directory;

    @Test
    void startupReportsStructuralCorruptionInAnOlderSegment() throws IOException {
        Path older = Files.write(segment(1), invalidHeader());
        Files.createFile(segment(2));

        StorageEngineException failure = openFailure(config(128, 256), Reason.STRUCTURAL_CORRUPTION);

        assertThat(failure.getCause()).isInstanceOf(SegmentLoadingException.class);
        Throwable segmentFailure = failure.getCause().getCause();
        assertThat(segmentFailure).hasMessageContaining(older.toString()).hasCauseInstanceOf(IndexBuildException.class);
        assertThat(segmentFailure.getCause().getCause()).isInstanceOfSatisfying(DataCorruptionException.class, cause -> {
            assertThat(cause.kind()).isEqualTo(DataCorruptionException.Kind.STRUCTURE);
            assertThat(cause.recordOffset()).isZero();
            assertThat(cause).hasCauseInstanceOf(TypeDecodingException.class);
        });
        assertThat(Files.readAllBytes(older)).containsExactly(invalidHeader());
    }

    @Test
    void startupReportsChecksumCorruptionInTheActiveSegment() throws IOException {
        createRecord();
        overwriteByte(segment(1), HEADER_SIZE, 99);

        StorageEngineException failure = openFailure(config(128, 256), Reason.CHECKSUM_CORRUPTION);

        assertThat(failure).hasRootCauseInstanceOf(DataCorruptionException.class);
    }

    @Test
    void startupReportsIncompleteHistoricalHeaderAndPayloadWithTheirExtents() throws IOException {
        createRecord();
        byte[] complete = Files.readAllBytes(segment(1));
        byte[] activeTail = Arrays.copyOf(complete, 3);
        Files.write(segment(2), activeTail);

        byte[] shortHeader = Arrays.copyOf(complete, HEADER_SIZE - 1);
        Files.write(segment(1), shortHeader);
        assertIncomplete(openFailure(config(128, 256), Reason.INCOMPLETE_RECORD), Section.HEADER, HEADER_SIZE, HEADER_SIZE - 1);
        assertThat(Files.readAllBytes(segment(1))).containsExactly(shortHeader);
        assertThat(Files.readAllBytes(segment(2))).containsExactly(activeTail);

        byte[] shortPayload = Arrays.copyOf(complete, complete.length - 1);
        Files.write(segment(1), shortPayload);
        assertIncomplete(openFailure(config(128, 256), Reason.INCOMPLETE_RECORD), Section.PAYLOAD, 3, 2);
        assertThat(Files.readAllBytes(segment(1))).containsExactly(shortPayload);
        assertThat(Files.readAllBytes(segment(2))).containsExactly(activeTail);
    }

    @Test
    void distinguishesInvalidConfigurationFromIncompatibleHistoricalData() throws IOException {
        assertThatIllegalArgumentException().isThrownBy(() -> config(0, 256));
        assertThat(segmentCount(directory)).isZero();
        createRecord();
        Files.createFile(segment(2));

        StorageEngineException failure = openFailure(config(2, 256), Reason.INCOMPATIBLE_RECORD_SIZE);

        assertThat(failure).hasRootCauseInstanceOf(PayloadLimitExceededException.class);
        Throwable recordFailure = failure.getCause().getCause().getCause().getCause();
        assertThat(recordFailure).isInstanceOfSatisfying(RecordIOException.ReadLimitExceeded.class, cause -> {
            assertThat(cause.recordOffset()).isZero();
            assertThat(cause.getCause()).isInstanceOfSatisfying(PayloadLimitExceededException.class, limit -> {
                assertThat(limit.payloadSize()).isEqualTo(3);
                assertThat(limit.maxPayloadSize()).isEqualTo(2);
            });
        });
        try (KeyValueStore store = new KeyValueStore(config(128, 256))) {
            assertValue(store, KEY, VALUE);
        }
    }

    @Test
    void startupPreservesTheFilesystemCauseOfAMissingDirectory() {
        Path missing = directory.resolve("missing-directory");
        StorageConfig config = new StorageConfig(256, 128, missing, DurabilityMode.SYNC_EACH_WRITE);

        StorageEngineException failure = openFailure(config, Reason.OWNERSHIP_IO);

        assertThat(failure).hasRootCauseInstanceOf(NoSuchFileException.class);
        assertThat(missing).doesNotExist();
    }

    @Test
    void startupRejectsABrokenSegmentSymlinkAndLeavesItInPlace() throws IOException {
        Path missing = directory.resolve("missing");
        Path link = Files.createSymbolicLink(segment(1), missing);

        StorageEngineException failure = openFailure(config(128, 256), Reason.INVALID_SEGMENT_METADATA);

        assertThat(failure.getCause()).isInstanceOf(SegmentFileDiscoveryException.InvalidLayout.class)
                .hasMessageContaining(link.toString());
        assertThat(Files.readSymbolicLink(link)).isEqualTo(missing);
        assertThat(missing).doesNotExist();
    }

    @Test
    void startupRejectsDuplicateIdsAndPreservesBothFiles() throws IOException {
        createRecord();
        byte[] original = Files.readAllBytes(segment(1));
        Path duplicate = Files.write(directory.resolve("segment_000001.log"), new byte[] {19});

        StorageEngineException failure = openFailure(config(128, 256), Reason.INVALID_SEGMENT_METADATA);

        assertThat(failure.getCause()).isInstanceOf(SegmentFileDiscoveryException.InvalidLayout.class)
                .hasMessageContaining("Duplicate segment ID 1")
                .hasMessageContaining(segment(1).toString())
                .hasMessageContaining(duplicate.toString());
        assertThat(Files.readAllBytes(segment(1))).containsExactly(original);
        assertThat(Files.readAllBytes(duplicate)).containsExactly(19);
        assertThat(segmentCount(directory)).isEqualTo(2);
    }

    @Test
    void startupReportsAnUnrepresentableSegmentIdAsInvalidMetadata() throws IOException {
        Files.createFile(directory.resolve("segment_9223372036854775808.log"));

        assertThat(openFailure(config(128, 256), Reason.INVALID_SEGMENT_METADATA))
                .hasRootCauseInstanceOf(NumberFormatException.class);
    }

    @Test
    void opensTheMaximumSegmentIdAndFailsOnlyWhenRolloverNeedsASuccessor() throws IOException {
        createRecord();
        Path last = Files.move(segment(1), segment(Long.MAX_VALUE));
        byte[] replacement = {7, 8};

        byte[] original = Files.readAllBytes(last);
        StorageConfig smallSegments = config(3, 30);
        try (KeyValueStore store = new KeyValueStore(smallSegments)) {
            assertValue(store, KEY, VALUE);
            StorageEngineException failure = assertFailure(() -> store.put(KEY, replacement), Operation.PUT,
                    Reason.SEGMENT_ID_EXHAUSTED, MutationOutcome.NOT_APPENDED);
            assertThat(failure)
                    .hasRootCauseMessage("Segment IDs exhausted at " + Long.MAX_VALUE + " in " + directory);
            assertThat(Files.readAllBytes(last)).containsExactly(original);
            assertValue(store, KEY, VALUE);
            store.put(OTHER_KEY, new byte[0]);
            assertValue(store, OTHER_KEY, new byte[0]);
            assertThat(Files.size(last)).isEqualTo(30);
        }
        try (var files = Files.list(directory)) {
            assertThat(files).containsExactlyInAnyOrder(last, directory.resolve("LOCK"));
        }
        try (KeyValueStore store = new KeyValueStore(smallSegments)) {
            assertValue(store, KEY, VALUE);
            assertValue(store, OTHER_KEY, new byte[0]);
        }
    }

    @Test
    void indexedCorruptionIsDistinctFromAMissAndAnEmptyValue() throws IOException {
        try (KeyValueStore store = new KeyValueStore(config(128, 256))) {
            store.put(KEY, VALUE);
            store.put(OTHER_KEY, new byte[0]);
            overwriteByte(segment(1), HEADER_SIZE, 99);

            assertThat(store.get(new byte[] {5})).isEmpty();
            assertValue(store, OTHER_KEY, new byte[0]);
            StorageEngineException failure = assertFailure(() -> store.get(KEY), Operation.GET,
                    Reason.CHECKSUM_CORRUPTION, MutationOutcome.NOT_APPLICABLE);
            assertThat(failure).hasRootCauseInstanceOf(DataCorruptionException.class);
            assertFailed(store, failure, KEY);
        }
    }

    @Test
    void indexedReadFailsStopOnInvalidRecordStructure() throws IOException {
        try (KeyValueStore store = new KeyValueStore(config(128, 256))) {
            store.put(KEY, VALUE);
            overwriteByte(segment(1), FormatInfo.CHECKSUM_FIELD_SIZE, 99);

            StorageEngineException failure = assertFailure(() -> store.get(KEY), Operation.GET,
                    Reason.STRUCTURAL_CORRUPTION, MutationOutcome.NOT_APPLICABLE);
            assertThat(failure).hasRootCauseInstanceOf(TypeDecodingException.class);
            assertFailed(store, failure, KEY);
        }
    }

    @Test
    void indexedReadFailsStopOnIncompletePayload() throws IOException {
        assertIncompleteIndexedRecord(HEADER_SIZE + 1, Section.PAYLOAD, 1);
    }

    @Test
    void indexedReadFailsStopWhenAnExpectedRecordStartsAtEof() throws IOException {
        assertIncompleteIndexedRecord(0, Section.HEADER, 0);
    }

    private void assertIncompleteIndexedRecord(int retainedBytes, Section section, int available) throws IOException {
        try (KeyValueStore store = new KeyValueStore(config(128, 256))) {
            store.put(OTHER_KEY, new byte[0]);
            long recordOffset = Files.size(segment(1));
            store.put(KEY, VALUE);
            truncateActive(recordOffset + retainedBytes);

            StorageEngineException failure = assertFailure(() -> store.get(KEY), Operation.GET,
                    Reason.INCOMPLETE_RECORD, MutationOutcome.NOT_APPLICABLE);
            assertThat(failure.getCause().getCause()).isInstanceOfSatisfying(IncompleteRecordException.class, cause -> {
                assertThat(cause.recordOffset()).isEqualTo(recordOffset);
                assertThat(cause.section()).isEqualTo(section);
                assertThat(cause.availableBytes()).isEqualTo(available);
            });
            assertFailed(store, failure, KEY);
        }
    }

    @Test
    void indexedReadFailsStopWhenAnExpectedRecordStartsBeyondEof() throws IOException {
        try (KeyValueStore store = new KeyValueStore(config(128, 256))) {
            store.put(OTHER_KEY, new byte[0]);
            long recordOffset = Files.size(segment(1));
            store.put(KEY, VALUE);
            truncateActive(recordOffset - 1);

            StorageEngineException failure = assertFailure(() -> store.get(KEY), Operation.GET,
                    Reason.INVALID_RECORD_LOCATION, MutationOutcome.NOT_APPLICABLE);
            assertThat(failure.getCause().getCause()).isInstanceOfSatisfying(RecordIOException.InvalidLocation.class,
                    cause -> assertThat(cause.recordOffset()).isEqualTo(recordOffset));
            assertThat(failure).hasRootCauseInstanceOf(ReadRequest.BeyondEnd.class);
            assertFailed(store, failure, KEY);
        }
    }

    private void truncateActive(long extent) throws IOException {
        // Deliberate external modification models corruption of bytes expected by the live index.
        try (FileChannel channel = FileChannel.open(segment(1), WRITE)) {
            channel.truncate(extent);
        }
    }

    @Test
    void readsHistoricalOffsetsBeyondAReducedRolloverThreshold() throws IOException {
        long reducedThreshold = 77;
        byte[] firstValue = new byte[63];
        byte[] emptyValueKey = {5};
        byte[] newKey = {6};
        try (KeyValueStore store = new KeyValueStore(config(64, 256))) {
            store.put(OTHER_KEY, firstValue);
            store.put(emptyValueKey, new byte[0]);
            assertThat(Files.size(segment(1))).isGreaterThan(reducedThreshold);
            store.put(KEY, VALUE);
        }
        StorageConfig reduced = config(64, reducedThreshold);
        byte[] previous = Files.readAllBytes(segment(1));
        try (KeyValueStore store = new KeyValueStore(reduced)) {
            assertThat(segmentCount(directory)).isEqualTo(1);
            assertThat(Files.readAllBytes(segment(1))).containsExactly(previous);
            assertValue(store, KEY, VALUE);
            assertValue(store, OTHER_KEY, firstValue);
            assertValue(store, emptyValueKey, new byte[0]);

            store.put(newKey, VALUE);
            assertThat(segmentCount(directory)).isEqualTo(2);
            assertThat(Files.readAllBytes(segment(1))).containsExactly(previous);
            assertThat(Files.size(segment(2))).isEqualTo(HEADER_SIZE + newKey.length + VALUE.length);
            assertValue(store, KEY, VALUE);
            assertValue(store, newKey, VALUE);
        }
        try (KeyValueStore store = new KeyValueStore(reduced)) {
            assertValue(store, KEY, VALUE);
            assertValue(store, emptyValueKey, new byte[0]);
            assertValue(store, newKey, VALUE);
        }
    }

    @Test
    void mismatchedRecordInNewestApplicableImmutableSegmentStopsLookup() throws IOException {
        byte[] latest = {7, 8};
        try (KeyValueStore store = new KeyValueStore(config(3, 16))) {
            store.put(KEY, VALUE);
            assertValue(store, KEY, VALUE);
            store.put(KEY, latest);
            store.put(OTHER_KEY, VALUE);
            assertThat(segmentCount(directory)).isEqualTo(3);
            assertValue(store, KEY, latest);
            assertValue(store, OTHER_KEY, VALUE);

            // Substitute complete, checksum-valid bytes while the index still points to this file.
            Files.write(segment(2), Files.readAllBytes(segment(3)));

            StorageEngineException failure = assertFailure(() -> store.get(KEY), Operation.GET,
                    Reason.INVALID_INDEX_REFERENCE, MutationOutcome.NOT_APPLICABLE);
            assertThat(failure.getCause()).hasMessageContaining(segment(2).toString());
            assertThat(failure.getCause().getCause()).isInstanceOfSatisfying(IndexConsistencyException.class,
                    cause -> assertThat(cause.recordOffset()).isZero());
            assertFailed(store, failure, KEY);
            assertFailed(store, failure, OTHER_KEY);
        }
    }

    @Test
    void indexedReadFailsStopWhenAPresentReferenceIdentifiesAValidTombstone() throws IOException {
        Path fixture = Files.createTempDirectory(directory, "tombstone");
        try (KeyValueStore writer = new KeyValueStore(KeyValueStoreTestSupport.config(fixture, 256))) {
            writer.delete(KEY);
        }
        try (KeyValueStore store = new KeyValueStore(config(128, 256))) {
            store.put(KEY, VALUE);
            // Replace a normal record with a checksum-valid tombstone without changing the live index.
            Files.write(segment(1), Files.readAllBytes(fixture.resolve(segment(1).getFileName())));
            StorageEngineException failure = assertFailure(() -> store.get(KEY), Operation.GET,
                    Reason.INVALID_INDEX_REFERENCE, MutationOutcome.NOT_APPLICABLE);
            assertThat(failure.getCause().getCause()).isInstanceOfSatisfying(IndexConsistencyException.class,
                    cause -> assertThat(cause).hasMessageContaining("TOMBSTONE"));
            assertFailed(store, failure, KEY);
        }
    }

    @Test
    void indexedReadFailsStopWhenTheRecordExceedsTheConfiguredReadLimit() throws IOException {
        Path fixture = Files.createTempDirectory(directory, "large-record");
        try (KeyValueStore writer = new KeyValueStore(KeyValueStoreTestSupport.config(fixture, 256))) {
            writer.put(KEY, new byte[8]);
        }
        try (KeyValueStore store = new KeyValueStore(config(3, 256))) {
            store.put(KEY, VALUE);
            Files.write(segment(1), Files.readAllBytes(fixture.resolve(segment(1).getFileName())));
            StorageEngineException failure = assertFailure(() -> store.get(KEY), Operation.GET,
                    Reason.INCOMPATIBLE_RECORD_SIZE, MutationOutcome.NOT_APPLICABLE);
            assertThat(failure.getCause().getCause()).isInstanceOf(RecordIOException.ReadLimitExceeded.class);
            assertThat(failure).hasRootCauseInstanceOf(PayloadLimitExceededException.class);
            assertFailed(store, failure, KEY);
        }
    }

    @Test
    void unreadableActiveRecordStopsLookupBeforeAnOlderValue() throws IOException {
        byte[] latest = {7};
        try (KeyValueStore store = new KeyValueStore(config(3, 16))) {
            store.put(KEY, VALUE);
            assertValue(store, KEY, VALUE);
            store.put(KEY, latest);
            assertThat(segmentCount(directory)).isEqualTo(2);
            assertValue(store, KEY, latest);

            overwriteByte(segment(2), HEADER_SIZE, 99);

            StorageEngineException failure = assertFailure(() -> store.get(KEY), Operation.GET,
                    Reason.CHECKSUM_CORRUPTION, MutationOutcome.NOT_APPLICABLE);
            assertThat(failure.getCause()).hasMessageContaining(segment(2).toString());
            assertThat(failure).hasRootCauseInstanceOf(DataCorruptionException.class);
            assertFailed(store, failure, KEY);
        }
    }

    @Test
    void rolloverCreationConflictKeepsBothFilesAndSkipsTheIncomingPut() throws IOException {
        StorageConfig config = config(3, 16);
        try (KeyValueStore store = new KeyValueStore(config)) {
            store.put(KEY, VALUE);
            byte[] original = Files.readAllBytes(segment(1));
            Files.write(segment(2), original);

            StorageEngineException failure = assertFailure(() -> store.put(OTHER_KEY, VALUE), Operation.PUT,
                    Reason.STORAGE_IO, MutationOutcome.NOT_APPENDED);
            assertThat(failure).hasRootCauseInstanceOf(FileAlreadyExistsException.class);
            assertFailed(store, failure, KEY);
            assertThat(Files.readAllBytes(segment(1))).containsExactly(original);
            assertThat(Files.readAllBytes(segment(2))).containsExactly(original);
            assertThat(segmentCount(directory)).isEqualTo(2);
        }
        try (KeyValueStore store = new KeyValueStore(config)) {
            assertValue(store, KEY, VALUE);
            assertThat(store.get(OTHER_KEY)).isEmpty();
        }
    }

    @Test
    void retiredSegmentLookupStillDetectsCorruption() throws IOException {
        try (KeyValueStore store = new KeyValueStore(config(19, 32))) {
            store.put(KEY, VALUE);
            store.put(OTHER_KEY, VALUE);
            overwriteByte(segment(1), HEADER_SIZE, 99);

            StorageEngineException failure = assertFailure(() -> store.get(KEY), Operation.GET,
                    Reason.CHECKSUM_CORRUPTION, MutationOutcome.NOT_APPLICABLE);
            assertThat(failure).hasRootCauseInstanceOf(DataCorruptionException.class);
            assertThat(failure.getCause()).hasMessageContaining(segment(1).toString());
            assertFailed(store, failure, OTHER_KEY);
        }
    }

    private static void assertIncomplete(StorageEngineException failure, Section section, int expected, int available) {
        assertThat(failure.getCause().getCause().getCause().getCause())
                .isInstanceOfSatisfying(IncompleteRecordException.class, cause -> {
                    assertThat(cause.section()).isEqualTo(section);
                    assertThat(cause.recordOffset()).isZero();
                    assertThat(cause.expectedBytes()).isEqualTo(expected);
                    assertThat(cause.availableBytes()).isEqualTo(available);
                });
    }

    private StorageEngineException openFailure(StorageConfig config, Reason reason) {
        return assertFailure(() -> {
            try (KeyValueStore ignored = new KeyValueStore(config)) {
                throw new AssertionError("Store opened with unreadable history");
            }
        }, Operation.OPEN, reason, MutationOutcome.NOT_APPLICABLE);
    }

    private void createRecord() {
        try (KeyValueStore store = new KeyValueStore(config(128, 256))) {
            store.put(KEY, VALUE);
        }
    }

    private Path segment(long id) {
        return directory.resolve("segment_%019d.log".formatted(id));
    }

    private StorageConfig config(long payloadLimit, long segmentLimit) {
        return new StorageConfig(segmentLimit, payloadLimit, directory, DurabilityMode.SYNC_EACH_WRITE);
    }

    private static void overwriteByte(Path path, long offset, int value) throws IOException {
        try (FileChannel channel = FileChannel.open(path, WRITE)) {
            assertThat(channel.write(ByteBuffer.wrap(new byte[] {(byte) value}), offset)).isEqualTo(1);
        }
    }
}
