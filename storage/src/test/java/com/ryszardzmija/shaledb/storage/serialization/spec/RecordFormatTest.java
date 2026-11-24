package com.ryszardzmija.shaledb.storage.serialization.spec;

import com.ryszardzmija.shaledb.storage.serialization.record.RecordType;
import org.junit.jupiter.api.Test;

import java.nio.ByteOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class RecordFormatTest {
    @Test
    void definesLittleEndianThirteenByteHeader() {
        assertThat(FormatInfo.BYTE_ORDER).isEqualTo(ByteOrder.LITTLE_ENDIAN);
        assertThat(FormatInfo.CHECKSUM_FIELD_SIZE).isEqualTo(4);
        assertThat(FormatInfo.TYPE_FIELD_SIZE).isEqualTo(1);
        assertThat(FormatInfo.KEY_LENGTH_FIELD_SIZE).isEqualTo(4);
        assertThat(FormatInfo.VALUE_LENGTH_FIELD_SIZE).isEqualTo(4);
        assertThat(FormatInfo.getHeaderSize()).isEqualTo(13);
        assertThat(FormatInfo.getChecksumHeaderFieldsSize()).isEqualTo(9);
    }

    @Test
    void encodesAndDecodesNormalTypeAsZero() {
        assertThat(RecordTypeCodec.encode(RecordType.NORMAL)).isEqualTo((byte) 0);
        assertThat(RecordTypeCodec.decode((byte) 0)).isEqualTo(RecordType.NORMAL);
    }

    @Test
    void encodesAndDecodesTombstoneTypeAsOne() {
        assertThat(RecordTypeCodec.encode(RecordType.TOMBSTONE)).isEqualTo((byte) 1);
        assertThat(RecordTypeCodec.decode((byte) 1)).isEqualTo(RecordType.TOMBSTONE);
    }

    @Test
    void rejectsUnknownTypeIdentifier() {
        assertThatExceptionOfType(TypeDecodingException.class).isThrownBy(() -> RecordTypeCodec.decode((byte) 127))
                .withMessage("Unknown record type: 127");
    }

    @Test
    void countsKeyValueAndHeaderBytes() {
        int payloadSize = FormatInfo.getPayloadSize(RecordType.NORMAL, 3, 2, 5);

        assertThat(payloadSize).isEqualTo(5);
        assertThat(FormatInfo.getEncodedRecordSize(payloadSize)).isEqualTo(18);
    }

    @Test
    void countsMinimumEmptyValueAndTombstoneRecords() {
        int minimumPayloadSize = FormatInfo.getPayloadSize(RecordType.NORMAL, 1, 0, 1);
        int emptyValuePayloadSize = FormatInfo.getPayloadSize(RecordType.NORMAL, 4, 0, 4);
        int tombstonePayloadSize = FormatInfo.getPayloadSize(RecordType.TOMBSTONE, 4, 0, 4);

        assertThat(minimumPayloadSize).isOne();
        assertThat(FormatInfo.getEncodedRecordSize(minimumPayloadSize)).isEqualTo(14);
        assertThat(emptyValuePayloadSize).isEqualTo(4);
        assertThat(tombstonePayloadSize).isEqualTo(4);
        assertThat(FormatInfo.getEncodedRecordSize(emptyValuePayloadSize)).isEqualTo(17);
        assertThat(FormatInfo.getEncodedRecordSize(tombstonePayloadSize)).isEqualTo(17);
    }

    @Test
    void rejectsInvalidLengthsBeforeAddingThem() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> FormatInfo.getPayloadSize(RecordType.NORMAL, -1, 2, 5))
                .withMessageContaining("key length must be positive: -1");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> FormatInfo.getPayloadSize(RecordType.NORMAL, 0, 2, 5))
                .withMessageContaining("key length must be positive: 0");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> FormatInfo.getPayloadSize(RecordType.NORMAL, 2, -1, 5))
                .withMessageContaining("value length must not be negative: -1");
    }

    @Test
    void rejectsPayloadSumsLargerThanAnIntEvenWithALargerBudget() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> FormatInfo.getPayloadSize(RecordType.NORMAL, Integer.MAX_VALUE, 1, Long.MAX_VALUE))
                .withMessageContaining("2147483648")
                .withMessageContaining("representable range");
    }

    @Test
    void requiresRoomForHeaderWithinTheIntegerRepresentation() {
        int largestPayload = Integer.MAX_VALUE - 13;
        int payloadSize = FormatInfo.getPayloadSize(RecordType.NORMAL, 1, largestPayload - 1, largestPayload);

        assertThat(payloadSize).isEqualTo(largestPayload);
        assertThat(FormatInfo.getEncodedRecordSize(payloadSize)).isEqualTo(Integer.MAX_VALUE);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> FormatInfo.getPayloadSize(RecordType.NORMAL, largestPayload, 1, Long.MAX_VALUE))
                .withMessageContaining("representable range");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> FormatInfo.getEncodedRecordSize(largestPayload + 1L))
                .withMessageContaining("representable range");
    }
}
