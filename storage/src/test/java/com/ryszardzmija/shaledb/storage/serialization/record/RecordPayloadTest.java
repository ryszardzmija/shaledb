package com.ryszardzmija.shaledb.storage.serialization.record;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class RecordPayloadTest {
    @Test
    void rejectsNullKey() {
        assertThatIllegalArgumentException().isThrownBy(() -> new RecordPayload(null, new byte[] {1}))
                .withMessage("Record key must not be null or empty");
    }

    @Test
    void rejectsEmptyKey() {
        assertThatIllegalArgumentException().isThrownBy(() -> new RecordPayload(new byte[0], new byte[] {1}))
                .withMessage("Record key must not be null or empty");
    }

    @Test
    void rejectsNullValue() {
        assertThatIllegalArgumentException().isThrownBy(() -> new RecordPayload(new byte[] {1}, null))
                .withMessage("Record value must not be null");
    }
}
