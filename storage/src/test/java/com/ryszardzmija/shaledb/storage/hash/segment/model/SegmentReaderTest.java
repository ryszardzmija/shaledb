package com.ryszardzmija.shaledb.storage.hash.segment.model;

import com.ryszardzmija.shaledb.storage.durability.FileSystemSync;
import com.ryszardzmija.shaledb.storage.hash.index.ByteKey;
import com.ryszardzmija.shaledb.storage.serialization.io.DataCorruptionException;
import com.ryszardzmija.shaledb.storage.serialization.io.IncompleteRecordException;
import com.ryszardzmija.shaledb.storage.serialization.io.ReadRequest;
import com.ryszardzmija.shaledb.storage.serialization.io.RecordIOException;
import com.ryszardzmija.shaledb.storage.serialization.io.RecordReader;
import com.ryszardzmija.shaledb.storage.serialization.io.RecordWriter;
import com.ryszardzmija.shaledb.storage.serialization.io.WriteRequest;
import com.ryszardzmija.shaledb.storage.serialization.record.RecordPayload;
import com.ryszardzmija.shaledb.storage.serialization.record.RecordType;
import com.ryszardzmija.shaledb.storage.serialization.spec.FormatInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;

import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SegmentReaderTest {
    private static final long MAX_PAYLOAD = 64;
    private static final ByteKey KEY = new ByteKey(new byte[] {0, -1});
    private static final ByteKey OTHER_KEY = new ByteKey(new byte[] {-1, 30});
    private static final byte[] VALUE = {0, -128, 5};
    private static final WriteRequest NORMAL = new WriteRequest(new RecordPayload(KEY.getData(), VALUE),
            RecordType.NORMAL);

    @TempDir
    Path directory;

    @Test
    void readsNormalRecordsAtZeroAndNonzeroOffsetsIncludingAnEmptyValue() throws IOException {
        Path path = directory.resolve("segment.log");
        try (FileChannel channel = FileChannel.open(path, CREATE_NEW, READ, WRITE)) {
            RecordWriter writer = new RecordWriter(channel, new FileSystemSync(), MAX_PAYLOAD);
            writer.write(NORMAL);
            long secondOffset = channel.size();
            writer.write(new WriteRequest(new RecordPayload(OTHER_KEY.getData(), new byte[0]), RecordType.NORMAL));

            SegmentReader reader = new SegmentReader(new RecordReader(channel, MAX_PAYLOAD));

            assertThat(reader.readValue(0, KEY)).containsExactly(VALUE);
            assertThat(reader.readValue(secondOffset, OTHER_KEY)).isEmpty();
        }
    }

    @Test
    void rejectsAValidRecordWithADifferentKeyEvenWhenHashesCollide() throws IOException {
        assertThat(OTHER_KEY.hashCode()).isEqualTo(KEY.hashCode());
        var request = new WriteRequest(new RecordPayload(OTHER_KEY.getData(), VALUE), RecordType.NORMAL);

        assertInconsistentReference(request);
    }

    @Test
    void rejectsAValidTombstoneReferencedByAPresentEntry() throws IOException {
        var request = new WriteRequest(RecordPayload.forTombstone(KEY.getData()), RecordType.TOMBSTONE);

        assertInconsistentReference(request);
    }

    @Test
    void rejectsAnInvalidNegativeOffset() throws IOException {
        try (FileChannel channel = FileChannel.open(directory.resolve("segment.log"), CREATE_NEW, READ, WRITE)) {
            SegmentReader reader = new SegmentReader(new RecordReader(channel, MAX_PAYLOAD));

            assertThatThrownBy(() -> reader.readValue(-1, KEY))
                    .isInstanceOfSatisfying(RecordIOException.InvalidLocation.class,
                    failure -> assertThat(failure.recordOffset()).isEqualTo(-1));
        }
    }

    @Test
    void distinguishesExactEofFromAnOffsetBeyondTheExtent() throws IOException {
        try (FileChannel channel = FileChannel.open(directory.resolve("segment.log"), CREATE_NEW, READ, WRITE)) {
            writeNormal(channel);
            long end = channel.size();
            SegmentReader reader = new SegmentReader(new RecordReader(channel, MAX_PAYLOAD));

            assertThatThrownBy(() -> reader.readValue(end, KEY))
                    .isInstanceOfSatisfying(IncompleteRecordException.class, failure -> {
                        assertThat(failure.recordOffset()).isEqualTo(end);
                        assertThat(failure.section()).isEqualTo(IncompleteRecordException.Section.HEADER);
                        assertThat(failure.origin()).isEqualTo(IncompleteRecordException.Origin.FILE_EXTENT);
                        assertThat(failure.availableBytes()).isZero();
                    });
            assertThatThrownBy(() -> reader.readValue(end + 1, KEY))
                    .isInstanceOfSatisfying(RecordIOException.InvalidLocation.class, failure -> {
                        assertThat(failure.recordOffset()).isEqualTo(end + 1);
                        assertThat(failure).hasCauseInstanceOf(ReadRequest.BeyondEnd.class);
                    });
        }
    }

    @Test
    void rejectsAnIncompletePayload() throws IOException {
        try (FileChannel channel = FileChannel.open(directory.resolve("segment.log"), CREATE_NEW, READ, WRITE)) {
            writeNormal(channel);
            channel.truncate(channel.size() - 1);
            SegmentReader reader = new SegmentReader(new RecordReader(channel, MAX_PAYLOAD));

            assertThatThrownBy(() -> reader.readValue(0, KEY))
                    .isInstanceOfSatisfying(IncompleteRecordException.class, failure -> {
                        assertThat(failure.recordOffset()).isZero();
                        assertThat(failure.section()).isEqualTo(IncompleteRecordException.Section.PAYLOAD);
                        assertThat(failure.origin()).isEqualTo(IncompleteRecordException.Origin.FILE_EXTENT);
                    });
        }
    }

    @Test
    void rejectsStructuralCorruptionAndChecksumCorruption() throws IOException {
        try (FileChannel channel = FileChannel.open(directory.resolve("segment.log"), CREATE_NEW, READ, WRITE)) {
            writeNormal(channel);
            long end = channel.size();
            SegmentReader reader = new SegmentReader(new RecordReader(channel, MAX_PAYLOAD));

            channel.write(ByteBuffer.wrap(new byte[] {99}), FormatInfo.CHECKSUM_FIELD_SIZE);
            assertCorruption(reader, DataCorruptionException.Kind.STRUCTURE);
            channel.write(ByteBuffer.wrap(new byte[] {0}), FormatInfo.CHECKSUM_FIELD_SIZE);
            channel.write(ByteBuffer.wrap(new byte[] {6}), end - 1);
            assertCorruption(reader, DataCorruptionException.Kind.CHECKSUM);
        }
    }

    @Test
    void rejectsARecordThatExceedsTheConfiguredPayloadLimit() throws IOException {
        try (FileChannel channel = FileChannel.open(directory.resolve("segment.log"), CREATE_NEW, READ, WRITE)) {
            writeNormal(channel);
            SegmentReader reader = new SegmentReader(new RecordReader(channel, KEY.getData().length + VALUE.length - 1));

            assertThatThrownBy(() -> reader.readValue(0, KEY))
                    .isInstanceOfSatisfying(RecordIOException.ReadLimitExceeded.class,
                            failure -> assertThat(failure.recordOffset()).isZero());
        }
    }

    @Test
    void propagatesAnOrdinaryReadFailureWithItsOriginalCause() throws IOException {
        FileChannel channel = mock(FileChannel.class);
        IOException injected = new IOException("read failed");
        when(channel.size()).thenReturn((long) FormatInfo.getHeaderSize());
        when(channel.read(any(ByteBuffer.class), eq(0L))).thenThrow(injected);
        SegmentReader reader = new SegmentReader(new RecordReader(channel, MAX_PAYLOAD));

        assertThatThrownBy(() -> reader.readValue(0, KEY))
                .isExactlyInstanceOf(RecordIOException.class)
                .hasCause(injected);
    }

    private static void writeNormal(FileChannel channel) {
        new RecordWriter(channel, new FileSystemSync(), MAX_PAYLOAD).write(NORMAL);
    }

    private static void assertCorruption(SegmentReader reader, DataCorruptionException.Kind kind) {
        assertThatThrownBy(() -> reader.readValue(0, KEY))
                .isInstanceOfSatisfying(DataCorruptionException.class, failure -> {
                    assertThat(failure.recordOffset()).isZero();
                    assertThat(failure.kind()).isEqualTo(kind);
                });
    }

    private void assertInconsistentReference(WriteRequest request) throws IOException {
        try (FileChannel channel = FileChannel.open(directory.resolve("segment.log"), CREATE_NEW, READ, WRITE)) {
            new RecordWriter(channel, new FileSystemSync(), MAX_PAYLOAD).write(request);
            SegmentReader reader = new SegmentReader(new RecordReader(channel, MAX_PAYLOAD));

            assertThatThrownBy(() -> reader.readValue(0, KEY))
                    .isInstanceOfSatisfying(IndexConsistencyException.class,
                            failure -> assertThat(failure.recordOffset()).isZero());
        }
    }
}
