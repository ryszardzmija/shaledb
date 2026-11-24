package com.ryszardzmija.shaledb.storage.serialization.io;

import com.ryszardzmija.shaledb.storage.serialization.io.IncompleteRecordException.Section;
import com.ryszardzmija.shaledb.storage.serialization.record.RecordPayload;
import com.ryszardzmija.shaledb.storage.serialization.record.RecordType;
import com.ryszardzmija.shaledb.storage.serialization.spec.FormatInfo;
import com.ryszardzmija.shaledb.storage.serialization.spec.RecordTypeCodec;
import com.ryszardzmija.shaledb.storage.serialization.spec.TypeDecodingException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Objects;
import java.util.zip.CRC32C;
import java.util.zip.Checksum;

import static com.ryszardzmija.shaledb.storage.serialization.io.RecordIOException.MAX_CONSECUTIVE_ZERO_TRANSFERS;

public class RecordReader {
    private final FileChannel readChannel;
    private final long maxPayloadSize;

    public RecordReader(FileChannel readChannel, long maxPayloadSize) {
        this.readChannel = Objects.requireNonNull(readChannel);
        this.maxPayloadSize = maxPayloadSize;
    }

    /** Reads one expected record, including checksum verification and its next file offset. */
    public ReadResult read(ReadRequest request) {
        long offset = request.offset();

        try {
            long fileSize = readChannel.size();
            if (offset > fileSize) {
                throw new ReadRequest.BeyondEnd(offset, fileSize);
            }
            long bytesRemaining = fileSize - offset;
            int headerSize = FormatInfo.getHeaderSize();
            requireAvailable(Section.HEADER, offset, headerSize, bytesRemaining);
            ByteBuffer headerBuffer = ByteBuffer.allocate(headerSize).order(FormatInfo.BYTE_ORDER);
            readFully(headerBuffer, offset, offset, Section.HEADER);
            headerBuffer.flip();

            byte[] storedChecksum = new byte[FormatInfo.CHECKSUM_FIELD_SIZE];
            headerBuffer.get(storedChecksum);

            byte typeValue = headerBuffer.get();
            RecordType recordType = RecordTypeCodec.decode(typeValue);

            int keyDataSize = headerBuffer.getInt();
            int valueDataSize = headerBuffer.getInt();

            int payloadSize = readPayloadSize(recordType, keyDataSize, valueDataSize, offset);
            int encodedRecordSize = FormatInfo.getEncodedRecordSize(payloadSize);
            requireAvailable(Section.PAYLOAD, offset, payloadSize, bytesRemaining - headerSize);
            // Both section ends are at or below fileSize, so these sums fit in long.
            long payloadOffset = offset + headerSize;
            long nextRecordOffset = offset + encodedRecordSize;
            ByteBuffer dataBuffer = ByteBuffer.allocate(payloadSize).order(FormatInfo.BYTE_ORDER);
            readFully(dataBuffer, payloadOffset, offset, Section.PAYLOAD);

            headerBuffer.rewind();
            headerBuffer.position(FormatInfo.CHECKSUM_FIELD_SIZE);
            dataBuffer.flip();
            Checksum checksum = new CRC32C();
            checksum.update(headerBuffer);
            checksum.update(dataBuffer);
            if (!isChecksumValid(storedChecksum, checksum)) {
                throw new DataCorruptionException("Invalid checksum", offset, DataCorruptionException.Kind.CHECKSUM, null);
            }

            byte[] keyData = new byte[keyDataSize];
            byte[] valueData = new byte[valueDataSize];
            dataBuffer.rewind();
            dataBuffer.get(keyData);
            dataBuffer.get(valueData);

            return new ReadResult(new RecordPayload(keyData, valueData), recordType, nextRecordOffset);
        } catch (TypeDecodingException e) {
            throw new DataCorruptionException("Error decoding record type", offset, e);
        } catch (IOException e) {
            throw new RecordIOException("Error reading record data at offset: " + offset, e);
        }
    }

    private static void requireAvailable(Section section, long recordOffset, int expected, long available) {
        if (available < expected) {
            // The extent checks guarantee 0 <= available < expected <= Integer.MAX_VALUE.
            throw new IncompleteRecordException(section, recordOffset, expected, (int) available);
        }
    }

    private void readFully(ByteBuffer buffer, long sectionOffset, long recordOffset, Section section) throws IOException {
        int consecutiveZeroReads = 0;
        while (buffer.hasRemaining()) {
            int bytesRead = readChannel.read(buffer, sectionOffset + buffer.position());
            if (bytesRead > 0) {
                consecutiveZeroReads = 0;
            } else if (bytesRead == -1) {
                throw new IncompleteRecordException(section, recordOffset, buffer.limit(), buffer.position());
            } else if (++consecutiveZeroReads == MAX_CONSECUTIVE_ZERO_TRANSFERS) {
                throw new RecordIOException.NoProgress("No progress reading record %s at offset %d"
                        .formatted(section, recordOffset));
            }
        }
    }

    private int readPayloadSize(RecordType type, int keyLength, int valueLength, long offset) {
        try {
            return FormatInfo.getPayloadSize(type, keyLength, valueLength, maxPayloadSize);
        } catch (FormatInfo.PayloadLimitExceededException e) {
            throw new RecordIOException.ReadLimitExceeded(offset, e);
        } catch (IllegalArgumentException e) {
            throw new DataCorruptionException(e.getMessage(), offset, e);
        }
    }

    private boolean isChecksumValid(byte[] storedChecksum, Checksum computedChecksum) {
        long storedChecksumLong = Integer.toUnsignedLong(ByteBuffer.wrap(storedChecksum).order(FormatInfo.BYTE_ORDER).getInt());
        return computedChecksum.getValue() == storedChecksumLong;
    }
}
