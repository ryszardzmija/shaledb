package com.ryszardzmija.shaledb.storage;

import com.ryszardzmija.shaledb.storage.StorageEngineException.MutationOutcome;
import com.ryszardzmija.shaledb.storage.StorageEngineException.Operation;
import com.ryszardzmija.shaledb.storage.StorageEngineException.Reason;
import com.ryszardzmija.shaledb.storage.config.StorageConfig;
import com.ryszardzmija.shaledb.storage.durability.DurabilityMode;
import com.ryszardzmija.shaledb.storage.serialization.spec.FormatInfo;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.CRC32C;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mockStatic;

final class KeyValueStoreTestSupport {
    private static final HexFormat HEX = HexFormat.of();
    private static final Pattern SEGMENT_FILE = Pattern.compile("segment_[0-9]+\\.log");

    private KeyValueStoreTestSupport() {
    }

    static StorageConfig config(Path segmentDir, long maxSegmentSize) {
        return new StorageConfig(maxSegmentSize, 128, segmentDir, DurabilityMode.SYNC_EACH_WRITE);
    }

    static byte[] hex(String value) {
        return HEX.parseHex(value);
    }

    static void assertValue(KeyValueStore store, byte[] key, byte[] expected) {
        String keyDescription = "value for key 0x" + HEX.formatHex(key);
        assertThat(store.get(key)).as(keyDescription).hasValueSatisfying(actual ->
                assertThat(actual).as(keyDescription).containsExactly(expected));
    }

    static long segmentCount(Path segmentDir) throws IOException {
        try (var files = Files.list(segmentDir)) {
            return files.filter(Files::isRegularFile)
                    .filter(file -> SEGMENT_FILE.matcher(file.getFileName().toString()).matches())
                    .count();
        }
    }

    static StorageEngineException assertFailure(
            Runnable action, Operation operation, Reason reason, MutationOutcome outcome) {
        StorageEngineException failure = catchThrowableOfType(action::run, StorageEngineException.class);
        assertThat(failure).isNotNull();
        assertThat(failure.operation()).isEqualTo(operation);
        assertThat(failure.reason()).isEqualTo(reason);
        assertThat(failure.mutationOutcome()).isEqualTo(outcome);
        return failure;
    }

    static void assertFailed(KeyValueStore store, Throwable failure, byte[] key) {
        assertThatIllegalStateException().isThrownBy(() -> store.get(key)).withCause(failure);
        assertThatIllegalStateException().isThrownBy(() -> store.put(key, new byte[0])).withCause(failure);
        assertThatIllegalStateException().isThrownBy(() -> store.delete(key)).withCause(failure);
    }

    static MockedStatic<FileChannel> mockChannels(Path directory) {
        return mockChannels(directory, null);
    }

    static MockedStatic<FileChannel> mockChannels(Path directory, FileChannel directoryChannel) {
        Path lockPath = directory.resolve("LOCK");
        MockedStatic<FileChannel> channels = mockStatic(FileChannel.class);
        channels.when(() -> FileChannel.open(lockPath, WRITE, CREATE, NOFOLLOW_LINKS))
                .thenAnswer(call -> lockPath.getFileSystem().provider().newFileChannel(lockPath,
                        Set.of(WRITE, CREATE, NOFOLLOW_LINKS)));
        channels.when(() -> FileChannel.open(directory, READ)).thenAnswer(call -> directoryChannel != null
                ? directoryChannel : directory.getFileSystem().provider().newFileChannel(directory, Set.of(READ)));
        return channels;
    }

    /** Fixture encoder independent of the production record writer and its format constants. */
    static byte[] encodeRecord(int type, byte[] key, byte[] value) {
        ByteBuffer bytes = ByteBuffer.allocate(13 + key.length + value.length).order(ByteOrder.LITTLE_ENDIAN);
        bytes.putInt(0).put((byte) type).putInt(key.length).putInt(value.length).put(key).put(value);
        var checksum = new CRC32C();
        checksum.update(bytes.array(), 4, bytes.capacity() - 4);
        return bytes.putInt(0, (int) checksum.getValue()).array();
    }

    static byte[] invalidHeader() {
        return ByteBuffer.allocate(FormatInfo.getHeaderSize()).put(FormatInfo.CHECKSUM_FIELD_SIZE, (byte) 99).array();
    }
}
