package com.ryszardzmija.shaledb.storage.hash.segment.model;

import com.ryszardzmija.shaledb.storage.durability.DurabilityMode;
import com.ryszardzmija.shaledb.storage.durability.FileSystemSync;
import com.ryszardzmija.shaledb.storage.serialization.io.ReadRequest;
import com.ryszardzmija.shaledb.storage.serialization.io.ReadResult;
import com.ryszardzmija.shaledb.storage.serialization.io.RecordIOException;
import com.ryszardzmija.shaledb.storage.serialization.io.RecordReader;
import com.ryszardzmija.shaledb.storage.serialization.io.RecordWriter;
import com.ryszardzmija.shaledb.storage.serialization.io.WriteRequest;
import com.ryszardzmija.shaledb.storage.serialization.io.WriteResult;
import com.ryszardzmija.shaledb.storage.serialization.record.RecordType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SegmentWriterTest {
    private static final long MAX_PAYLOAD = 64;
    private static final byte[] KEY = {0, -1};
    private static final byte[] VALUE = {2, 3, -128};
    private static final byte[] EMPTY = {};

    @TempDir
    Path directory;

    @Test
    void appendsCompleteRecordsAndForcesEachBeforeReturningItsActualLocation() throws IOException {
        try (FileChannel channel = FileChannel.open(directory.resolve("segment.log"), CREATE_NEW, READ, WRITE)) {
            List<Long> forcedExtents = new ArrayList<>();
            FileSystemSync sync = mock(FileSystemSync.class);
            doAnswer(call -> {
                channel.force(true);
                forcedExtents.add(channel.size());
                return null;
            }).when(sync).forceFile(channel);
            SegmentWriter writer = new SegmentWriter(new RecordWriter(channel, sync, MAX_PAYLOAD),
                    DurabilityMode.SYNC_EACH_WRITE);

            WriteResult normal = writer.appendValue(KEY, VALUE);
            long normalEnd = channel.size();
            assertThat(forcedExtents).containsExactly(normalEnd);
            WriteResult empty = writer.appendValue(KEY, EMPTY);
            long emptyEnd = channel.size();
            assertThat(forcedExtents).containsExactly(normalEnd, emptyEnd);
            WriteResult tombstone = writer.appendTombstone(KEY);
            long tombstoneEnd = channel.size();
            assertThat(forcedExtents).containsExactly(normalEnd, emptyEnd, tombstoneEnd);

            RecordReader reader = new RecordReader(channel, MAX_PAYLOAD);
            assertRecord(reader, normal, 0, normalEnd, RecordType.NORMAL, VALUE);
            assertRecord(reader, empty, normalEnd, emptyEnd, RecordType.NORMAL, EMPTY);
            assertRecord(reader, tombstone, emptyEnd, tombstoneEnd, RecordType.TOMBSTONE, EMPTY);
        }
    }

    @Test
    void appendFailureSkipsForceForBothRecordTypes() {
        RecordWriter recordWriter = mock(RecordWriter.class);
        SegmentWriter writer = new SegmentWriter(recordWriter, DurabilityMode.SYNC_EACH_WRITE);
        RecordIOException failure = new RecordIOException("write failed", new IOException("error"));
        when(recordWriter.write(any(WriteRequest.class))).thenThrow(failure);

        assertThatThrownBy(() -> writer.appendValue(KEY, VALUE)).isSameAs(failure);
        assertThatThrownBy(() -> writer.appendTombstone(KEY)).isSameAs(failure);

        verify(recordWriter, never()).flushToStorage();
    }

    @Test
    void forceFailurePreventsEitherRecordTypeFromReturningAnAppendResult() {
        RecordWriter recordWriter = mock(RecordWriter.class);
        SegmentWriter writer = new SegmentWriter(recordWriter, DurabilityMode.SYNC_EACH_WRITE);
        RecordIOException failure = new RecordIOException("force failed", new IOException("error"));
        when(recordWriter.write(any(WriteRequest.class))).thenReturn(new WriteResult(5, 16));
        doThrow(failure).when(recordWriter).flushToStorage();

        assertThatThrownBy(() -> writer.appendValue(KEY, VALUE)).isSameAs(failure);
        assertThatThrownBy(() -> writer.appendTombstone(KEY)).isSameAs(failure);
        var order = inOrder(recordWriter);
        order.verify(recordWriter).write(any(WriteRequest.class));
        order.verify(recordWriter).flushToStorage();
        order.verify(recordWriter).write(any(WriteRequest.class));
        order.verify(recordWriter).flushToStorage();
        order.verifyNoMoreInteractions();
    }

    private static void assertRecord(RecordReader reader, WriteResult written, long offset, long end,
                                     RecordType type, byte[] value) {
        assertThat(written.writeOffset()).isEqualTo(offset);
        assertThat(written.encodedRecordSize()).isEqualTo(end - offset);
        ReadResult decoded = reader.read(new ReadRequest(written.writeOffset()));
        assertThat(decoded.nextRecordOffset()).isEqualTo(end);
        assertThat(decoded.recordType()).isEqualTo(type);
        assertThat(decoded.recordPayload().key()).containsExactly(KEY);
        assertThat(decoded.recordPayload().value()).containsExactly(value);
    }
}
