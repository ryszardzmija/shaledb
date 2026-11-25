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
import com.ryszardzmija.shaledb.storage.hash.index.KeyDirectory;
import com.ryszardzmija.shaledb.storage.hash.index.RecordLocation;
import com.ryszardzmija.shaledb.storage.hash.segment.files.*;
import com.ryszardzmija.shaledb.storage.hash.segment.loader.SegmentLoader;
import com.ryszardzmija.shaledb.storage.hash.segment.loader.SegmentLoadingException;
import com.ryszardzmija.shaledb.storage.hash.segment.model.*;
import com.ryszardzmija.shaledb.storage.hash.segment.rollover.*;
import com.ryszardzmija.shaledb.storage.serialization.io.DataCorruptionException;
import com.ryszardzmija.shaledb.storage.serialization.io.IncompleteRecordException;
import com.ryszardzmija.shaledb.storage.serialization.io.ReadRequest;
import com.ryszardzmija.shaledb.storage.serialization.io.RecordIOException;
import com.ryszardzmija.shaledb.storage.serialization.io.WriteResult;
import com.ryszardzmija.shaledb.storage.serialization.record.RecordType;
import com.ryszardzmija.shaledb.storage.serialization.spec.FormatInfo;

import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class HashIndexEngine implements StorageEngine {
    private enum State { OPENING, OPEN, FAILED, CLOSED }

    private final DatabaseLock ownership;
    private final SegmentChannelCleanup channelCleanup;
    private final RolloverPolicy rolloverPolicy;
    private final RolloverHandler rolloverHandler;
    private final long maxPayloadSize;

    private final Map<Long, ImmutableSegment> immutableSegments;
    private KeyDirectory keyDirectory;
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
            this.rolloverHandler = new RolloverHandler(segmentFileFactory, segmentConfig,
                    durabilityConfig, channelCleanup);
            SegmentLoader segmentLoader = new SegmentLoader(segmentConfig, durabilityConfig, channelCleanup,
                    fileSystemSync);

            KeyDirectory directory = new KeyDirectory();
            // The insertion-order chain keeps cleanup independent of the hash buckets.
            Map<Long, ImmutableSegment> registry = LinkedHashMap.newLinkedHashMap(discovered.size());
            SegmentLayout segmentLayout = latest == null
                    ? new SegmentLayout(List.of(), segmentFileFactory.createSegmentFile())
                    : new SegmentLayout(discovered.headMap(latest.getKey(), false).entrySet().stream()
                            .map(entry -> new SegmentFile(entry.getKey(), entry.getValue())).toList(),
                            new SegmentFile(latest.getKey(), latest.getValue()));
            this.mutableSegment = segmentLoader.loadSegments(segmentLayout, latest != null, directory, registry);
            this.immutableSegments = registry;
            this.keyDirectory = directory;
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
        int payloadSize = FormatInfo.getPayloadSize(RecordType.NORMAL, key.length, value.length, maxPayloadSize);
        ByteKey byteKey = new ByteKey(key);
        mutate(Operation.PUT, FormatInfo.getEncodedRecordSize(payloadSize),
                () -> mutableSegment.appendValue(byteKey.getData(), value),
                appended -> keyDirectory.markPresent(byteKey,
                        new RecordLocation(mutableSegment.id(), appended.writeOffset())));
    }

    @Override
    public Optional<byte[]> get(byte[] key) {
        requireOpen();
        validateKey(key);
        ByteKey byteKey = new ByteKey(key);

        try {
            Optional<RecordLocation> found = keyDirectory.lookup(byteKey);
            if (found.isEmpty()) {
                return Optional.empty();
            }
            RecordLocation location = found.get();
            if (location.segmentId() == mutableSegment.id()) {
                return Optional.of(mutableSegment.readValue(location.recordOffset(), byteKey));
            }
            ImmutableSegment historical = immutableSegments.get(location.segmentId());
            if (historical == null) {
                throw new IndexConsistencyException(location.recordOffset(),
                        "Unknown segment ID " + location.segmentId());
            }
            return Optional.of(historical.readValue(location.recordOffset(), byteKey));
        } catch (SegmentIOException | IndexConsistencyException e) {
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
        int payloadSize = FormatInfo.getPayloadSize(RecordType.TOMBSTONE, key.length, 0, maxPayloadSize);
        ByteKey byteKey = new ByteKey(key);
        mutate(Operation.DELETE, FormatInfo.getEncodedRecordSize(payloadSize),
                () -> mutableSegment.appendTombstone(byteKey.getData()),
                appended -> keyDirectory.remove(byteKey));
    }

    @Override
    public void close() {
        State previousState = state;
        state = State.CLOSED;
        keyDirectory = null;

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
            for (ImmutableSegment segment : immutableSegments.values()) {
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

    private void mutate(Operation operation, int recordSize, Supplier<WriteResult> append, Consumer<WriteResult> publish) {
        // StorageConfig guarantees that every admitted record fits in an empty segment.
        MutationOutcome outcome = MutationOutcome.NOT_APPENDED;
        try {
            handleRollover(recordSize);
            outcome = MutationOutcome.UNKNOWN;
            WriteResult appended = append.get();
            outcome = MutationOutcome.FORCE_COMPLETED;
            publish.accept(appended);
        } catch (RuntimeException | Error e) {
            if (outcome == MutationOutcome.NOT_APPENDED && e instanceof SegmentFileException.IdExhausted exhausted) {
                // The handler reports exhaustion before retiring the current writer.
                throw failure(operation, outcome, "No successor segment ID available", exhausted);
            }
            if (outcome != MutationOutcome.FORCE_COMPLETED && e instanceof RuntimeException operational
                    && (e instanceof SegmentIOException || e instanceof RolloverException)) {
                String message = outcome == MutationOutcome.NOT_APPENDED
                        ? "Failed to prepare the destination segment" : "Failed to append or force mutation";
                throw terminalFailure(operation, outcome, message, operational);
            }
            // Publication only changes memory and has no expected operational failures.
            markFailed(e);
            throw e;
        }
    }

    private void handleRollover(int recordSize) {
        if (!rolloverPolicy.shouldRollover(mutableSegment.size(), recordSize)) {
            return;
        }
        RolloverResult replacement = rolloverHandler.rollOver(mutableSegment);
        mutableSegment = replacement.newMutableSegment(); // Keep the successor reachable for close.
        ImmutableSegment historical = replacement.newImmutableSegment();
        try {
            long id = historical.id();
            if (immutableSegments.putIfAbsent(id, historical) != null) {
                throw new IllegalStateException("Duplicate historical segment ID " + id);
            }
        } catch (RuntimeException | Error e) {
            // Close even if insertion linked the segment before failing. Segment close is idempotent.
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
        return new StorageEngineException(message, cause, operation, classify(operation, cause), outcome);
    }

    private static Reason classify(Operation operation, RuntimeException failure) {
        // Only the primary cause determines the category. Suppressed failures describe cleanup.
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            switch (cause) {
                case DurabilityException _ -> { return Reason.DURABILITY; }
                case DataCorruptionException corruption -> {
                    return corruption.kind() == DataCorruptionException.Kind.CHECKSUM
                            ? Reason.CHECKSUM_CORRUPTION : Reason.STRUCTURAL_CORRUPTION;
                }
                case IncompleteRecordException incomplete -> {
                    return operation == Operation.OPEN && incomplete.origin() == IncompleteRecordException.Origin.TRANSFER_EOF
                            ? Reason.STORAGE_IO : Reason.INCOMPLETE_RECORD;
                }
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
