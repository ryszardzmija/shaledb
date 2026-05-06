package com.ryszardzmija.shaledb.server.config;

public class ConfigLoadingException extends RuntimeException {
    public ConfigLoadingException(String message) {
        super(message);
    }

    public ConfigLoadingException(String message, Throwable throwable) {
        super(message, throwable);
    }
}
