package com.ryszardzmija.shaledb.storage.serialization.io;

import com.ryszardzmija.shaledb.storage.hash.index.ByteKey;
import com.ryszardzmija.shaledb.storage.hash.index.IndexBuildException;
import com.ryszardzmija.shaledb.storage.hash.index.IndexBuilder;
import com.ryszardzmija.shaledb.storage.serialization.io.IncompleteRecordException.Section;
import com.ryszardzmija.shaledb.storage.serialization.io.RecordIOException.ReadLimitExceeded;
import com.ryszardzmija.shaledb.storage.serialization.spec.FormatInfo.PayloadLimitExceededException;
import com.ryszardzmija.shaledb.storage.serialization.spec.FormatInfo.PayloadLimitExceededException.Limit;
import com.ryszardzmija.shaledb.storage.serialization.spec.TypeDecodingException;
import org.assertj.core.api.ThrowableAssertAlternative;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.CRC32C;

import static java.nio.ByteOrder.LITTLE_ENDIAN;
import static java.nio.file.StandardOpenOption.READ;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class RecordReaderTest {
    private static final int TYPE_OFFSET = 4;
    private static final int KEY_LENGTH_OFFSET = 5;
    private static final int VALUE_LENGTH_OFFSET = 9;
    private static final int HEADER_SIZE = 13;
    private static final int KEY_LENGTH = 3;
    private static final int VALUE_LENGTH = 2;
    private static final long MAX_PAYLOAD_SIZE = 64;

    @TempDir
    Path tempDir;

    @Test
    void rejectsModifiedKeyBytes() throws IOException {
        byte[] bytes = validRecord();
        bytes[HEADER_SIZE] ^= 1;

        assertCorruption(bytes).withMessageContaining("Invalid checksum");
    }

    @Test
    void rejectsModifiedValueBytes() throws IOException {
        byte[] bytes = validRecord();
        bytes[HEADER_SIZE + KEY_LENGTH] ^= 1;

        assertCorruption(bytes).withMessageContaining("Invalid checksum");
    }

    @Test
    void rejectsTypeChangeBetweenValidTypes() throws IOException {
        byte[] bytes = recordWithChecksum(0, 1, 0, new byte[] {7});
        bytes[TYPE_OFFSET] = 1;

        assertCorruption(bytes).withMessageContaining("Invalid checksum");
    }

    @Test
    void rejectsChangedKeyAndValueLengthsWithUnchangedTotalLength() throws IOException {
        byte[] bytes = validRecord();
        ByteBuffer header = ByteBuffer.wrap(bytes).order(LITTLE_ENDIAN);
        // Shift the key/value boundary while keeping the same bytes in the payload.
        header.putInt(KEY_LENGTH_OFFSET, KEY_LENGTH - 1);
        header.putInt(VALUE_LENGTH_OFFSET, VALUE_LENGTH + 1);

        assertCorruption(bytes).withMessageContaining("Invalid checksum");
    }

    @Test
    void rejectsInvalidStoredChecksum() throws IOException {
        byte[] bytes = validRecord();
        bytes[0] ^= 1;

        assertCorruption(bytes).withMessageContaining("Invalid checksum");
    }

    @Test
    void rejectsTruncatedHeader() throws IOException {
        byte[] bytes = Arrays.copyOf(validRecord(), HEADER_SIZE - 1);

        assertIncomplete(readIncomplete(bytes, 0, MAX_PAYLOAD_SIZE), Section.HEADER, 0, HEADER_SIZE, HEADER_SIZE - 1);
    }

    @Test
    void rejectsTruncatedKey() throws IOException {
        byte[] bytes = Arrays.copyOf(validRecord(), HEADER_SIZE + KEY_LENGTH - 1);

        assertIncomplete(readIncomplete(bytes, 0, MAX_PAYLOAD_SIZE),
                Section.PAYLOAD, 0, KEY_LENGTH + VALUE_LENGTH, KEY_LENGTH - 1);
    }

    @Test
    void rejectsTruncatedValue() throws IOException {
        byte[] bytes = Arrays.copyOf(validRecord(), HEADER_SIZE + KEY_LENGTH + VALUE_LENGTH - 1);

        assertIncomplete(readIncomplete(bytes, 0, MAX_PAYLOAD_SIZE),
                Section.PAYLOAD, 0, KEY_LENGTH + VALUE_LENGTH, KEY_LENGTH + VALUE_LENGTH - 1);
    }

    @Test
    void rejectsUnknownRecordTypeWithValidChecksum() throws IOException {
        byte[] bytes = recordWithChecksum(127, 1, 1, new byte[] {7, 8});

        assertCorruption(bytes)
                .withMessageContaining("Error decoding record type")
                .withCauseInstanceOf(TypeDecodingException.class);
    }

    @Test
    void rejectsCombinedPayloadOneByteAboveConfiguredMaximum() throws IOException {
        Path path = Files.write(tempDir.resolve("record.data"), validRecord());

        try (FileChannel channel = FileChannel.open(path, READ)) {
            RecordReader reader = new RecordReader(channel, KEY_LENGTH + VALUE_LENGTH - 1);
            ReadLimitExceeded failure = catchThrowableOfType(() -> reader.read(new ReadRequest(0)), ReadLimitExceeded.class);
            assertReadLimit(failure, Limit.CONFIGURED, 5, 4);
            assertThatExceptionOfType(IndexBuildException.class)
                    .isThrownBy(() -> new IndexBuilder(channel, KEY_LENGTH + VALUE_LENGTH - 1).build())
                    .withCauseInstanceOf(ReadLimitExceeded.class)
                    .withRootCauseInstanceOf(PayloadLimitExceededException.class);
        }
    }

    @Test
    void checksTheConfiguredLimitBeforePayloadExtent() throws IOException {
        Path path = Files.write(tempDir.resolve("record.data"), Arrays.copyOf(validRecord(), HEADER_SIZE));

        try (FileChannel channel = FileChannel.open(path, READ)) {
            assertThatExceptionOfType(ReadLimitExceeded.class)
                    .isThrownBy(() -> new RecordReader(channel, 4).read(new ReadRequest(0)));
        }
    }

    @Test
    void rejectsLargeDeclaredPayloadThatCannotFitInTheFile() throws IOException {
        int declaredPayload = 64 * 1024 * 1024;
        byte[] bytes = recordWithChecksum(0, 1, declaredPayload - 1, new byte[] {7});

        assertIncomplete(readIncomplete(bytes, 0, declaredPayload), Section.PAYLOAD, 0, declaredPayload, 1);
    }

    @Test
    void rejectsOversizedPayloadWhenDeclaredLengthsSumBeyondIntegerRange() throws IOException {
        byte[] bytes = recordWithChecksum(0, Integer.MAX_VALUE, 1, new byte[0]);

        assertImplementationLimit(bytes, MAX_PAYLOAD_SIZE, (long) Integer.MAX_VALUE + 1);
        assertImplementationLimit(bytes, Long.MAX_VALUE, (long) Integer.MAX_VALUE + 1);
    }

    @Test
    void rejectsRepresentablePayloadLengthWithoutRoomForHeader() throws IOException {
        byte[] bytes = recordWithChecksum(0, 1, Integer.MAX_VALUE - 13, new byte[0]);

        assertImplementationLimit(bytes, Integer.MAX_VALUE, (long) Integer.MAX_VALUE - HEADER_SIZE + 1);
    }

    private void assertImplementationLimit(byte[] bytes, long readLimit, long declaredPayloadSize) throws IOException {
        Path path = Files.write(tempDir.resolve("record.data"), bytes);
        try (FileChannel channel = FileChannel.open(path, READ)) {
            RecordReader reader = new RecordReader(channel, readLimit);
            DataCorruptionException failure = catchThrowableOfType(
                    () -> reader.read(new ReadRequest(0)), DataCorruptionException.class);
            assertThat(failure).isNotNull();
            assertThat(failure.recordOffset()).isZero();
            assertThat(failure.kind()).isEqualTo(DataCorruptionException.Kind.STRUCTURE);
            assertThat(failure.getCause()).isInstanceOfSatisfying(PayloadLimitExceededException.class, limit -> {
                assertThat(limit.limit()).isEqualTo(Limit.IMPLEMENTATION);
                assertThat(limit.payloadSize()).isEqualTo(declaredPayloadSize);
                assertThat(limit.maxPayloadSize()).isEqualTo(Integer.MAX_VALUE - HEADER_SIZE);
            });
        }
        assertThat(Files.readAllBytes(path)).containsExactly(bytes);
    }

    private static void assertReadLimit(ReadLimitExceeded failure, Limit limit, long declaredSize, long maximum) {
        assertThat(failure).isNotNull();
        assertThat(failure.recordOffset()).isZero();
        assertThat(failure.getCause()).isInstanceOfSatisfying(PayloadLimitExceededException.class, cause -> {
            assertThat(cause.limit()).isEqualTo(limit);
            assertThat(cause.payloadSize()).isEqualTo(declaredSize);
            assertThat(cause.maxPayloadSize()).isEqualTo(maximum);
        });
    }

    @Test
    void rejectsTombstoneWithValueBytesAndValidChecksum() throws IOException {
        byte[] bytes = recordWithChecksum(1, 1, 1, new byte[] {7, 8});

        assertCorruption(bytes).withMessageContaining("Tombstone value length must be zero: 1");
    }

    @Test
    void rejectsReadAtEndOfFile() throws IOException {
        byte[] bytes = validRecord();

        assertIncomplete(readIncomplete(bytes, bytes.length, MAX_PAYLOAD_SIZE),
                Section.HEADER, bytes.length, HEADER_SIZE, 0);
    }

    @Test
    void rejectsReadBeyondEndOfFile() throws IOException {
        Path path = Files.write(tempDir.resolve("record.data"), validRecord());

        try (FileChannel channel = FileChannel.open(path, READ)) {
            RecordReader reader = new RecordReader(channel, MAX_PAYLOAD_SIZE);
            assertThatIllegalArgumentException().isThrownBy(() -> reader.read(new ReadRequest(channel.size() + 1)))
                    .withMessage("Record offset 19 is beyond file size 18");
        }
    }

    @Test
    void rejectsEveryTruncatedPrefixWithoutChangingFileBytes() throws IOException {
        byte[] record = validRecord();
        for (int size = 0; size < record.length; size++) {
            byte[] prefix = Arrays.copyOf(record, size);
            IncompleteRecordException failure = readIncomplete(prefix, 0, MAX_PAYLOAD_SIZE);
            if (size < HEADER_SIZE) {
                assertIncomplete(failure, Section.HEADER, 0, HEADER_SIZE, size);
            } else {
                assertIncomplete(failure, Section.PAYLOAD, 0, KEY_LENGTH + VALUE_LENGTH, size - HEADER_SIZE);
            }
        }
    }

    @Test
    void scanningStopsNormallyAtAnEmptyFileOrACompleteRecordBoundary() throws IOException {
        Path path = Files.createFile(tempDir.resolve("scan.data"));
        ByteKey key = new ByteKey(new byte[] {1, 2, 3});
        try (FileChannel channel = FileChannel.open(path, READ)) {
            assertThat(new IndexBuilder(channel, MAX_PAYLOAD_SIZE).build().getKeyOffset(key)).isEmpty();
        }

        Files.write(path, validRecord());
        try (FileChannel channel = FileChannel.open(path, READ)) {
            assertThat(new IndexBuilder(channel, MAX_PAYLOAD_SIZE).build().getKeyOffset(key)).contains(0L);
        }
    }

    @Test
    void scanningFailsOnAnIncompleteSuffixAfterCompleteRecordsWithoutChangingFileBytes() throws IOException {
        byte[] first = validRecord();
        byte[] second = recordWithChecksum(1, 1, 0, new byte[] {6});
        int suffixOffset = first.length + second.length;
        for (int suffixSize : new int[] {7, HEADER_SIZE + 1}) {
            byte[] bytes = ByteBuffer.allocate(suffixOffset + suffixSize)
                    .put(first).put(second).put(first, 0, suffixSize).array();
            Path path = Files.write(tempDir.resolve("scan.data"), bytes);

            try (FileChannel channel = FileChannel.open(path, READ)) {
                IndexBuilder builder = new IndexBuilder(channel, MAX_PAYLOAD_SIZE);
                IndexBuildException failure = catchThrowableOfType(builder::build, IndexBuildException.class);
                assertThat(failure).isNotNull();
                if (suffixSize < HEADER_SIZE) {
                    assertThat(failure.getCause()).isInstanceOfSatisfying(IncompleteRecordException.class,
                            cause -> assertIncomplete(cause, Section.HEADER, suffixOffset, HEADER_SIZE, suffixSize));
                } else {
                    assertThat(failure.getCause()).isInstanceOfSatisfying(IncompleteRecordException.class,
                            cause -> assertIncomplete(cause, Section.PAYLOAD, suffixOffset, KEY_LENGTH + VALUE_LENGTH, 1));
                }
            }
            assertThat(Files.readAllBytes(path)).containsExactly(bytes);
        }
    }

    @Test
    void readRequestRejectsNegativeOffset() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ReadRequest(-1))
                .withMessage("Record offset must not be negative: -1");
    }

    @Test
    void rejectsNegativeKeyLengthAsCorruption() throws IOException {
        byte[] bytes = recordWithChecksum(0, -1, 0, new byte[0]);

        assertCorruption(bytes).withMessageContaining("Record key length must be positive: -1");
    }

    @Test
    void rejectsNegativeValueLengthWithPositiveTotalAsCorruption() throws IOException {
        byte[] bytes = recordWithChecksum(0, 2, -1, new byte[] {7});

        assertCorruption(bytes).withMessageContaining("Record value length must not be negative: -1");
    }

    @Test
    void rejectsEmptyKeyWithValidChecksumAsCorruption() throws IOException {
        byte[] bytes = recordWithChecksum(0, 0, 1, new byte[] {7});

        assertCorruption(bytes).withMessageContaining("Record key length must be positive: 0");
    }

    private ThrowableAssertAlternative<DataCorruptionException> assertCorruption(byte[] bytes) throws IOException {
        return assertCorruption(bytes, 0, MAX_PAYLOAD_SIZE);
    }

    private ThrowableAssertAlternative<DataCorruptionException> assertCorruption(
            byte[] bytes, long offset, long maxPayloadSize) throws IOException {
        Path path = Files.write(tempDir.resolve("record.data"), bytes);
        ThrowableAssertAlternative<DataCorruptionException> failure;
        try (FileChannel channel = FileChannel.open(path, READ)) {
            RecordReader reader = new RecordReader(channel, maxPayloadSize);
            ReadRequest request = new ReadRequest(offset);
            failure = assertThatExceptionOfType(DataCorruptionException.class).isThrownBy(() -> reader.read(request));
        }
        assertThat(Files.readAllBytes(path)).containsExactly(bytes);
        return failure;
    }

    private IncompleteRecordException readIncomplete(byte[] bytes, long offset, long maxPayloadSize) throws IOException {
        Path path = Files.write(tempDir.resolve("record.data"), bytes);
        IncompleteRecordException failure;
        try (FileChannel channel = FileChannel.open(path, READ)) {
            RecordReader reader = new RecordReader(channel, maxPayloadSize);
            failure = catchThrowableOfType(() -> reader.read(new ReadRequest(offset)), IncompleteRecordException.class);
        }
        assertThat(Files.readAllBytes(path)).containsExactly(bytes);
        return failure;
    }

    private static void assertIncomplete(
            IncompleteRecordException failure, Section section, long offset, int expected, int available) {
        assertThat(failure).isNotNull();
        assertThat(failure.recordOffset()).isEqualTo(offset);
        assertThat(failure.section()).isEqualTo(section);
        assertThat(failure.expectedBytes()).isEqualTo(expected);
        assertThat(failure.availableBytes()).isEqualTo(available);
    }

    private static byte[] validRecord() {
        return recordWithChecksum(0, KEY_LENGTH, VALUE_LENGTH, new byte[] {1, 2, 3, 4, 5});
    }

    private static byte[] recordWithChecksum(int type, int keyLength, int valueLength, byte[] payload) {
        ByteBuffer record = ByteBuffer.allocate(HEADER_SIZE + payload.length).order(LITTLE_ENDIAN);
        record.position(TYPE_OFFSET);
        record.put((byte) type).putInt(keyLength).putInt(valueLength).put(payload);

        byte[] bytes = record.array();
        CRC32C checksum = new CRC32C();
        checksum.update(bytes, TYPE_OFFSET, bytes.length - TYPE_OFFSET);
        record.putInt(0, (int) checksum.getValue());
        return bytes;
    }
}
