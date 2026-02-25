package com.ryszardzmija.segment;

import com.ryszardzmija.index.ByteKey;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

public class SegmentManager implements AutoCloseable {
    private static final int FIRST_SEGMENT_ID = 1;
    private static final String SEGMENT_FILENAME_FORMAT = "segment_%06d.log";
    private static final long MAX_SEGMENT_SIZE = 1024*1024;  // 1MB

    private final Path dataPath;
    private final Deque<ImmutableSegment> immutableSegments;

    private MutableSegment mutableSegment;
    private int lastSegmentId;

    public SegmentManager(Path dataPath) throws IOException {
        this.dataPath = Objects.requireNonNull(dataPath);
        this.immutableSegments = new ArrayDeque<>();
        List<Path> segmentFiles = getSegmentFiles(dataPath);
        readSegments(segmentFiles);
    }

    public void put(byte[] key, byte[] value) throws IOException {
        mutableSegment.put(new ByteKey(key), value);

        if (mutableSegment.size() >= MAX_SEGMENT_SIZE) {
            rollOver();
        }
    }

    public Optional<byte[]> get(byte[] key) throws IOException {
        ByteKey byteKey = new ByteKey(key);

        Optional<byte[]> result = mutableSegment.get(byteKey);
        if (result.isPresent()) {
            return result;
        }

        for (ImmutableSegment segment : immutableSegments) {
            result = segment.get(byteKey);
            if (result.isPresent()) {
                return result;
            }
        }

        return Optional.empty();
    }

    @Override
    public void close() throws Exception {
        Exception firstException = null;

        try {
            mutableSegment.close();
        } catch (Exception e) {
            firstException = e;
        }

        for (ImmutableSegment segment : immutableSegments) {
            try {
                segment.close();
            } catch (Exception e) {
                if (firstException == null) {
                    firstException = e;
                } else {
                    firstException.addSuppressed(e);
                }
            }
        }

        if (firstException != null) {
            throw firstException;
        }
    }

    private void readSegments(List<Path> segmentFiles) throws IOException {
        // Note: immutable segments are added to the front starting from the oldest one
        // so the front of the deque will hold the newest segment after insertion.
        for (int i = 0; i < segmentFiles.size() - 1; ++i) {
            immutableSegments.addFirst(new ImmutableSegment(segmentFiles.get(i)));
        }
        if (!segmentFiles.isEmpty()) {
            mutableSegment = new MutableSegment(segmentFiles.getLast());
            lastSegmentId = extractId(segmentFiles.getLast());
        } else {
            Path segmentPath = createSegmentFile(dataPath, FIRST_SEGMENT_ID);
            mutableSegment = new MutableSegment(segmentPath);
            lastSegmentId = FIRST_SEGMENT_ID;
        }
    }

    private List<Path> getSegmentFiles(Path dataPath) throws IOException {
        try (Stream<Path> files = Files.list(dataPath)) {
            return files
                    .filter(p -> p.getFileName().toString().matches("segment_\\d+\\.log"))
                    .sorted(Comparator.comparingInt(this::extractId))
                    .toList();
        }
    }

    private int extractId(Path segmentPath) {
        String filename = segmentPath.getFileName().toString();
        int start = filename.indexOf('_');
        int end = filename.indexOf('.');
        String id = filename.substring(start + 1, end);
        return Integer.parseInt(id);
    }

    private Path createSegmentFile(Path dataPath, int id) throws IOException {
        Path segmentPath = dataPath.resolve(String.format(SEGMENT_FILENAME_FORMAT, id));
        Files.createFile(segmentPath);
        return segmentPath;
    }

    private void rollOver() throws IOException {
        mutableSegment.close();
        immutableSegments.addFirst(new ImmutableSegment(mutableSegment.getPath()));
        ++lastSegmentId;
        Path path = createSegmentFile(dataPath, lastSegmentId);
        mutableSegment = new MutableSegment(path);
    }
}
