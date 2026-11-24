package com.ryszardzmija.shaledb.storage.hash.segment.rollover;

import com.ryszardzmija.shaledb.storage.serialization.spec.FormatInfo;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SizeBasedRolloverPolicyTest {
    @Test
    void acceptsExactFitsAndRollsOverOnlyWhenTheRecordNeedsMoreSpace() {
        int recordSize = FormatInfo.getEncodedRecordSize(1);
        long capacity = 2L * recordSize;
        var policy = new SizeBasedRolloverPolicy(capacity);

        assertThat(policy.shouldRollover(0, recordSize)).isFalse();
        assertThat(policy.shouldRollover(recordSize - 1L, recordSize)).isFalse();
        assertThat(policy.shouldRollover(recordSize, recordSize)).isFalse();
        assertThat(policy.shouldRollover(recordSize + 1L, recordSize)).isTrue();
        assertThat(policy.shouldRollover(capacity, recordSize)).isTrue();
        assertThat(policy.shouldRollover(capacity + 1, recordSize)).isTrue();
        assertThat(new SizeBasedRolloverPolicy(recordSize).shouldRollover(0, recordSize)).isFalse();
    }

    @Test
    void comparesRemainingCapacityWithoutOverflowOrNarrowing() {
        var policy = new SizeBasedRolloverPolicy(Long.MAX_VALUE);
        int minimumRecord = FormatInfo.getEncodedRecordSize(1);
        int maximumRecord = Integer.MAX_VALUE;

        assertThat(policy.shouldRollover(0, maximumRecord)).isFalse();
        assertThat(policy.shouldRollover(Long.MAX_VALUE - maximumRecord, maximumRecord)).isFalse();
        assertThat(policy.shouldRollover(Long.MAX_VALUE - maximumRecord + 1, maximumRecord)).isTrue();
        assertThat(policy.shouldRollover(Long.MAX_VALUE, minimumRecord)).isTrue();
        assertThat(new SizeBasedRolloverPolicy(maximumRecord).shouldRollover(Long.MAX_VALUE, minimumRecord)).isTrue();
    }
}
