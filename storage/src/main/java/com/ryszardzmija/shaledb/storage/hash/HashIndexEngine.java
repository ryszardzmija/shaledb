package com.ryszardzmija.shaledb.storage.hash;

import com.ryszardzmija.shaledb.storage.StorageEngine;
import com.ryszardzmija.shaledb.storage.StorageEngineException;
import com.ryszardzmija.shaledb.storage.StorageEngineException.MutationOutcome;
import com.ryszardzmija.shaledb.storage.StorageEngineException.Operation;
import com.ryszardzmija.shaledb.storage.StorageEngineException.Reason;
import com.ryszardzmija.shaledb.storage.config.StorageConfig;
import com.ryszardzmija.shaledb.storage.durability.DurabilityConfig;
import com.ryszardzmija.shaledb.storage.durability.DurabilityException;
import com.ryszardzmija.shaledb.storage.durability.FileSystemSync;
import com.ryszardzmija.shaledb.storage.hash.index.ByteKey;
import com.ryszardzmija.shaledb.storage.hash.segment.files.*;
import com.ryszardzmija.shaledb.storage.hash.segment.loader.LoadedSegments;
import com.ryszardzmija.shaledb.storage.hash.segment.loader.SegmentLoader;
import com.ryszardzmija.shaledb.storage.hash.segment.loader.SegmentLoadingException;
import com.ryszardzmija.shaledb.storage.hash.segment.model.*;
import com.ryszardzmija.shaledb.storage.hash.segment.rollover.*;
import com.ryszardzmija.shaledb.storage.serialization.io.DataCorruptionException;
import com.ryszardzmija.shaledb.storage.serialization.io.IncompleteRecordException;
import com.ryszardzmija.shaledb.storage.serialization.io.ReadRequest;
import com.ryszardzmija.shaledb.storage.serialization.io.RecordIOException;
import com.ryszardzmija.shaledb.storage.serialization.record.RecordType;
import com.ryszardzmija.shaledb.storage.serialization.spec.FormatInfo;

import java.nio.file.Path;
import java.util.*;

public class HashIndexEngine implements StorageEngine {
    private enum State { OPENING, OPEN, FAILED, CLOSED }

    private final DatabaseLock ownership;
    private final SegmentChannelCleanup channelCleanup;
    private final RolloverPolicy rolloverPolicy;
    private final RolloverHandler rolloverHandler;
    private final long maxPayloadSize;

    private final ArrayList<ImmutableSegment> immutableSegments;
    private MutableSegment mutableSegment;
    private State state = State.OPENING;
    private Throwable failureCause;

    public HashIndexEngine(StorageConfig storageConfig) {
        Objects.requireNonNull(storageConfig);
        this.maxPayloadSize = storageConfig.maxPayloadSize();
        SegmentConfig segmentConfig = SegmentConfig.from(storageConfig);
        DurabilityConfig durabilityConfig = DurabilityConfig.from(storageConfig);
        this.ownership = DatabaseLock.acquire(storageConfig.segmentDir());
        this.channelCleanup = ownership.segmentCleanup();
        Path segmentDir = ownership.directory();

        try {
            NavigableMap<Long, Path> discovered = new SegmentFileDiscoverer(segmentDir).discoverSegmentFiles();
            Map.Entry<Long, Path> latest = discovered.lastEntry();
            FileSystemSync fileSystemSync = new FileSystemSync();
            SegmentFileFactory segmentFileFactory = new SegmentFileFactory(segmentDir, fileSystemSync,
                    latest == null ? 0 : latest.getKey(), channelCleanup);
            this.rolloverPolicy = new SizeBasedRolloverPolicy(segmentConfig.maxSegmentSize());
            this.rolloverHandler = new RolloverHandler(segmentFileFactory, fileSystemSync, segmentConfig,
                    durabilityConfig, channelCleanup);
            SegmentLoader segmentLoader = new SegmentLoader(segmentConfig, durabilityConfig, channelCleanup);

            SegmentLayout segmentLayout = latest == null
                    ? new SegmentLayout(List.of(), segmentFileFactory.createSegmentFile())
                    : new SegmentLayout(List.copyOf(discovered.headMap(latest.getKey(), false).values()), latest.getValue());
            LoadedSegments loadedSegments = segmentLoader.loadSegments(segmentLayout);
            this.immutableSegments = loadedSegments.immutableSegments();
            this.mutableSegment = loadedSegments.mutableSegment();
            this.state = State.OPEN;
        } catch (RuntimeException | Error e) {
            if (!channelCleanup.hasPendingChannels()) {
                ownership.closeAfterFailedOpen(e);
            }
            if (e instanceof SegmentFileException || e instanceof SegmentFileDiscoveryException
                    || e instanceof SegmentLoadingException) {
                throw failure(Operation.OPEN, MutationOutcome.NOT_APPLICABLE,
                        "Failed to open store in " + segmentDir, (RuntimeException) e);
            }
            throw e;
        }
    }

