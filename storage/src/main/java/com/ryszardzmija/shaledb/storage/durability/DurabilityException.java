package com.ryszardzmija.shaledb.storage.durability;

public class DurabilityException extends RuntimeException {
    public enum Target { FILE, DIRECTORY, UNSPECIFIED }

    private final Target target;

    public DurabilityException(String message, Throwable cause) {
        this(message, cause, Target.UNSPECIFIED);
    }

    DurabilityException(String message, Throwable cause, Target target) {
        super(message, cause);
        this.target = target;
    }

    public Target target() {
        return target;
    }
}
