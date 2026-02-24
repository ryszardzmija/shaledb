package com.ryszardzmija;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class KeyValueStore implements AutoCloseable {
    private static final int HEADER_SIZE = 8;
    private static final Charset CHARSET = StandardCharsets.UTF_8;

    private final FileChannel writeChannel;
    private final FileChannel readChannel;
    private final Map<String, Long> offsetMap;

    public KeyValueStore(Path logPath) throws IOException {
        this.writeChannel = FileChannel.open(logPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
        this.readChannel = FileChannel.open(logPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.READ);
        this.offsetMap = new HashMap<>();
        createOffsetMap();
    }

    public void put(String key, String value) throws IOException {
        byte[] keyBytes = key.getBytes(CHARSET);
        byte[] valueBytes = value.getBytes(CHARSET);
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_SIZE + keyBytes.length + valueBytes.length);

        buffer.putInt(keyBytes.length);
        buffer.putInt(valueBytes.length);
        buffer.put(keyBytes);
        buffer.put(valueBytes);
        buffer.flip();

        offsetMap.put(key, writeChannel.position());

        writeFromBuffer(buffer);
        writeChannel.force(false);
    }

    public Optional<String> get(String key) throws IOException {
        if (offsetMap.containsKey(key)) {
            long offset = offsetMap.get(key);
            ByteBuffer headerBuffer = ByteBuffer.allocate(HEADER_SIZE);
            readIntoBuffer(headerBuffer, offset);
            headerBuffer.flip();
            int keyDataSize = headerBuffer.getInt();
            int valueDataSize = headerBuffer.getInt();

            ByteBuffer valueDataBuffer = ByteBuffer.allocate(valueDataSize);
            readIntoBuffer(valueDataBuffer, offset + HEADER_SIZE + keyDataSize);

            return Optional.of(new String(valueDataBuffer.array(), CHARSET));
        } else {
            return Optional.empty();
        }
    }

    @Override
    public void close() throws IOException {
        writeChannel.close();
        readChannel.close();
    }

    private void readIntoBuffer(ByteBuffer buffer, long offset) throws IOException {
        while (buffer.hasRemaining()) {
            readChannel.read(buffer, offset + buffer.position());
        }
    }

    private void writeFromBuffer(ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
           int _ = writeChannel.write(buffer);
        }
    }

    private void createOffsetMap() throws IOException {
        long currentOffset = 0;
        ByteBuffer headerBuffer = ByteBuffer.allocate(HEADER_SIZE);
        while (currentOffset < readChannel.size()) {
            readIntoBuffer(headerBuffer, currentOffset);
            headerBuffer.flip();
            int keyDataSize = headerBuffer.getInt();
            int valueDataSize = headerBuffer.getInt();

            ByteBuffer keyDataBuffer = ByteBuffer.allocate(keyDataSize);
            readIntoBuffer(keyDataBuffer, currentOffset + HEADER_SIZE);
            keyDataBuffer.flip();

            String key = new String(keyDataBuffer.array(), CHARSET);
            offsetMap.put(key, currentOffset);

            headerBuffer.clear();
            currentOffset += HEADER_SIZE + keyDataSize + valueDataSize;
        }
    }
}
