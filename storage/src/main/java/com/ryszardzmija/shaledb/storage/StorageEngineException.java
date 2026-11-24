package com.ryszardzmija.shaledb.storage;

import java.util.Objects;

/** An operational failure reported by the storage engine. The cause retains component diagnostics. */
public class StorageEngineException extends RuntimeException {
    public enum Operation { OPEN, GET, PUT, DELETE, CLOSE, UNSPECIFIED }

    public enum Reason {
        STORAGE_IO,
        DURABILITY,
        STRUCTURAL_CORRUPTION,
        CHECKSUM_CORRUPTION,
        INCOMPLETE_RECORD,
        INCOMPATIBLE_RECORD_SIZE,
        INVALID_RECORD_LOCATION,
        INVALID_SEGMENT_METADATA,
        UNSPECIFIED,
        INVALID_INDEX_REFERENCE,
        SEGMENT_ID_EXHAUSTED,
        DATABASE_LOCKED,
        INVALID_LOCK_FILE,
        OWNERSHIP_IO
    }

    public enum MutationOutcome {
        /** The failed operation was not a put or delete, or no outcome was provided. */
        NOT_APPLICABLE,
        /** An append or force failed and the engine cannot establish the persistence outcome. */
        UNKNOWN,
        /** The mutation's file force completed before a later operation failed. */
        FORCE_COMPLETED,
        /** The operation failed before the incoming record's append began. */
        NOT_APPENDED
    }

    private final Operation operation;
    private final Reason reason;
    private final MutationOutcome mutationOutcome;

    public StorageEngineException(String message, Throwable cause) {
        this(message, cause, Operation.UNSPECIFIED, Reason.UNSPECIFIED, MutationOutcome.NOT_APPLICABLE);
    }

    public StorageEngineException(
            String message, Throwable cause, Operation operation, Reason reason, MutationOutcome mutationOutcome) {
        super(message, cause);
        this.operation = Objects.requireNonNull(operation);
        this.reason = Objects.requireNonNull(reason);
        this.mutationOutcome = Objects.requireNonNull(mutationOutcome);
    }

    public Operation operation() {
        return operation;
    }

    public Reason reason() {
        return reason;
    }

    public MutationOutcome mutationOutcome() {
        return mutationOutcome;
    }
}
