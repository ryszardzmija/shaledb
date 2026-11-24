package com.ryszardzmija.shaledb.storage.hash.segment.model;

import com.ryszardzmija.shaledb.storage.durability.FileSystemSync;
import com.ryszardzmija.shaledb.storage.hash.index.ByteKey;
import com.ryszardzmija.shaledb.storage.hash.index.HashIndex;
import com.ryszardzmija.shaledb.storage.hash.index.Index;
import com.ryszardzmija.shaledb.storage.serialization.io.RecordIOException;
import com.ryszardzmija.shaledb.storage.serialization.io.RecordReader;
import com.ryszardzmija.shaledb.storage.serialization.io.RecordWriter;
import com.ryszardzmija.shaledb.storage.serialization.io.WriteRequest;
import com.ryszardzmija.shaledb.storage.serialization.record.RecordPayload;
import com.ryszardzmija.shaledb.storage.serialization.record.RecordType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.Optional;

import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SegmentReaderTest {
    private static final long MAX_PAYLOAD = 64;
    private static final ByteKey KEY = new ByteKey(new byte[] {0, -1});
    private static final ByteKey OTHER_KEY = new ByteKey(new byte[] {-1, 30});
    private static final byte[] VALUE = {0, -128, 5};

    @TempDir
    Path directory;

    @Test
    void readsNormalRecordsAtZeroAndNonzeroOffsetsIncludingAnEmptyValue() throws IOException {
        Path path = directory.resolve("segment.log");
        try (FileChannel channel = FileChannel.open(path, CREATE_NEW, READ, WRITE)) {
            RecordWriter writer = new RecordWriter(channel, new FileSystemSync(), MAX_PAYLOAD);
            writer.write(new WriteRequest(new RecordPayload(KEY.getData(), VALUE), RecordType.NORMAL));
            long secondOffset = channel.size();
            writer.write(new WriteRequest(new RecordPayload(OTHER_KEY.getData(), new byte[0]), RecordType.NORMAL));

            Index index = new HashIndex();
            index.markPresent(KEY, 0);
            index.markPresent(OTHER_KEY, secondOffset);
            SegmentReader reader = new SegmentReader(new RecordReader(channel, MAX_PAYLOAD), index);

            assertThat(reader.get(KEY)).isInstanceOfSatisfying(LookupResult.Present.class,
                    result -> assertThat(result.value()).containsExactly(VALUE));
            assertThat(reader.get(OTHER_KEY)).isInstanceOfSatisfying(LookupResult.Present.class,
                    result -> assertThat(result.value()).isEmpty());
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
    void rejectsAnInvalidNegativeIndexedOffset() throws IOException {
        Index index = mock(Index.class);
        when(index.getKeyOffset(KEY)).thenReturn(Optional.of(-1L));

        try (FileChannel channel = FileChannel.open(directory.resolve("segment.log"), CREATE_NEW, READ, WRITE)) {
            SegmentReader reader = new SegmentReader(new RecordReader(channel, MAX_PAYLOAD), index);

            assertThatThrownBy(() -> reader.get(KEY)).isInstanceOfSatisfying(RecordIOException.InvalidLocation.class,
                    failure -> assertThat(failure.recordOffset()).isEqualTo(-1));
        }
    }

    private void assertInconsistentReference(WriteRequest request) throws IOException {
        try (FileChannel channel = FileChannel.open(directory.resolve("segment.log"), CREATE_NEW, READ, WRITE)) {
            new RecordWriter(channel, new FileSystemSync(), MAX_PAYLOAD).write(request);
            Index index = new HashIndex();
            index.markPresent(KEY, 0);
            SegmentReader reader = new SegmentReader(new RecordReader(channel, MAX_PAYLOAD), index);

            assertThatThrownBy(() -> reader.get(KEY)).isInstanceOfSatisfying(IndexConsistencyException.class,
                    failure -> assertThat(failure.recordOffset()).isZero());
        }
    }
}
