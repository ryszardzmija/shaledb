package com.ryszardzmija.shaledb.storage.hash.index;

import com.ryszardzmija.shaledb.storage.serialization.io.SegmentScanner;
import com.ryszardzmija.shaledb.storage.serialization.record.RecordType;

import java.nio.channels.FileChannel;

public class IndexBuilder {
    private final SegmentScanner scanner;

    public IndexBuilder(FileChannel readChannel, long maxPayloadSize) {
        this.scanner = new SegmentScanner(readChannel, maxPayloadSize);
    }

    public Index build() {
        ReplayResult result = replay();
        SegmentScanner.ScanResult scan = result.scan();
        if (scan.incompleteTail() != null) {
            throw new IndexBuildException("Incomplete record at offset " + scan.validSize(), scan.incompleteTail());
        }
        return result.index();
    }

    /** The provisional index of the validated prefix and the physical scan outcome. */
    public record ReplayResult(Index index, SegmentScanner.ScanResult scan) {}

    public ReplayResult replay() {
        Index index = new HashIndex();
        try {
            SegmentScanner.ScanResult scan = scanner.scan((readResult, offset) -> {
                ByteKey key = new ByteKey(readResult.recordPayload().key());
                if (readResult.recordType() == RecordType.TOMBSTONE) {
                    index.markDeleted(key);
                } else {
                    index.markPresent(key, offset);
                }
            });
            return new ReplayResult(index, scan);
        } catch (SegmentScanner.ScanException e) {
            throw new IndexBuildException(e.getMessage(), e.getCause());
        }
    }
}
