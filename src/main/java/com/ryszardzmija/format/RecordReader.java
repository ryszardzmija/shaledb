package com.ryszardzmija.format;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Objects;

import static com.ryszardzmija.format.FormatInfo.HEADER_SIZE;

public class RecordReader {
    private final FileChannel readChannel;

    public RecordReader(FileChannel readChannel) {
        this.readChannel = Objects.requireNonNull(readChannel);
    }

    public record RecordRead(Record record, long nextRecordOffset) {}

    public RecordRead read(long offset) throws IOException {
        // TODO: Handle buffer underflow exception
        ByteBuffer headerBuffer = ByteBuffer.allocate(HEADER_SIZE);
        readIntoBuffer(headerBuffer, offset);
        headerBuffer.flip();
        int keyDataSize = headerBuffer.getInt();
        int valueDataSize = headerBuffer.getInt();

        ByteBuffer dataBuffer = ByteBuffer.allocate(keyDataSize + valueDataSize);
        readIntoBuffer(dataBuffer, offset + HEADER_SIZE);
        dataBuffer.flip();

        byte[] keyData = new byte[keyDataSize];
        byte[] valueData = new byte[valueDataSize];
        dataBuffer.get(keyData);
        dataBuffer.get(valueData);

        long nextRecordOffset = offset + HEADER_SIZE + keyDataSize + valueDataSize;

        return new RecordRead(new Record(keyData, valueData), nextRecordOffset);
    }

    private void readIntoBuffer(ByteBuffer buffer, long offset) throws IOException {
        while (buffer.hasRemaining()) {
            readChannel.read(buffer, offset + buffer.position());
        }
    }
}
