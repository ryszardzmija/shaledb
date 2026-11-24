package com.ryszardzmija.shaledb.storage.serialization.spec;

import com.ryszardzmija.shaledb.storage.serialization.record.RecordType;

import java.nio.ByteOrder;

public class FormatInfo {
    public static final int CHECKSUM_FIELD_SIZE = 4;
    public static final int TYPE_FIELD_SIZE = 1;
    public static final int KEY_LENGTH_FIELD_SIZE = 4;
    public static final int VALUE_LENGTH_FIELD_SIZE = 4;

    public static final ByteOrder BYTE_ORDER = ByteOrder.LITTLE_ENDIAN;

    public static int getHeaderSize() {
        return CHECKSUM_FIELD_SIZE + TYPE_FIELD_SIZE + KEY_LENGTH_FIELD_SIZE + VALUE_LENGTH_FIELD_SIZE;
    }

    public static int getChecksumHeaderFieldsSize() {
        return TYPE_FIELD_SIZE + KEY_LENGTH_FIELD_SIZE + VALUE_LENGTH_FIELD_SIZE;
    }

    /**
     * Validates record structure and returns the combined key and value length.
     * The result fits the configured budget and leaves room for the header in an int-sized record.
     */
    public static int getPayloadSize(RecordType type, int keyLength, int valueLength, long maxPayloadSize) {
        if (type != RecordType.NORMAL && type != RecordType.TOMBSTONE) {
            throw new IllegalArgumentException("Unsupported record type: " + type);
        }
        if (keyLength <= 0) {
            throw new IllegalArgumentException("Record key length must be positive: " + keyLength);
        }
        if (valueLength < 0) {
            throw new IllegalArgumentException("Record value length must not be negative: " + valueLength);
        }
        if (type == RecordType.TOMBSTONE && valueLength != 0) {
            throw new IllegalArgumentException("Tombstone value length must be zero: " + valueLength);
        }

        int payloadSize = requireRepresentablePayloadSize((long) keyLength + valueLength);
        if (payloadSize > maxPayloadSize) {
            throw new IllegalArgumentException("Record payload size %d exceeds configured maxPayloadSize %d"
                    .formatted(payloadSize, maxPayloadSize));
        }
        return payloadSize;
    }

    /**
     * Returns the total record length, including the header. Representability does not guarantee allocation.
     */
    public static int getEncodedRecordSize(long payloadSize) {
        return getHeaderSize() + requireRepresentablePayloadSize(payloadSize);
    }

    private static int requireRepresentablePayloadSize(long payloadSize) {
        int maxRepresentablePayloadSize = Integer.MAX_VALUE - getHeaderSize();
        if (payloadSize <= 0 || payloadSize > maxRepresentablePayloadSize) {
            throw new IllegalArgumentException("Record payload size %d is outside the representable range [1, %d]"
                    .formatted(payloadSize, maxRepresentablePayloadSize));
        }
        return (int) payloadSize;
    }
}
