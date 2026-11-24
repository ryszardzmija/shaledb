package com.ryszardzmija.shaledb.storage.hash.index;

import java.util.Arrays;

public class ByteKey {
    private final byte[] keyData;

    public ByteKey(byte[] keyData) {
        this.keyData = Arrays.copyOf(keyData, keyData.length);
    }

    public byte[] getData() {
        return Arrays.copyOf(keyData, keyData.length);
    }

    /** Compares decoded key bytes without exposing this key's backing array. */
    public final boolean matches(byte[] data) {
        return Arrays.equals(keyData, data);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (other == null) {
            return false;
        }
        if (this.getClass() != other.getClass()) {
            return false;
        }
        ByteKey otherKey = (ByteKey) other;
        return matches(otherKey.keyData);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(keyData);
    }
}
