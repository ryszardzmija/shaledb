package com.ryszardzmija.shaledb.storage.serialization.io;

import com.ryszardzmija.shaledb.storage.durability.FileSystemSync;
import com.ryszardzmija.shaledb.storage.serialization.io.IncompleteRecordException.Section;
import com.ryszardzmija.shaledb.storage.serialization.record.RecordPayload;
import com.ryszardzmija.shaledb.storage.serialization.record.RecordType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class RecordTransferTest {
    private static final byte[] KEY = {0x00, (byte) 0x80, (byte) 0xff};
    private static final byte[] VALUE = {(byte) 0xc3, 0x28};
    private static final byte[] ENCODED = HexFormat.of().parseHex("dd057e930003000000020000000080ffc328");
    private static final int HEADER_SIZE = 13;
    private static final int MAX_PAYLOAD_SIZE = 64;
    private static final long OFFSET = 23;

    private final FileChannel channel = mock(FileChannel.class);
    private final RecordReader reader = new RecordReader(channel, MAX_PAYLOAD_SIZE);
    private final RecordWriter writer = new RecordWriter(channel, new FileSystemSync(), MAX_PAYLOAD_SIZE);

    @Test
    void fillsHeaderAndPayloadThroughShortReadsAndResetsTheProgressLimit() throws IOException {
        long offset = Long.MAX_VALUE - ENCODED.length;
        when(channel.size()).thenReturn(Long.MAX_VALUE);
        var schedule = Arrays.stream(new int[] {0, 0, 3, 0, 0, 4, 6, 0, 0, 1, 0, 0, 4}).iterator();
        ByteBuffer file = ByteBuffer.wrap(ENCODED);
        when(channel.read(any(ByteBuffer.class), anyLong())).thenAnswer(call -> {
            assertThat(call.<Long>getArgument(1)).isEqualTo(offset + file.position());
            return consume(file, call.getArgument(0), schedule.nextInt());
        });

        ReadResult result = reader.read(new ReadRequest(offset));

        assertThat(result.recordType()).isEqualTo(RecordType.NORMAL);
        assertThat(result.recordPayload().key()).containsExactly(KEY);
        assertThat(result.recordPayload().value()).containsExactly(VALUE);
        assertThat(result.nextRecordOffset()).isEqualTo(Long.MAX_VALUE);
        assertThat(schedule.hasNext()).isFalse();
    }

    @Test
    void abandonsAReadAfterThreeConsecutiveZeroTransfers() throws IOException {
        when(channel.size()).thenReturn(OFFSET + ENCODED.length);
        stubRead(OFFSET, 0, HEADER_SIZE);
        stubRead(OFFSET + HEADER_SIZE, HEADER_SIZE, 2);
        long stalledOffset = OFFSET + HEADER_SIZE + 2;
        when(channel.read(any(ByteBuffer.class), eq(stalledOffset)))
                .thenReturn(0, 0, 0).thenThrow(new AssertionError("Read retried after the progress limit"));

        assertThatExceptionOfType(RecordIOException.NoProgress.class)
                .isThrownBy(() -> reader.read(new ReadRequest(OFFSET)));
        verify(channel, times(3)).read(any(ByteBuffer.class), eq(stalledOffset));
    }

    @Test
    void classifiesEofAfterAShortHeaderReadDespiteAnInitiallySufficientExtent() throws IOException {
        when(channel.size()).thenReturn(OFFSET + ENCODED.length);
        stubRead(OFFSET, 0, 3);
        when(channel.read(any(ByteBuffer.class), eq(OFFSET + 3))).thenReturn(-1);

        assertIncompleteRead(Section.HEADER, HEADER_SIZE, 3);
    }

    @Test
    void classifiesEofAfterAShortPayloadReadDespiteAnInitiallySufficientExtent() throws IOException {
        when(channel.size()).thenReturn(OFFSET + ENCODED.length);
        stubRead(OFFSET, 0, HEADER_SIZE);
        stubRead(OFFSET + HEADER_SIZE, HEADER_SIZE, 2);
        when(channel.read(any(ByteBuffer.class), eq(OFFSET + HEADER_SIZE + 2))).thenReturn(-1);

        assertIncompleteRead(Section.PAYLOAD, 5, 2);
    }

    @Test
    void preservesReadFailureAfterPartialProgress() throws IOException {
        IOException failure = new IOException("injected read failure");
        when(channel.size()).thenReturn(OFFSET + ENCODED.length);
        stubRead(OFFSET, 0, HEADER_SIZE);
        stubRead(OFFSET + HEADER_SIZE, HEADER_SIZE, 2);
        when(channel.read(any(ByteBuffer.class), eq(OFFSET + HEADER_SIZE + 2))).thenThrow(failure);

        assertThatExceptionOfType(RecordIOException.class)
                .isThrownBy(() -> reader.read(new ReadRequest(OFFSET)))
                .withCause(failure);
    }

    @Test
    void rejectsAnOffsetBeyondTheExtentBeforeReading() throws IOException {
        when(channel.size()).thenReturn(10L);

        assertThatIllegalArgumentException().isThrownBy(() -> reader.read(new ReadRequest(Long.MAX_VALUE)));
        verify(channel).size();
        verifyNoMoreInteractions(channel);
    }

    @Test
    void rejectsAHeaderThatWouldEndBeyondLongMaxBeforeReading() throws IOException {
        long offset = Long.MAX_VALUE - HEADER_SIZE + 1;
        when(channel.size()).thenReturn(Long.MAX_VALUE);

        IncompleteRecordException failure = catchThrowableOfType(
                () -> reader.read(new ReadRequest(offset)), IncompleteRecordException.class);

        assertThat(failure.recordOffset()).isEqualTo(offset);
        assertThat(failure.section()).isEqualTo(Section.HEADER);
        assertThat(failure.availableBytes()).isEqualTo(HEADER_SIZE - 1);
        verify(channel).size();
        verifyNoMoreInteractions(channel);
    }

    @Test
    void rejectsAPayloadThatWouldEndBeyondLongMaxBeforeReadingIt() throws IOException {
        long offset = Long.MAX_VALUE - ENCODED.length + 1;
        when(channel.size()).thenReturn(Long.MAX_VALUE);
        stubRead(offset, 0, HEADER_SIZE);

        IncompleteRecordException failure = catchThrowableOfType(
                () -> reader.read(new ReadRequest(offset)), IncompleteRecordException.class);

        assertThat(failure.recordOffset()).isEqualTo(offset);
        assertThat(failure.section()).isEqualTo(Section.PAYLOAD);
        assertThat(failure.availableBytes()).isEqualTo(ENCODED.length - HEADER_SIZE - 1);
        verify(channel).size();
        verify(channel).read(any(ByteBuffer.class), eq(offset));
        verifyNoMoreInteractions(channel);
    }

    @Test
    void appendsExactBytesThroughShortWritesAndResetsTheProgressLimit() throws IOException {
        byte[] history = {7, 8, 9};
        ByteBuffer file = ByteBuffer.allocate(history.length + ENCODED.length);
        file.put(history).rewind();
        when(channel.size()).thenReturn((long) history.length);
        when(channel.position(history.length)).thenAnswer(call -> {
            file.position(history.length);
            return channel;
        });
        var schedule = Arrays.stream(new int[] {0, 0, 4, 0, 0, 10, 0, 0, 4}).iterator();
        when(channel.write(any(ByteBuffer.class))).thenAnswer(call -> {
            ByteBuffer buffer = call.getArgument(0);
            assertThat(file.position()).isEqualTo(history.length + buffer.position());
            int count = schedule.nextInt();
            consume(buffer, file, count);
            return count;
        });

        WriteResult result = writer.write(writeRequest());

        assertThat(result).isEqualTo(new WriteResult(history.length, ENCODED.length));
        assertThat(file.array()).containsExactly(ByteBuffer.allocate(file.capacity()).put(history).put(ENCODED).array());
        assertThat(schedule.hasNext()).isFalse();
        var order = inOrder(channel);
        order.verify(channel).size();
        order.verify(channel).position(history.length);
        order.verify(channel, times(9)).write(any(ByteBuffer.class));
        order.verifyNoMoreInteractions();
    }

    @Test
    void abandonsAWriteAfterThreeConsecutiveZeroTransfers() throws IOException {
        ByteBuffer written = ByteBuffer.allocate(4);
        when(channel.write(any(ByteBuffer.class))).thenAnswer(call -> consume(call.getArgument(0), written, 4))
                .thenReturn(0, 0, 0).thenThrow(new AssertionError("Write retried after the progress limit"));

        assertThatExceptionOfType(RecordIOException.NoProgress.class).isThrownBy(() -> writer.write(writeRequest()));
        assertThat(written.array()).containsExactly(Arrays.copyOf(ENCODED, 4));
        verify(channel, times(4)).write(any(ByteBuffer.class));
    }

    @Test
    void preservesWriteFailureBeforeTransferringAnyBytes() throws IOException {
        IOException failure = new IOException("injected write failure");
        when(channel.write(any(ByteBuffer.class))).thenThrow(failure);

        assertThatExceptionOfType(RecordIOException.class).isThrownBy(() -> writer.write(writeRequest())).withCause(failure);
        verify(channel).write(any(ByteBuffer.class));
    }

    @Test
    void preservesWriteFailureAfterPartialProgressWithoutRestartingTheTransfer() throws IOException {
        IOException failure = new IOException("injected write failure");
        ByteBuffer written = ByteBuffer.allocate(4);
        when(channel.write(any(ByteBuffer.class))).thenAnswer(call -> consume(call.getArgument(0), written, 4))
                .thenThrow(failure);

        assertThatExceptionOfType(RecordIOException.class).isThrownBy(() -> writer.write(writeRequest())).withCause(failure);
        assertThat(written.array()).containsExactly(Arrays.copyOf(ENCODED, 4));
        verify(channel, times(2)).write(any(ByteBuffer.class));
    }

    @Test
    void permitsAnAppendEndingAtLongMax() throws IOException {
        long offset = Long.MAX_VALUE - ENCODED.length;
        when(channel.size()).thenReturn(offset);
        ByteBuffer written = ByteBuffer.allocate(ENCODED.length);
        when(channel.write(any(ByteBuffer.class))).thenAnswer(call -> consume(call.getArgument(0), written, ENCODED.length));

        assertThat(writer.write(writeRequest())).isEqualTo(new WriteResult(offset, ENCODED.length));
        verify(channel).position(offset);
        assertThat(written.array()).containsExactly(ENCODED);
    }

    @Test
    void rejectsAnOverflowingAppendBeforePositioningOrTransferringBytes() throws IOException {
        when(channel.size()).thenReturn(Long.MAX_VALUE - ENCODED.length + 1);

        assertThatExceptionOfType(RecordIOException.class).isThrownBy(() -> writer.write(writeRequest()));
        verify(channel).size();
        verifyNoMoreInteractions(channel);
    }

    private void stubRead(long fileOffset, int dataOffset, int count) throws IOException {
        when(channel.read(any(ByteBuffer.class), eq(fileOffset))).thenAnswer(call -> {
            call.<ByteBuffer>getArgument(0).put(ENCODED, dataOffset, count);
            return count;
        });
    }

    private void assertIncompleteRead(Section section, int expected, int transferred) {
        IncompleteRecordException failure = catchThrowableOfType(
                () -> reader.read(new ReadRequest(OFFSET)), IncompleteRecordException.class);

        assertThat(failure.recordOffset()).isEqualTo(OFFSET);
        assertThat(failure.section()).isEqualTo(section);
        assertThat(failure.expectedBytes()).isEqualTo(expected);
        assertThat(failure.availableBytes()).isEqualTo(transferred);
    }

    private static int consume(ByteBuffer source, ByteBuffer target, int count) {
        byte[] bytes = new byte[count];
        source.get(bytes);
        target.put(bytes);
        return count;
    }

    private static WriteRequest writeRequest() {
        return new WriteRequest(new RecordPayload(KEY, VALUE), RecordType.NORMAL);
    }
}
