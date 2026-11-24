package com.ryszardzmija.shaledb.storage.serialization.io;

import com.ryszardzmija.shaledb.storage.durability.FileSystemSync;
import com.ryszardzmija.shaledb.storage.serialization.record.RecordPayload;
import com.ryszardzmija.shaledb.storage.serialization.record.RecordType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;

import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.READ;
import static org.assertj.core.api.Assertions.assertThat;

class RecordSerializationTest {
    // Little-endian CRC32C, type, little-endian key and value lengths, key, value.
    private static final String NORMAL_RECORD_HEX = "dd057e93" + "00" + "03000000" + "02000000" + "0080ff" + "c328";
    private static final String TOMBSTONE_RECORD_HEX = "0166497c" + "01" + "02000000" + "00000000" + "dead";
    private static final int MAX_PAYLOAD_SIZE = 1024;

    @TempDir
    Path tempDir;

    @Test
    void writesNormalRecordInExistingBinaryFormat() throws IOException {
        RecordPayload payload = new RecordPayload(hex("0080ff"), hex("c328"));

        Path path = writeRecord(payload, RecordType.NORMAL);

        assertThat(Files.readAllBytes(path)).containsExactly(hex(NORMAL_RECORD_HEX));
    }

    @Test
    void writesTombstoneRecordInExistingBinaryFormat() throws IOException {
        RecordPayload payload = RecordPayload.forTombstone(hex("dead"));

        Path path = writeRecord(payload, RecordType.TOMBSTONE);

        assertThat(Files.readAllBytes(path)).containsExactly(hex(TOMBSTONE_RECORD_HEX));
    }

    @Test
    void readsNormalRecordFromKnownBytes() throws IOException {
        Path path = Files.write(tempDir.resolve("normal.data"), hex(NORMAL_RECORD_HEX));

        ReadResult result = readFirstRecord(path, MAX_PAYLOAD_SIZE);

        assertRecord(result, RecordType.NORMAL, hex("0080ff"), hex("c328"), 18);
    }

    @Test
    void readsTombstoneRecordFromKnownBytes() throws IOException {
        Path path = Files.write(tempDir.resolve("tombstone.data"), hex(TOMBSTONE_RECORD_HEX));

        ReadResult result = readFirstRecord(path, MAX_PAYLOAD_SIZE);

        assertRecord(result, RecordType.TOMBSTONE, hex("dead"), new byte[0], 15);
    }

    @Test
    void roundTripsEveryByteValueInKeyAndValue() throws IOException {
        byte[] key = new byte[256];
        byte[] value = new byte[256];
        for (int index = 0; index < key.length; index++) {
            key[index] = (byte) index;
            value[index] = (byte) (255 - index);
        }
        Path path = writeRecord(new RecordPayload(key, value), RecordType.NORMAL);

        ReadResult result = readFirstRecord(path, MAX_PAYLOAD_SIZE);

        assertRecord(result, RecordType.NORMAL, key, value, 13 + 256 + 256);
    }

    @Test
    void roundTripsSingleByteKeyAndValue() throws IOException {
        byte[] key = hex("00");
        byte[] value = hex("ff");
        Path path = writeRecord(new RecordPayload(key, value), RecordType.NORMAL);

        ReadResult result = readFirstRecord(path, MAX_PAYLOAD_SIZE);

        assertRecord(result, RecordType.NORMAL, key, value, 15);
    }

    @Test
    void roundTripsPayloadAtConfiguredMaximum() throws IOException {
        byte[] key = hex("010203");
        byte[] value = hex("0405060708");
        Path path = writeRecord(new RecordPayload(key, value), RecordType.NORMAL);

        ReadResult result = readFirstRecord(path, 8);

        assertRecord(result, RecordType.NORMAL, key, value, 21);
    }

    @Test
    void readsConsecutiveRecordsOfDifferentSizesByOffset() throws IOException {
        byte[] firstKey = hex("01");
        byte[] firstValue = hex("101112");
        byte[] secondKey = hex("0203");
        byte[] emptyValue = new byte[0];
        RecordPayload firstPayload = new RecordPayload(firstKey, firstValue);
        RecordPayload secondPayload = new RecordPayload(secondKey, emptyValue);
        RecordPayload tombstone = RecordPayload.forTombstone(firstKey);
        Path path = tempDir.resolve("consecutive.data");

        try (FileChannel channel = FileChannel.open(path, CREATE_NEW, APPEND)) {
            RecordWriter writer = new RecordWriter(channel, new FileSystemSync());

            WriteResult first = writer.write(new WriteRequest(firstPayload, RecordType.NORMAL));
            WriteResult second = writer.write(new WriteRequest(secondPayload, RecordType.NORMAL));
            WriteResult third = writer.write(new WriteRequest(tombstone, RecordType.TOMBSTONE));

            assertThat(first.writeOffset()).isZero();
            assertThat(second.writeOffset()).isEqualTo(17);
            assertThat(third.writeOffset()).isEqualTo(32);
        }

        try (FileChannel channel = FileChannel.open(path, READ)) {
            RecordReader reader = new RecordReader(channel, MAX_PAYLOAD_SIZE);

            assertRecord(reader.read(new ReadRequest(32)), RecordType.TOMBSTONE, firstKey, emptyValue, 46);
            assertRecord(reader.read(new ReadRequest(0)), RecordType.NORMAL, firstKey, firstValue, 17);
            assertRecord(reader.read(new ReadRequest(17)), RecordType.NORMAL, secondKey, emptyValue, 32);
            assertThat(channel.size()).isEqualTo(46);
        }
    }

    private Path writeRecord(RecordPayload payload, RecordType type) throws IOException {
        Path path = tempDir.resolve("record.data");
        try (FileChannel channel = FileChannel.open(path, CREATE_NEW, APPEND)) {
            new RecordWriter(channel, new FileSystemSync()).write(new WriteRequest(payload, type));
        }
        return path;
    }

    private static ReadResult readFirstRecord(Path path, long maxPayloadSize) throws IOException {
        try (FileChannel channel = FileChannel.open(path, READ)) {
            return new RecordReader(channel, maxPayloadSize).read(new ReadRequest(0));
        }
    }

    private static void assertRecord(ReadResult result, RecordType type, byte[] key, byte[] value, long nextOffset) {
        assertThat(result.recordType()).isEqualTo(type);
        assertThat(result.recordPayload().key()).containsExactly(key);
        assertThat(result.recordPayload().value()).containsExactly(value);
        assertThat(result.nextRecordOffset()).isEqualTo(nextOffset);
    }

    private static byte[] hex(String bytes) {
        return HexFormat.of().parseHex(bytes);
    }
}
