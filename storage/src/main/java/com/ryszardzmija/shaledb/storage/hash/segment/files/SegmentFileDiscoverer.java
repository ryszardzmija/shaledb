package com.ryszardzmija.shaledb.storage.hash.segment.files;

import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;

public class SegmentFileDiscoverer {
    private final Path segmentDir;

    public SegmentFileDiscoverer(Path segmentDir) {
        this.segmentDir = Objects.requireNonNull(segmentDir);
    }

    /**
     * Returns validated segment paths ordered by ID, oldest first. An empty result means the
     * directory was read successfully and contains no segment entries.
     */
    public NavigableMap<Long, Path> discoverSegmentFiles() {
        NavigableMap<Long, Path> segments = new TreeMap<>();
        Map<Object, Path> fileKeys = new HashMap<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(segmentDir)) {
            for (Path path : entries) {
                String filename = path.getFileName().toString();
                if (!FileFormatInfo.isSegmentName(filename)) {
                    continue;
                }

                long id;
                try {
                    id = FileFormatInfo.parseId(filename);
                } catch (NumberFormatException e) {
                    throw new SegmentFileDiscoveryException.InvalidLayout("Invalid segment filename: " + path, e);
                }
                BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                if (!attributes.isRegularFile()) {
                    throw new SegmentFileDiscoveryException.InvalidLayout("Segment must be a regular file: " + path);
                }
                Path duplicate = segments.putIfAbsent(id, path);
                if (duplicate != null) {
                    throw new SegmentFileDiscoveryException.InvalidLayout(
                            "Duplicate segment ID " + id + " in " + duplicate + " and " + path);
                }

                Object fileKey = attributes.fileKey();
                if (fileKey != null) {
                    Path alias = fileKeys.putIfAbsent(fileKey, path);
                    if (alias != null) {
                        throw new SegmentFileDiscoveryException.InvalidLayout(
                                "Segment paths refer to the same file: " + alias + " and " + path);
                    }
                }
            }
        } catch (IOException | DirectoryIteratorException | UnsupportedOperationException | SecurityException e) {
            throw new SegmentFileDiscoveryException("Error discovering segment files in " + segmentDir, e);
        }
        return Collections.unmodifiableNavigableMap(segments);
    }
}
