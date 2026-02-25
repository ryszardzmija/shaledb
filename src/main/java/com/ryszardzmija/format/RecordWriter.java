package com.ryszardzmija.format;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Objects;

public class RecordWriter {
    private final FileChannel writeChannel;

    public RecordWriter(FileChannel writeChannel) {
        this.writeChannel = Objects.requireNonNull(writeChannel);
    }

    public record WriteResult(long writeOffset) {}

    public WriteResult write(Record record) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(FormatInfo.HEADER_SIZE + record.key().length + record.value().length);
        buffer.putInt(record.key().length);
        buffer.putInt(record.value().length);
        buffer.put(record.key());
        buffer.put(record.value());
        buffer.flip();

        WriteResult result = new WriteResult(writeChannel.position());
        writeFromBuffer(buffer);
        writeChannel.force(false);

        return result;
    }

    private void writeFromBuffer(ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            int _ = writeChannel.write(buffer);
        }
    }
}
