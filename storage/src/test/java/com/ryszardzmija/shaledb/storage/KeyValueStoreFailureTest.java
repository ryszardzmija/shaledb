package com.ryszardzmija.shaledb.storage;

import com.ryszardzmija.shaledb.storage.StorageEngineException.MutationOutcome;
import com.ryszardzmija.shaledb.storage.StorageEngineException.Operation;
import com.ryszardzmija.shaledb.storage.StorageEngineException.Reason;
import com.ryszardzmija.shaledb.storage.config.StorageConfig;
import com.ryszardzmija.shaledb.storage.durability.DurabilityMode;
import com.ryszardzmija.shaledb.storage.hash.index.IndexBuildException;
import com.ryszardzmija.shaledb.storage.hash.segment.loader.SegmentLoadingException;
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
    void startupReportsIncompleteHeaderAndPayloadWithTheirExtents() throws IOException {
        createRecord();
        byte[] complete = Files.readAllBytes(segment(1));

        Files.write(segment(1), Arrays.copyOf(complete, HEADER_SIZE - 1));
        assertIncomplete(openFailure(config(128, 256), Reason.INCOMPLETE_RECORD), Section.HEADER, HEADER_SIZE, HEADER_SIZE - 1);

        Files.write(segment(1), Arrays.copyOf(complete, complete.length - 1));
        assertIncomplete(openFailure(config(128, 256), Reason.INCOMPLETE_RECORD), Section.PAYLOAD, 3, 2);
    }

    @Test
    void distinguishesInvalidConfigurationFromIncompatibleHistoricalData() throws IOException {
        assertThatIllegalArgumentException().isThrownBy(() -> config(0, 256));
        assertThat(segmentCount(directory)).isZero();
        createRecord();

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
    void startupPreservesTheFilesystemCauseOfAnUnopenableSegment() throws IOException {
        Path missing = directory.resolve("missing");
        Files.createSymbolicLink(segment(1), missing);

        StorageEngineException failure = openFailure(config(128, 256), Reason.STORAGE_IO);

        assertThat(failure.getCause()).isInstanceOf(SegmentLoadingException.class);
        assertThat(failure).hasRootCauseInstanceOf(NoSuchFileException.class);
    }

    @Test
    void startupReportsAnUnrepresentableSegmentIdAsInvalidMetadata() throws IOException {
        Files.createFile(directory.resolve("segment_9223372036854775808.log"));

        assertThat(openFailure(config(128, 256), Reason.INVALID_SEGMENT_METADATA))
                .hasRootCauseInstanceOf(NumberFormatException.class);
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
        }
    }

    @Test
    void indexedReadDistinguishesAnIncompleteRecordFromAnOffsetBeyondEof() throws IOException {
        try (KeyValueStore store = new KeyValueStore(config(128, 256))) {
            store.put(OTHER_KEY, new byte[0]);
            long recordOffset = Files.size(segment(1));
            store.put(KEY, VALUE);

            try (FileChannel channel = FileChannel.open(segment(1), WRITE)) {
                channel.truncate(recordOffset + HEADER_SIZE + 1);
                StorageEngineException incomplete = assertFailure(() -> store.get(KEY), Operation.GET,
                        Reason.INCOMPLETE_RECORD, MutationOutcome.NOT_APPLICABLE);
                assertThat(incomplete.getCause().getCause()).isInstanceOfSatisfying(IncompleteRecordException.class,
                        cause -> assertThat(cause.recordOffset()).isEqualTo(recordOffset));

                channel.truncate(recordOffset - 1);
                StorageEngineException invalid = assertFailure(() -> store.get(KEY), Operation.GET,
                        Reason.INVALID_RECORD_LOCATION, MutationOutcome.NOT_APPLICABLE);
                assertThat(invalid.getCause().getCause()).isInstanceOfSatisfying(RecordIOException.InvalidLocation.class,
                        cause -> assertThat(cause.recordOffset()).isEqualTo(recordOffset));
                assertThat(invalid).hasRootCauseInstanceOf(ReadRequest.BeyondEnd.class);
            }
        }
    }

    @Test
    void reportsAnIndexedOffsetRejectedByTheExistingSegmentThresholdCheck() {
        try (KeyValueStore store = new KeyValueStore(config(128, 256))) {
            store.put(OTHER_KEY, new byte[63]);
            store.put(KEY, VALUE);
        }
        try (KeyValueStore store = new KeyValueStore(config(64, 77))) {
            assertFailure(() -> store.get(KEY), Operation.GET, Reason.INVALID_RECORD_LOCATION, MutationOutcome.NOT_APPLICABLE);
        }
    }

    @Test
    void rolloverCreationFailureReportsThatThePutForceCompleted() throws IOException {
        StorageConfig config = config(3, 16);
        try (KeyValueStore store = new KeyValueStore(config)) {
            Files.createFile(segment(2));

            StorageEngineException failure = assertFailure(() -> store.put(KEY, VALUE), Operation.PUT,
                    Reason.STORAGE_IO, MutationOutcome.FORCE_COMPLETED);
            assertThat(failure).hasRootCauseInstanceOf(FileAlreadyExistsException.class);
            assertValue(store, KEY, VALUE);
        }
        try (KeyValueStore store = new KeyValueStore(config)) {
            assertValue(store, KEY, VALUE);
        }
    }

    @Test
    void corruptionDuringRolloverFailsTheMutationAndCleansUpTheReplacementFile() throws IOException {
        try (KeyValueStore store = new KeyValueStore(config(19, 32))) {
            store.put(KEY, VALUE);
            overwriteByte(segment(1), HEADER_SIZE, 99);

            StorageEngineException failure = assertFailure(() -> store.put(OTHER_KEY, VALUE), Operation.PUT,
                    Reason.CHECKSUM_CORRUPTION, MutationOutcome.FORCE_COMPLETED);
            assertThat(failure).hasRootCauseInstanceOf(DataCorruptionException.class);
            assertThat(segment(2)).doesNotExist();
            assertValue(store, OTHER_KEY, VALUE);
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
