package com.ryszardzmija.shaledb.storage.serialization.io;

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
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

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

        assertCorruption(bytes).withMessageContaining("Unexpected end of record data");
    }

    @Test
    void rejectsTruncatedKey() throws IOException {
        byte[] bytes = Arrays.copyOf(validRecord(), HEADER_SIZE + KEY_LENGTH - 1);

        assertCorruption(bytes).withMessageContaining("Unexpected end of record data");
    }

    @Test
    void rejectsTruncatedValue() throws IOException {
        byte[] bytes = Arrays.copyOf(validRecord(), HEADER_SIZE + KEY_LENGTH + VALUE_LENGTH - 1);

        assertCorruption(bytes).withMessageContaining("Unexpected end of record data");
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
        byte[] bytes = validRecord();

        assertCorruption(bytes, 0, KEY_LENGTH + VALUE_LENGTH - 1)
                .withMessageContaining("Record payload size 5 exceeds configured maxPayloadSize 4");
    }

    @Test
    void rejectsOversizedPayloadWhenDeclaredLengthsSumBeyondIntegerRange() throws IOException {
        byte[] bytes = recordWithChecksum(0, Integer.MAX_VALUE, 1, new byte[0]);

        assertCorruption(bytes).withMessageContaining("representable range");
        assertCorruption(bytes, 0, Long.MAX_VALUE)
                .withMessageContaining("2147483648")
                .withMessageContaining("representable range");
    }

    @Test
    void rejectsRepresentablePayloadLengthWithoutRoomForHeader() throws IOException {
        byte[] bytes = recordWithChecksum(0, 1, Integer.MAX_VALUE - 13, new byte[0]);

        assertCorruption(bytes, 0, Integer.MAX_VALUE)
                .withMessageContaining("2147483635")
                .withMessageContaining("representable range");
    }

    @Test
    void rejectsTombstoneWithValueBytesAndValidChecksum() throws IOException {
        byte[] bytes = recordWithChecksum(1, 1, 1, new byte[] {7, 8});

        assertCorruption(bytes).withMessageContaining("Tombstone value length must be zero: 1");
    }

    @Test
    void rejectsReadAtEndOfFile() throws IOException {
        byte[] bytes = validRecord();

        assertCorruption(bytes, bytes.length, MAX_PAYLOAD_SIZE)
                .withMessage("Data corruption detected at offset 18: Unexpected end of record data");
    }

    @Test
    void rejectsReadBeyondEndOfFile() throws IOException {
        byte[] bytes = validRecord();

        assertCorruption(bytes, bytes.length + 1L, MAX_PAYLOAD_SIZE)
                .withMessage("Data corruption detected at offset 19: Unexpected end of record data");
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
        try (FileChannel channel = FileChannel.open(path, READ)) {
            RecordReader reader = new RecordReader(channel, maxPayloadSize);
            ReadRequest request = new ReadRequest(offset);
            return assertThatExceptionOfType(DataCorruptionException.class).isThrownBy(() -> reader.read(request));
        }
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
