package com.ryszardzmija.shaledb.storage.hash.index;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class HashIndexTest {
    @Test
    void comparesCompleteBinaryKeysByContent() {
        ByteKey key = new ByteKey(new byte[] {0, -128, -1});
        ByteKey equalKey = new ByteKey(new byte[] {0, -128, -1});

        assertThat(key).isEqualTo(equalKey);
        assertThat(key.hashCode()).isEqualTo(equalKey.hashCode());
        assertThat(key).isNotEqualTo(new ByteKey(new byte[] {0, -128, 1}));
        assertThat(key).isNotEqualTo(new ByteKey(new byte[] {0, -128}));
        assertThat(key).isNotEqualTo(new ByteKey(new byte[] {0, -128, -1, 0}));
    }

    @Test
    void retainedIdentitySurvivesChangesToConstructorInputAndAccessorCopy() {
        byte[] callerBytes = {0, -128, -1};
        ByteKey retained = new ByteKey(callerBytes);
        ByteKey lookup = new ByteKey(callerBytes);
        Index index = new HashIndex();
        index.markPresent(retained, 42);

        callerBytes[0] = 1;
        byte[] exported = retained.getData();
        exported[1] = 2;

        assertThat(retained).isEqualTo(lookup);
        assertThat(retained.hashCode()).isEqualTo(lookup.hashCode());
        assertThat(retained.getData()).containsExactly((byte) 0, (byte) -128, (byte) -1);
        assertThat(index.getKeyOffset(lookup)).contains(42L);
        assertThat(index.getKeyOffset(new ByteKey(callerBytes))).isEmpty();
    }

    @Test
    void appliesEveryStateTransitionIndependentlyForCollidingKeys() {
        ByteKey key = new ByteKey(new byte[] {0, -1});
        ByteKey collision = new ByteKey(new byte[] {-1, 30});
        assertThat(collision).isNotEqualTo(key);
        assertThat(collision.hashCode()).isEqualTo(key.hashCode());

        Index index = new HashIndex();
        assertThat(index.isDeleted(key)).isFalse();
        assertThat(index.getKeyOffset(key)).isEmpty();
        index.markDeleted(collision);
        assertDeleted(index, collision);

        index.markPresent(key, 0);
        assertPresent(index, key, 0);
        assertDeleted(index, collision);
        index.markPresent(key, 20);
        assertPresent(index, key, 20);
        index.markPresent(collision, 40);
        assertPresent(index, collision, 40);

        index.markDeleted(key);
        assertDeleted(index, key);
        index.markDeleted(key);
        assertDeleted(index, key);
        index.markPresent(key, 80);
        assertPresent(index, key, 80);
        assertPresent(index, collision, 40);
    }

    @Test
    void rejectsNegativeOffsetWithoutReplacingDeletedState() {
        Index index = new HashIndex();
        ByteKey key = new ByteKey(new byte[] {1});
        index.markDeleted(key);

        assertThatIllegalArgumentException().isThrownBy(() -> index.markPresent(key, -1));

        assertDeleted(index, key);
    }

    private static void assertPresent(Index index, ByteKey key, long offset) {
        assertThat(index.getKeyOffset(key)).contains(offset);
        assertThat(index.isDeleted(key)).isFalse();
    }

    private static void assertDeleted(Index index, ByteKey key) {
        assertThat(index.isDeleted(key)).isTrue();
        assertThat(index.getKeyOffset(key)).isEmpty();
    }
}
