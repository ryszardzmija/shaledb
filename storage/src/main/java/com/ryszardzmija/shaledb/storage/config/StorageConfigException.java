package com.ryszardzmija.shaledb.storage.config;

public class StorageConfigException extends RuntimeException {
    public StorageConfigException(String message) {
        super(message);
    }

    public StorageConfigException(String message, Throwable throwable) {
        super(message, throwable);
    }
}
