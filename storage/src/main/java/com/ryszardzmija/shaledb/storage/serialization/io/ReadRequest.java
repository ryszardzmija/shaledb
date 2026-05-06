package com.ryszardzmija.shaledb.storage.serialization.io;

public record ReadRequest(long offset) {
    public ReadRequest {
        if (offset < 0) {
            throw new IllegalArgumentException("Record offset must not be negative: " + offset);
        }
    }
}
