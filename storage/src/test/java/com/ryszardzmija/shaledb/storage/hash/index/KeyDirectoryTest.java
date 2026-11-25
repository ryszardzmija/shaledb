package com.ryszardzmija.shaledb.storage.hash.index;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class KeyDirectoryTest {
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
        KeyDirectory directory = new KeyDirectory();
        var location = new RecordLocation(1, 42);
        directory.markPresent(retained, location);

        callerBytes[0] = 1;
        byte[] exported = retained.getData();
        exported[1] = 2;

        assertThat(retained).isEqualTo(lookup);
        assertThat(retained.hashCode()).isEqualTo(lookup.hashCode());
        assertThat(retained.getData()).containsExactly((byte) 0, (byte) -128, (byte) -1);
        assertThat(directory.lookup(lookup)).contains(location);
        assertThat(directory.lookup(new ByteKey(callerBytes))).isEmpty();
    }

    @Test
    void appliesEveryStateTransitionIndependentlyForCollidingKeys() {
        ByteKey key = new ByteKey(new byte[] {0, -1});
        ByteKey collision = new ByteKey(new byte[] {-1, 30});
        assertThat(collision).isNotEqualTo(key);
        assertThat(collision.hashCode()).isEqualTo(key.hashCode());

        KeyDirectory directory = new KeyDirectory();
        assertThat(directory.lookup(key)).isEmpty();
        directory.remove(collision);
        assertThat(directory.size()).isZero();

        var first = new RecordLocation(1, 0);
        var replacement = new RecordLocation(20, 7);
        directory.markPresent(key, first);
        assertThat(directory.lookup(key)).contains(first);
        assertThat(directory.lookup(collision)).isEmpty();
        directory.markPresent(new ByteKey(key.getData()), replacement);
        assertThat(directory.lookup(key)).contains(replacement);
        assertThat(directory.size()).isOne();

        var distinct = new RecordLocation(40, 13);
        directory.markPresent(collision, distinct);
        assertThat(directory.lookup(collision)).contains(distinct);
        assertThat(directory.size()).isEqualTo(2);

        directory.remove(key);
        directory.remove(new ByteKey(key.getData()));
        assertThat(directory.lookup(key)).isEmpty();
        assertThat(directory.size()).isOne();
        var reinserted = new RecordLocation(Long.MAX_VALUE, Long.MAX_VALUE);
        directory.markPresent(key, reinserted);
        assertThat(directory.lookup(key)).contains(reinserted);
        assertThat(directory.lookup(collision)).contains(distinct);
        assertThat(directory.size()).isEqualTo(2);
    }

    @Test
    void rejectsInvalidLocations() {
        assertThatIllegalArgumentException().isThrownBy(() -> new RecordLocation(0, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new RecordLocation(-1, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new RecordLocation(1, -1));
    }
}