    @Override
    public void put(byte[] key, byte[] value) {
        requireOpen();
        validateKey(key);
        if (value == null) {
            throw new IllegalArgumentException("Value must not be null");
        }
        FormatInfo.getPayloadSize(RecordType.NORMAL, key.length, value.length, maxPayloadSize);
        ByteKey byteKey = new ByteKey(key);
        mutate(Operation.PUT, () -> mutableSegment.put(byteKey, value));
    }

    @Override
    public Optional<byte[]> get(byte[] key) {
        requireOpen();
        validateKey(key);
        ByteKey byteKey = new ByteKey(key);

        try {
            LookupResult mutableResult = mutableSegment.get(byteKey);
            switch (mutableResult) {
                case LookupResult.Deleted _ -> { return Optional.empty(); }
                case LookupResult.Present mappedValue -> { return Optional.of(mappedValue.value()); }
                case LookupResult.NotPresent _ -> {  /* continue */ }
            }

            for (ImmutableSegment segment : immutableSegments) {
                LookupResult immutableResult = segment.get(byteKey);
                switch (immutableResult) {
                    case LookupResult.Deleted _ -> { return Optional.empty(); }
                    case LookupResult.Present mappedValue -> { return Optional.of(mappedValue.value()); }
                    case LookupResult.NotPresent _ -> {  /* continue */ }
                }
            }

            return Optional.empty();
        } catch (SegmentIOException e) {
            throw terminalFailure(Operation.GET, MutationOutcome.NOT_APPLICABLE, "Failed to read indexed record", e);
        } catch (RuntimeException | Error e) {
            markFailed(e);
            throw e;
        }
    }

    /**
     * Deletes the key-value pair associated with the key.
     *
     * <p>Note that each deletion writes a tombstone record for the key, which means that
     * even though repeated deletions on the same key do not change the state of the store,
     * they are still accumulated on the disk.
     *
     * @param key a non-empty array of bytes representing the key
     * @throws IllegalArgumentException if the key is null, empty, or exceeds the payload limit
     * @throws StorageEngineException if the key-value pair associated with the key fails to be deleted
     */
    @Override
    public void delete(byte[] key) {
        requireOpen();
        validateKey(key);
        FormatInfo.getPayloadSize(RecordType.TOMBSTONE, key.length, 0, maxPayloadSize);
        ByteKey byteKey = new ByteKey(key);
        mutate(Operation.DELETE, () -> mutableSegment.delete(byteKey));
    }

    @Override
    public void close() {
        State previousState = state;
        state = State.CLOSED;

        Throwable primaryFailure = null;
        if (previousState == State.OPEN) {
            try {
                mutableSegment.sync();
            } catch (RuntimeException | Error e) {
                primaryFailure = e;
            }
        }
        primaryFailure = channelCleanup.retryPending(primaryFailure);
        if (previousState != State.CLOSED) {
            try {
                mutableSegment.close();
            } catch (RuntimeException | Error e) {
                primaryFailure = SegmentChannelCleanup.suppress(primaryFailure, e);
            }
            mutableSegment = null;
            for (ImmutableSegment segment : immutableSegments) {
                try {
                    segment.close();
                } catch (RuntimeException | Error e) {
                    primaryFailure = SegmentChannelCleanup.suppress(primaryFailure, e);
                }
            }
            immutableSegments.clear();
        }

        if (!channelCleanup.hasPendingChannels()) {
            try {
                ownership.close();
            } catch (RuntimeException | Error e) {
                primaryFailure = SegmentChannelCleanup.suppress(primaryFailure, e);
            }
        }
        if (primaryFailure instanceof SegmentIOException e) {
            throw failure(Operation.CLOSE, MutationOutcome.NOT_APPLICABLE, "Failed to close store", e);
        }
        if (primaryFailure instanceof RuntimeException e) {
            throw e;
        }
        if (primaryFailure instanceof Error e) {
            throw e;
        }
    }

