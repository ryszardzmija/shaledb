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
import java.util.function.Supplier;

import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.WRITE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class RecordWriterTest {
    private static final long MAX_PAYLOAD_SIZE = 8;

    @TempDir
    Path tempDir;

    @Test
    void rejectsCombinedPayloadOneByteOverTheLimit() throws IOException {
        assertRejectedWithoutAppend(() -> new WriteRequest(new RecordPayload(new byte[4], new byte[5]), RecordType.NORMAL),
                "Record payload size 9 exceeds configured maxPayloadSize 8");
    }

    @Test
    void rejectsOversizedNormalKeyWithAnEmptyValue() throws IOException {
        assertRejectedWithoutAppend(() -> new WriteRequest(new RecordPayload(new byte[9], new byte[0]), RecordType.NORMAL),
                "Record payload size 9 exceeds configured maxPayloadSize 8");
    }

    @Test
    void rejectsEmptyKeyWhenConstructingThePayload() throws IOException {
        assertRejectedWithoutAppend(() -> new WriteRequest(new RecordPayload(new byte[0], new byte[1]), RecordType.NORMAL),
                "Record key must not be null or empty");
    }

    @Test
    void rejectsTombstoneWithAValue() throws IOException {
        assertRejectedWithoutAppend(() -> new WriteRequest(new RecordPayload(new byte[1], new byte[1]), RecordType.TOMBSTONE),
                "Tombstone value length must be zero: 1");
    }

    @Test
    void rejectsOversizedTombstone() throws IOException {
        assertRejectedWithoutAppend(() -> new WriteRequest(RecordPayload.forTombstone(new byte[9]), RecordType.TOMBSTONE),
                "Record payload size 9 exceeds configured maxPayloadSize 8");
    }

    @Test
    void rejectsMissingType() throws IOException {
        assertRejectedWithoutAppend(() -> new WriteRequest(new RecordPayload(new byte[1], new byte[0]), null),
                "Unsupported record type: null");
    }

    @Test
    void rejectsMissingPayload() throws IOException {
        assertRejectedWithoutAppend(() -> new WriteRequest(null, RecordType.NORMAL),
                "Write request and record payload must not be null");
    }

    private void assertRejectedWithoutAppend(Supplier<WriteRequest> request, String expectedMessage) throws IOException {
        Path path = tempDir.resolve("record.data");
        try (FileChannel channel = FileChannel.open(path, CREATE_NEW, WRITE)) {
            RecordWriter writer = new RecordWriter(channel, new FileSystemSync(), MAX_PAYLOAD_SIZE);
            writer.write(new WriteRequest(new RecordPayload(new byte[] {1}, new byte[] {2}), RecordType.NORMAL));
            byte[] before = Files.readAllBytes(path);

            assertThatIllegalArgumentException().isThrownBy(() -> writer.write(request.get()))
                    .withMessage(expectedMessage);

            assertThat(Files.readAllBytes(path)).containsExactly(before);
        }
    }
}
