package com.ryszardzmija.storage.hash;

import com.ryszardzmija.storage.StorageEngine;
import com.ryszardzmija.storage.StorageEngineException;
import com.ryszardzmija.storage.hash.index.ByteKey;
import com.ryszardzmija.storage.hash.segment.files.*;
import com.ryszardzmija.storage.hash.segment.loader.LoadedSegments;
import com.ryszardzmija.storage.hash.segment.loader.SegmentLoader;
import com.ryszardzmija.storage.hash.segment.loader.SegmentLoadingException;
import com.ryszardzmija.storage.hash.segment.model.ImmutableSegment;
import com.ryszardzmija.storage.hash.segment.model.MutableSegment;
import com.ryszardzmija.storage.hash.segment.model.SegmentIOException;
import com.ryszardzmija.storage.hash.segment.rollover.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.*;

public class HashIndexEngine implements StorageEngine {
    private static final Logger logger = LoggerFactory.getLogger(HashIndexEngine.class);

    private final RolloverPolicy rolloverPolicy;
    private final RolloverHandler rolloverHandler;

    private final Deque<ImmutableSegment> immutableSegments;
    private MutableSegment mutableSegment;

    public HashIndexEngine(Path segmentDir) {
        Objects.requireNonNull(segmentDir);

        try {
            SegmentFileFactory segmentFileFactory = new SegmentFileFactory(segmentDir);
            this.rolloverPolicy = new SizeBasedRolloverPolicy();
            this.rolloverHandler = new RolloverHandler(segmentFileFactory);
            SegmentFileDiscoverer segmentFileDiscoverer = new SegmentFileDiscoverer(segmentDir);
            SegmentLoader segmentLoader = new SegmentLoader();

            SegmentLayout segmentLayout = segmentFileDiscoverer.getSegmentFiles(segmentFileFactory);
            LoadedSegments loadedSegments = segmentLoader.loadSegments(segmentLayout);
            this.immutableSegments = loadedSegments.immutableSegments();
            this.mutableSegment = loadedSegments.mutableSegment();
        } catch (SegmentFileException | SegmentFileDiscoveryException | SegmentLoadingException e) {
            throw new StorageEngineException("Failed to initialize hash index storage engine", e);
        }
    }

    @Override
    public void put(byte[] key, byte[] value) {
        if (key == null || key.length == 0) {
            throw new IllegalArgumentException("Key must not be null or empty");
        }
        if (value == null) {
            throw new IllegalArgumentException("Value must not be null");
        }

        try {
            mutableSegment.put(new ByteKey(key), value);
        } catch (SegmentIOException e) {
            throw new StorageEngineException("Failed to write segment", e);
        }

        try {
            handleRollover();
        } catch (RolloverException e) {
            // TODO: Decide whether to implement retry logic or wait for the next
            // TODO: write for a retry. If the rollover keeps failing we should
            // TODO: bound the size of the segment and probably refuse writes after
            // TODO: some threshold is reached.
            logger.warn("Rollover failed after successful write", e);
        }
    }

    @Override
    public Optional<byte[]> get(byte[] key) {
        if (key == null || key.length == 0) {
            throw new IllegalArgumentException("Key must not be null or empty");
        }

        ByteKey byteKey = new ByteKey(key);

        try {
            if (mutableSegment.foundDeleted(byteKey)) {
                return Optional.empty();
            }
            Optional<byte[]> result = mutableSegment.get(byteKey);
            if (result.isPresent()) {
                return result;
            }

            for (ImmutableSegment segment : immutableSegments) {
                if (segment.foundDeleted(byteKey)) {
                    return Optional.empty();
                }
                result = segment.get(byteKey);
                if (result.isPresent()) {
                    return result;
                }
            }

            return Optional.empty();
        } catch (SegmentIOException e) {
            throw new StorageEngineException("Failed to read segment", e);
        }
    }

    @Override
    public void delete(byte[] key) {
        if (key == null || key.length == 0) {
            throw new IllegalArgumentException("Key must not be null or empty");
        }

        try {
            mutableSegment.delete(new ByteKey(key));
        } catch (SegmentIOException e) {
            throw new StorageEngineException("Failed to write segment", e);
        }

        try {
            handleRollover();
        } catch (RolloverException e) {
            logger.warn("Rollover failed after successful delete", e);
        }
    }

    @Override
    public void close() {
        mutableSegment.close();

        for (ImmutableSegment segment : immutableSegments) {
            segment.close();
        }
    }

    private void handleRollover() {
        if (rolloverPolicy.shouldRollover(mutableSegment)) {
            RolloverResult rolloverResult = rolloverHandler.rollOver(mutableSegment);
            mutableSegment = rolloverResult.newMutableSegment();
            immutableSegments.addFirst(rolloverResult.newImmutableSegment());
        }
    }
}
