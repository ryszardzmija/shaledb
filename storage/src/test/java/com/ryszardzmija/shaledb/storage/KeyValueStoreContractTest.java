package com.ryszardzmija.shaledb.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;

import static com.ryszardzmija.shaledb.storage.KeyValueStoreTestSupport.assertValue;
import static com.ryszardzmija.shaledb.storage.KeyValueStoreTestSupport.config;
import static com.ryszardzmija.shaledb.storage.KeyValueStoreTestSupport.hex;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class KeyValueStoreContractTest {
    @TempDir
    Path tempDir;

    @Test
    void readsIndependentKeysRepeatedlyAndReportsMissingKeys() {
        byte[] first = hex("01");
        byte[] second = hex("02");
        byte[] missing = hex("03");

        try (var store = openStore()) {
            assertThat(store.get(missing)).isEmpty();
            store.put(first, hex("0a0b"));
            assertValue(store, first, hex("0a0b"));
            store.put(second, hex("0c"));

            for (int read = 0; read < 3; read++) {
                assertValue(store, first, hex("0a0b"));
                assertValue(store, second, hex("0c"));
                assertThat(store.get(missing)).isEmpty();
            }
        }
    }

    @Test
    void addressesBinaryKeysByContentAndPreservesBinaryValues() {
        try (var store = openStore()) {
            store.put(hex("00"), hex("ff0080"));
            store.put(hex("0080"), hex("0080feff"));
            store.put(hex("0080ff"), hex("c32800"));
            store.put(hex("00807f"), hex("00fe"));

            assertValue(store, hex("00"), hex("ff0080"));
            assertValue(store, hex("0080"), hex("0080feff"));
            assertValue(store, hex("0080ff"), hex("c32800"));
            assertValue(store, hex("00807f"), hex("00fe"));
            assertThat(store.get(hex("0080fe"))).isEmpty();
        }
    }

    @Test
    void keepsCollidingBinaryKeysIndependent() {
        byte[] first = hex("001f");
        byte[] second = hex("0100");
        assertThat(Arrays.hashCode(first)).isEqualTo(Arrays.hashCode(second));

        try (var store = openStore()) {
            store.put(first, hex("80"));
            store.put(second, hex("ff"));
            assertValue(store, hex("001f"), hex("80"));
            assertValue(store, hex("0100"), hex("ff"));

            store.put(hex("001f"), hex("81"));
            assertValue(store, hex("001f"), hex("81"));
            assertValue(store, hex("0100"), hex("ff"));

            store.delete(hex("001f"));
            assertThat(store.get(first)).isEmpty();
            assertValue(store, second, hex("ff"));
        }
    }

    @Test
    void returnsTheLatestValueAfterShorterLongerAndIdenticalOverwrites() {
        byte[] key = hex("01");
        byte[] other = hex("02");

        try (var store = openStore()) {
            store.put(key, hex("0102"));
            store.put(other, hex("03"));

            store.put(key, hex("04"));
            assertValue(store, key, hex("04"));

            store.put(key, hex("05060708"));
            assertValue(store, key, hex("05060708"));

            store.put(key, hex("05060708"));
            assertValue(store, key, hex("05060708"));
            assertValue(store, other, hex("03"));
        }
    }

    @Test
    void deletesIdempotentlyAndAllowsRepeatedReinsertion() {
        byte[] key = hex("01");
        byte[] other = hex("02");

        try (var store = openStore()) {
            store.put(other, hex("ff"));
            store.delete(key);
            assertThat(store.get(key)).isEmpty();

            store.put(key, hex("10"));
            for (int cycle = 0; cycle < 3; cycle++) {
                byte[] replacement = {(byte) (0x20 + cycle)};
                byte[] reinserted = {(byte) (0x30 + cycle)};
                store.put(key, replacement);
                assertValue(store, key, replacement);

                store.delete(key);
                assertThat(store.get(key)).isEmpty();
                store.delete(key);
                assertThat(store.get(key)).isEmpty();
                assertValue(store, other, hex("ff"));

                store.put(key, reinserted);
                assertValue(store, key, reinserted);
            }
        }
    }

    @Test
    void treatsEmptyValuesAsPresentThroughOverwritesAndReinsertion() {
        byte[] key = hex("01");
        byte[] empty = new byte[0];

        try (var store = openStore()) {
            store.put(key, empty);
            assertValue(store, key, empty);
            assertThat(store.get(hex("02"))).isEmpty();

            store.put(key, hex("8000"));
            assertValue(store, key, hex("8000"));
            store.put(key, empty);
            assertValue(store, key, empty);

            store.delete(key);
            assertThat(store.get(key)).isEmpty();
            store.put(key, empty);
            assertValue(store, key, empty);
        }
    }

    @Test
    void retainsTheOriginalKeyWhenTheCallerMutatesItsArray() {
        byte[] key = hex("0080ff");

        try (var store = openStore()) {
            store.put(key, hex("0102"));
            key[0] = 0x01;

            assertValue(store, hex("0080ff"), hex("0102"));
            assertThat(store.get(key)).isEmpty();
        }
    }

    @Test
    void retainsTheOriginalValueWhenTheCallerMutatesItsInputArray() {
        byte[] key = hex("01");
        byte[] value = hex("0080ff");

        try (var store = openStore()) {
            store.put(key, value);
            value[0] = 0x01;

            assertValue(store, key, hex("0080ff"));
        }
    }

    @Test
    void mutatingAReturnedValueDoesNotChangeLaterReads() {
        byte[] key = hex("01");

        try (var store = openStore()) {
            store.put(key, hex("0080ff"));
            byte[] returned = store.get(key).orElseThrow();
            returned[0] = 0x01;

            assertValue(store, key, hex("0080ff"));
        }
    }

    @Test
    void rejectsNullKeysForPutGetAndDelete() {
        assertRejectsInvalidKey(null);
    }

    @Test
    void rejectsEmptyKeysForPutGetAndDelete() {
        assertRejectsInvalidKey(new byte[0]);
    }

    @Test
    void rejectsNullValuesWithoutChangingExistingOrMissingKeys() {
        byte[] existing = hex("01");
        byte[] missing = hex("02");

        try (var store = openStore()) {
            store.put(existing, hex("80"));

            assertThatIllegalArgumentException().isThrownBy(() -> store.put(existing, null));
            assertThatIllegalArgumentException().isThrownBy(() -> store.put(missing, null));

            assertValue(store, existing, hex("80"));
            assertThat(store.get(missing)).isEmpty();
        }
    }

    private void assertRejectsInvalidKey(byte[] invalidKey) {
        byte[] existing = hex("01");
        try (var store = openStore()) {
            store.put(existing, hex("80"));

            assertThatIllegalArgumentException().isThrownBy(() -> store.put(invalidKey, hex("ff")));
            assertThatIllegalArgumentException().isThrownBy(() -> store.get(invalidKey));
            assertThatIllegalArgumentException().isThrownBy(() -> store.delete(invalidKey));

            assertValue(store, existing, hex("80"));
        }
    }

    private KeyValueStore openStore() {
        return new KeyValueStore(config(tempDir, 4096));
    }
}
