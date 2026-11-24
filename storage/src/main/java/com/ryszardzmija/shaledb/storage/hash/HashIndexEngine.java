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
    private final DatabaseLock ownership;
    private final RolloverPolicy rolloverPolicy;
    private final RolloverHandler rolloverHandler;
    private final long maxPayloadSize;

    private final Deque<ImmutableSegment> immutableSegments;
    private MutableSegment mutableSegment;
    private boolean closed;

    public HashIndexEngine(StorageConfig storageConfig) {
        Objects.requireNonNull(storageConfig);
        this.maxPayloadSize = storageConfig.maxPayloadSize();
        SegmentConfig segmentConfig = SegmentConfig.from(storageConfig);
        DurabilityConfig durabilityConfig = DurabilityConfig.from(storageConfig);
        this.ownership = DatabaseLock.acquire(storageConfig.segmentDir());
        Path segmentDir = ownership.directory();

        boolean loadingSegments = false;
        try {
            NavigableMap<Long, Path> discovered = new SegmentFileDiscoverer(segmentDir).discoverSegmentFiles();
            Map.Entry<Long, Path> latest = discovered.lastEntry();
            FileSystemSync fileSystemSync = new FileSystemSync();
            SegmentFileFactory segmentFileFactory = new SegmentFileFactory(segmentDir, fileSystemSync,
                    latest == null ? 0 : latest.getKey());
            this.rolloverPolicy = new SizeBasedRolloverPolicy(segmentConfig.maxSegmentSize());
            this.rolloverHandler = new RolloverHandler(segmentFileFactory, fileSystemSync, segmentConfig, durabilityConfig);
            SegmentLoader segmentLoader = new SegmentLoader(segmentConfig, durabilityConfig);

            SegmentLayout segmentLayout = latest == null
                    ? new SegmentLayout(List.of(), segmentFileFactory.createSegmentFile())
                    : new SegmentLayout(List.copyOf(discovered.headMap(latest.getKey(), false).values()), latest.getValue());
            loadingSegments = true;
            LoadedSegments loadedSegments = segmentLoader.loadSegments(segmentLayout);
            this.immutableSegments = loadedSegments.immutableSegments();
            this.mutableSegment = loadedSegments.mutableSegment();
        } catch (RuntimeException | Error e) {
            boolean segmentCleanupFailed = e instanceof SegmentFileException creationFailure
                    ? creationFailure.segmentChannelCloseFailed()
                    : loadingSegments && segmentLoadCleanupFailed(e);
            if (!segmentCleanupFailed) {
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

    private static boolean segmentLoadCleanupFailed(Throwable failure) {
        // Segment constructors and the loader preserve channel close failures on the startup cause chain.
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause.getSuppressed().length != 0) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void put(byte[] key, byte[] value) {
        requireOpen();
        validateKey(key);
        if (value == null) {
            throw new IllegalArgumentException("Value must not be null");
        }
        FormatInfo.getPayloadSize(RecordType.NORMAL, key.length, value.length, maxPayloadSize);

        try {
            mutableSegment.put(new ByteKey(key), value);
        } catch (SegmentIOException e) {
            throw failure(Operation.PUT, MutationOutcome.UNKNOWN, "Failed to append or force put", e);
        }

        handleRollover(Operation.PUT);
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
            throw failure(Operation.GET, MutationOutcome.NOT_APPLICABLE, "Failed to read indexed record", e);
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

        try {
            mutableSegment.delete(new ByteKey(key));
        } catch (SegmentIOException e) {
            throw failure(Operation.DELETE, MutationOutcome.UNKNOWN, "Failed to append or force delete", e);
        }

        handleRollover(Operation.DELETE);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;

        Throwable primaryFailure = null;
        try {
            mutableSegment.sync();
        } catch (RuntimeException | Error e) {
            primaryFailure = e;
        }

        boolean segmentCloseFailed = false;
        try {
            mutableSegment.close();
        } catch (RuntimeException | Error e) {
            segmentCloseFailed = true;
            primaryFailure = retainFailure(primaryFailure, e);
        }
        for (ImmutableSegment segment : immutableSegments) {
            try {
                segment.close();
            } catch (RuntimeException | Error e) {
                segmentCloseFailed = true;
                primaryFailure = retainFailure(primaryFailure, e);
            }
        }

        if (!segmentCloseFailed) {
            try {
                ownership.close();
            } catch (RuntimeException | Error e) {
                primaryFailure = retainFailure(primaryFailure, e);
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

    private static Throwable retainFailure(Throwable primaryFailure, Throwable cleanupFailure) {
        if (primaryFailure == null) {
            return cleanupFailure;
        }
        if (primaryFailure != cleanupFailure) {
            primaryFailure.addSuppressed(cleanupFailure);
        }
        return primaryFailure;
    }

    private void handleRollover(Operation operation) {
        try {
            if (rolloverPolicy.shouldRollover(mutableSegment)) {
                RolloverResult rolloverResult = rolloverHandler.rollOver(mutableSegment);
                mutableSegment = rolloverResult.newMutableSegment();
                immutableSegments.addFirst(rolloverResult.newImmutableSegment());
            }
        } catch (SegmentIOException | RolloverException e) {
            throw failure(operation, MutationOutcome.FORCE_COMPLETED, "Rollover check or handoff failed after mutation", e);
        }
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
        if (closed) {
            throw new IllegalStateException("Storage engine is closed");
        }
    }
}
