package com.ryszardzmija.shaledb.storage.serialization.spec;

import com.ryszardzmija.shaledb.storage.serialization.record.RecordType;
import org.junit.jupiter.api.Test;

import java.nio.ByteOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

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
}
