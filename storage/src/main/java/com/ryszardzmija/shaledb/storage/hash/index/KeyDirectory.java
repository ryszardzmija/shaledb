package com.ryszardzmija.shaledb.storage.hash.index;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** One location per live key, for serialized use by the storage engine. */
public final class KeyDirectory {
    private final Map<ByteKey, RecordLocation> locations = new HashMap<>();

    public Optional<RecordLocation> lookup(ByteKey key) {
        return Optional.ofNullable(locations.get(key));
    }

    public void markPresent(ByteKey key, RecordLocation location) {
        locations.put(Objects.requireNonNull(key), Objects.requireNonNull(location));
    }

    public void remove(ByteKey key) {
        locations.remove(key);
    }

    int size() {
        return locations.size();
    }
}
