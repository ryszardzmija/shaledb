package com.ryszardzmija.shaledb.storage.serialization.io;

import com.ryszardzmija.shaledb.storage.durability.DurabilityException;
import com.ryszardzmija.shaledb.storage.durability.FileSystemSync;
import com.ryszardzmija.shaledb.storage.serialization.record.RecordPayload;
import com.ryszardzmija.shaledb.storage.serialization.record.RecordType;
import com.ryszardzmija.shaledb.storage.serialization.spec.FormatInfo;
import com.ryszardzmija.shaledb.storage.serialization.spec.RecordTypeCodec;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Objects;
import java.util.zip.CRC32C;
import java.util.zip.Checksum;

import static com.ryszardzmija.shaledb.storage.serialization.io.RecordIOException.MAX_CONSECUTIVE_ZERO_TRANSFERS;

public class RecordWriter {
    private final FileChannel writeChannel;
    private final FileSystemSync fileSystemSync;
    private final long maxPayloadSize;

    public RecordWriter(FileChannel writeChannel, FileSystemSync fileSystemSync, long maxPayloadSize) {
        this.writeChannel = Objects.requireNonNull(writeChannel);
        this.fileSystemSync = Objects.requireNonNull(fileSystemSync);
        this.maxPayloadSize = maxPayloadSize;
    }

    /**
     * Appends all encoded bytes before returning their starting offset and size.
     * Calls require serialized access to the file. A transfer failure can leave a partial record.
     * Durability requires a separate flush.
     */
    public WriteResult write(WriteRequest request) {
        if (request == null || request.payload() == null) {
            throw new IllegalArgumentException("Write request and record payload must not be null");
        }
        RecordPayload recordPayload = request.payload();
        RecordType recordType = request.type();
        int keyLength = recordPayload.key().length;
        int valueLength = recordPayload.value().length;
        int payloadSize = FormatInfo.getPayloadSize(recordType, keyLength, valueLength, maxPayloadSize);
        int encodedRecordSize = FormatInfo.getEncodedRecordSize(payloadSize);
        byte encodedType = RecordTypeCodec.encode(recordType);

        long writeOffset;
        try {
            writeOffset = writeChannel.size();
        } catch (IOException e) {
            throw new RecordIOException("Error obtaining segment size for append", e);
        }
        if (encodedRecordSize > Long.MAX_VALUE - writeOffset) {
            throw new RecordIOException.InvalidLocation("Record end exceeds the supported file offset range", writeOffset, null);
        }
        try {
            ByteBuffer buffer = ByteBuffer.allocate(encodedRecordSize).order(FormatInfo.BYTE_ORDER);

            ByteBuffer checksumHeaderFields = ByteBuffer.allocate(FormatInfo.getChecksumHeaderFieldsSize()).order(FormatInfo.BYTE_ORDER);
            checksumHeaderFields.put(encodedType);
            checksumHeaderFields.putInt(keyLength);
            checksumHeaderFields.putInt(valueLength);
            checksumHeaderFields.flip();
            Checksum checksum = new CRC32C();
            checksum.update(checksumHeaderFields);
            checksum.update(recordPayload.key());
            checksum.update(recordPayload.value());
            long checksumValue = checksum.getValue();

            buffer.putInt((int) checksumValue);
            buffer.put(encodedType);
            buffer.putInt(keyLength);
            buffer.putInt(valueLength);
            buffer.put(recordPayload.key());
            buffer.put(recordPayload.value());
            buffer.flip();

            writeChannel.position(writeOffset);
            writeFully(buffer, writeOffset);

            return new WriteResult(writeOffset, encodedRecordSize);
        } catch (IOException e) {
            throw new RecordIOException("Error writing record data at offset " + writeOffset, e);
        }
    }

    public void flushToStorage() {
        try {
            fileSystemSync.forceFile(writeChannel);
        } catch (DurabilityException e) {
            throw new RecordIOException("Error flushing writes to storage", e);
        }
    }

    private void writeFully(ByteBuffer buffer, long writeOffset) throws IOException {
        int consecutiveZeroWrites = 0;
        while (buffer.hasRemaining()) {
            if (writeChannel.write(buffer) > 0) {
                consecutiveZeroWrites = 0;
            } else if (++consecutiveZeroWrites == MAX_CONSECUTIVE_ZERO_TRANSFERS) {
                throw new RecordIOException.NoProgress("No progress writing record at offset " + writeOffset);
            }
        }
    }
}
