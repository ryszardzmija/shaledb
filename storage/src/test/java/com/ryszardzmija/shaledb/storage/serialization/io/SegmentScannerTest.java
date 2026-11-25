package com.ryszardzmija.shaledb.storage.serialization.io;

import com.ryszardzmija.shaledb.storage.durability.FileSystemSync;
import com.ryszardzmija.shaledb.storage.serialization.record.RecordPayload;
import com.ryszardzmija.shaledb.storage.serialization.record.RecordType;
import com.ryszardzmija.shaledb.storage.serialization.spec.FormatInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SegmentScannerTest {
    private static final long MAX_PAYLOAD = 64;
    private static final byte[] KEY = {0, -1};
    private static final byte[] VALUE = {-128, 4};

    @TempDir
    Path directory;

    @Test
    void emptyFileReachesExactEofWithoutDeliveringRecords() throws IOException {
        try (FileChannel channel = openSegment()) {
            var result = new SegmentScanner(channel, MAX_PAYLOAD).scan((record, offset) -> {
                throw new AssertionError("Empty segment delivered a record");
            });

            assertThat(result).isEqualTo(new SegmentScanner.ScanResult(0, 0, null));
            assertThat(channel.isOpen()).isTrue();
        }
    }

    @Test
    void streamsValuesEmptyValuesAndTombstonesWithTheirPhysicalOffsets() throws IOException {
        byte[] otherKey = {-1, 30};
        List<ReadResult> records = new ArrayList<>();
        List<Long> offsets = new ArrayList<>();
        try (FileChannel channel = openSegment()) {
            appendValue(channel);
            long secondOffset = channel.size();
            writer(channel).write(new WriteRequest(new RecordPayload(otherKey, new byte[0]), RecordType.NORMAL));
            long tombstoneOffset = channel.size();
            writer(channel).write(new WriteRequest(RecordPayload.forTombstone(KEY), RecordType.TOMBSTONE));
            long end = channel.size();

            var result = new SegmentScanner(channel, MAX_PAYLOAD).scan((record, offset) -> {
                records.add(record);
                offsets.add(offset);
            });

            assertThat(offsets).containsExactly(0L, secondOffset, tombstoneOffset);
            assertThat(result).isEqualTo(new SegmentScanner.ScanResult(end, end, null));
            assertThat(records).extracting(ReadResult::nextRecordOffset)
                    .containsExactly(secondOffset, tombstoneOffset, end);
            assertThat(records).extracting(ReadResult::recordType)
                    .containsExactly(RecordType.NORMAL, RecordType.NORMAL, RecordType.TOMBSTONE);
            assertThat(records.get(0).recordPayload().key()).containsExactly(KEY);
            assertThat(records.get(0).recordPayload().value()).containsExactly(VALUE);
            assertThat(records.get(1).recordPayload().key()).containsExactly(otherKey);
            assertThat(records.get(1).recordPayload().value()).isEmpty();
            assertThat(records.get(2).recordPayload().key()).containsExactly(KEY);
            assertThat(records.get(2).recordPayload().value()).isEmpty();
        }
    }

    @Test
    void reportsOnlyTheValidatedPrefixForAnIncompleteTail() throws IOException {
        List<Long> offsets = new ArrayList<>();
        try (FileChannel channel = openSegment()) {
            appendValue(channel);
            long boundary = channel.size();
            appendValue(channel);
            long end = channel.size() - 1;
            channel.truncate(end);

            var result = new SegmentScanner(channel, MAX_PAYLOAD).scan((record, offset) -> offsets.add(offset));

            assertThat(offsets).containsExactly(0L);
            assertThat(result.validSize()).isEqualTo(boundary);
            assertThat(result.fileSize()).isEqualTo(end);
            assertThat(result.incompleteTail()).satisfies(tail -> {
                assertThat(tail.origin()).isEqualTo(IncompleteRecordException.Origin.FILE_EXTENT);
                assertThat(tail.section()).isEqualTo(IncompleteRecordException.Section.PAYLOAD);
                assertThat(tail.recordOffset()).isEqualTo(boundary);
                assertThat(tail.availableBytes()).isEqualTo(KEY.length + VALUE.length - 1);
            });
            assertThat(channel.size()).isEqualTo(end);
        }
    }

    @Test
    void doesNotDeliverAStructurallyCorruptRecord() throws IOException {
        assertCorruptSuffix(FormatInfo.CHECKSUM_FIELD_SIZE, (byte) 99, DataCorruptionException.Kind.STRUCTURE);
    }

    @Test
    void doesNotDeliverAChecksumInvalidRecord() throws IOException {
        assertCorruptSuffix(FormatInfo.getHeaderSize(), (byte) 1, DataCorruptionException.Kind.CHECKSUM);
    }

    @Test
    void treatsUnexpectedTransferEofAsTerminalEvenWithAnIncompleteTail() throws IOException {
        List<Long> offsets = new ArrayList<>();
        try (FileChannel channel = openSegment()) {
            appendValue(channel);
            long boundary = channel.size();
            appendValue(channel);
            appendValue(channel);
            channel.truncate(channel.size() - 1);
            FileChannel reader = mock(FileChannel.class, delegatesTo(channel));
            doReturn(-1).when(reader).read(any(ByteBuffer.class), eq(boundary));

            assertThatThrownBy(() -> new SegmentScanner(reader, MAX_PAYLOAD)
                    .scan((record, offset) -> offsets.add(offset)))
                    .isInstanceOfSatisfying(SegmentScanner.ScanException.class, failure ->
                            assertThat(failure.getCause()).isInstanceOfSatisfying(IncompleteRecordException.class,
                                    cause -> {
                                        assertThat(cause.origin()).isEqualTo(IncompleteRecordException.Origin.TRANSFER_EOF);
                                        assertThat(cause.recordOffset()).isEqualTo(boundary);
                                    }));
            assertThat(offsets).containsExactly(0L);
        }
    }

    @Test
    void rejectsAnExtentChangeAfterDeliveringTheValidatedPrefix() throws IOException {
        List<Long> offsets = new ArrayList<>();
        try (FileChannel channel = openSegment()) {
            appendValue(channel);
            long boundary = channel.size();
            appendValue(channel);
            long extent = channel.size() - 1;
            channel.truncate(extent);
            FileChannel reader = mock(FileChannel.class, delegatesTo(channel));
            doReturn(extent, boundary).when(reader).size();

            assertThatThrownBy(() -> new SegmentScanner(reader, MAX_PAYLOAD)
                    .scan((record, offset) -> offsets.add(offset)))
                    .isInstanceOf(SegmentScanner.ScanException.class)
                    .hasCauseInstanceOf(IOException.class);
            assertThat(offsets).containsExactly(0L);
            assertThat(channel.size()).isEqualTo(extent);
        }
    }

    @Test
    void replayFailureResemblingAnIncompleteTailAbortsWithoutDeliveringLaterRecords() throws IOException {
        var injected = new IncompleteRecordException(IncompleteRecordException.Section.HEADER,
                IncompleteRecordException.Origin.FILE_EXTENT, 0, FormatInfo.getHeaderSize(), 0);
        List<Long> offsets = new ArrayList<>();
        try (FileChannel channel = openSegment()) {
            appendValue(channel);
            appendValue(channel);
            SegmentScanner scanner = new SegmentScanner(channel, MAX_PAYLOAD);

            assertThatThrownBy(() -> scanner.scan((record, offset) -> {
                offsets.add(offset);
                throw injected;
            })).isSameAs(injected);
            assertThat(offsets).containsExactly(0L);
        }
    }

    @Test
    void propagatesConsumerErrors() throws IOException {
        var injected = new AssertionError("replay failed");
        try (FileChannel channel = openSegment()) {
            appendValue(channel);
            SegmentScanner scanner = new SegmentScanner(channel, MAX_PAYLOAD);

            assertThatThrownBy(() -> scanner.scan((record, offset) -> {
                throw injected;
            })).isSameAs(injected);
        }
    }

    @Test
    void ioFailureAfterAValidPrefixAbortsReplayAndPreservesTheCause() throws IOException {
        Path path = directory.resolve("segment.log");
        long secondOffset;
        try (FileChannel channel = FileChannel.open(path, CREATE_NEW, WRITE)) {
            appendValue(channel);
            secondOffset = channel.size();
            appendValue(channel);
        }

        byte[] history = Files.readAllBytes(path);
        IOException readFailure = new IOException("Injected positional read failure");
        try (FileChannel channel = FileChannel.open(path, READ)) {
            FileChannel failingChannel = mock(FileChannel.class);
            when(failingChannel.size()).thenReturn(channel.size());
            when(failingChannel.read(any(ByteBuffer.class), anyLong())).thenAnswer(call ->
                    channel.read(call.getArgument(0), call.<Long>getArgument(1)));
            doThrow(readFailure).when(failingChannel).read(any(ByteBuffer.class), eq(secondOffset));

            List<Long> offsets = new ArrayList<>();
            assertThatThrownBy(() -> new SegmentScanner(failingChannel, MAX_PAYLOAD)
                    .scanComplete((record, offset) -> offsets.add(offset)))
                    .isInstanceOf(SegmentScanner.ScanException.class)
                    .hasCauseInstanceOf(RecordIOException.class)
                    .hasRootCause(readFailure);
            assertThat(offsets).containsExactly(0L);
        }
        assertThat(Files.readAllBytes(path)).containsExactly(history);
    }

    private void assertCorruptSuffix(int positionInRecord, byte changedByte, DataCorruptionException.Kind kind)
            throws IOException {
        List<Long> offsets = new ArrayList<>();
        try (FileChannel channel = openSegment()) {
            appendValue(channel);
            long boundary = channel.size();
            appendValue(channel);
            channel.write(ByteBuffer.wrap(new byte[] {changedByte}), boundary + positionInRecord);

            assertThatThrownBy(() -> new SegmentScanner(channel, MAX_PAYLOAD)
                    .scan((record, offset) -> offsets.add(offset)))
                    .isInstanceOfSatisfying(SegmentScanner.ScanException.class, failure ->
                            assertThat(failure.getCause()).isInstanceOfSatisfying(DataCorruptionException.class,
                                    cause -> {
                                        assertThat(cause.kind()).isEqualTo(kind);
                                        assertThat(cause.recordOffset()).isEqualTo(boundary);
                                    }));
            assertThat(offsets).containsExactly(0L);
        }
    }

    private FileChannel openSegment() throws IOException {
        return FileChannel.open(directory.resolve("segment.log"), CREATE_NEW, READ, WRITE);
    }

    private static void appendValue(FileChannel channel) {
        writer(channel).write(new WriteRequest(new RecordPayload(KEY, VALUE), RecordType.NORMAL));
    }

    private static RecordWriter writer(FileChannel channel) {
        return new RecordWriter(channel, new FileSystemSync(), MAX_PAYLOAD);
    }
}