    private void mutate(Operation operation, Runnable appendAndPublish) {
        // Caller validation is complete. Guard the append path, including setup of its write position.
        MutationOutcome outcome = MutationOutcome.UNKNOWN;
        try {
            appendAndPublish.run();
            outcome = MutationOutcome.FORCE_COMPLETED;
            handleRollover();
        } catch (SegmentIOException | RolloverException e) {
            String message = outcome == MutationOutcome.FORCE_COMPLETED
                    ? "Rollover check or handoff failed after mutation" : "Failed to append or force mutation";
            throw terminalFailure(operation, outcome, message, e);
        } catch (RuntimeException | Error e) {
            markFailed(e);
            throw e;
        }
    }

    private void handleRollover() {
        if (!rolloverPolicy.shouldRollover(mutableSegment)) {
            return;
        }
        // Reserve room before the handler can retire the active segment.
        immutableSegments.ensureCapacity(immutableSegments.size() + 1);
        RolloverResult replacement = rolloverHandler.rollOver(mutableSegment);
        mutableSegment = replacement.newMutableSegment();
        ImmutableSegment historical = replacement.newImmutableSegment();
        try {
            immutableSegments.addFirst(historical);
        } catch (RuntimeException | Error e) {
            // Keep the successor reachable for close. The history list may never have taken ownership.
            try {
                historical.close();
            } catch (RuntimeException | Error closeFailure) {
                SegmentChannelCleanup.suppress(e, closeFailure);
            }
            throw e;
        }
    }

    private StorageEngineException terminalFailure(
            Operation operation, MutationOutcome outcome, String message, RuntimeException cause) {
        StorageEngineException failure = failure(operation, outcome, message, cause);
        markFailed(failure);
        return failure;
    }

    private static StorageEngineException failure(
            Operation operation, MutationOutcome outcome, String message, RuntimeException cause) {
        return new StorageEngineException(message, cause, operation, classify(cause), outcome);
    }

    private static Reason classify(RuntimeException failure) {
        // Only the primary cause determines the category. Suppressed failures describe cleanup.
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            switch (cause) {
                case DurabilityException _ -> { return Reason.DURABILITY; }
                case DataCorruptionException corruption -> {
                    return corruption.kind() == DataCorruptionException.Kind.CHECKSUM
                            ? Reason.CHECKSUM_CORRUPTION : Reason.STRUCTURAL_CORRUPTION;
                }
                case IncompleteRecordException _ -> { return Reason.INCOMPLETE_RECORD; }
                case RecordIOException.ReadLimitExceeded _ -> { return Reason.INCOMPATIBLE_RECORD_SIZE; }
                case RecordIOException.InvalidLocation _, ReadRequest.BeyondEnd _ -> {
                    return Reason.INVALID_RECORD_LOCATION;
                }
                case IndexConsistencyException _ -> { return Reason.INVALID_INDEX_REFERENCE; }
                case SegmentFileDiscoveryException.InvalidLayout _ -> { return Reason.INVALID_SEGMENT_METADATA; }
                case SegmentFileException.IdExhausted _ -> { return Reason.SEGMENT_ID_EXHAUSTED; }
                default -> { /* Continue through contextual wrappers. */ }
            }
        }
        return Reason.STORAGE_IO;
    }

    private static void validateKey(byte[] key) {
        if (key == null || key.length == 0) {
            throw new IllegalArgumentException("Key must not be null or empty");
        }
    }

    private void requireOpen() {
        switch (state) {
            case OPEN -> { }
            case FAILED -> throw new IllegalStateException("Storage engine has failed", failureCause);
            case CLOSED -> throw new IllegalStateException("Storage engine is closed");
            case OPENING -> throw new IllegalStateException("Storage engine is opening");
        }
    }

    void markFailed(Throwable cause) {
        if (state == State.OPEN) {
            failureCause = Objects.requireNonNull(cause);
            state = State.FAILED;
        }
    }
}
