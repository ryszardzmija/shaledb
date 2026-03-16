package com.ryszardzmija.storage.hash.index;

import java.util.Optional;

public interface Index {
    void markPresent(ByteKey key, long offset);
    void markDeleted(ByteKey key);

    boolean isPresent(ByteKey key);
    boolean isDeleted(ByteKey key);

    Optional<Long> getKeyOffset(ByteKey key);
}
