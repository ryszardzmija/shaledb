package com.ryszardzmija.shaledb.storage.serialization.io;

import com.ryszardzmija.shaledb.storage.serialization.io.IncompleteRecordException.Origin;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.Objects;
import java.util.function.ObjLongConsumer;

public final class SegmentScanner {
    private final FileChannel readChannel;
    private final RecordReader recordReader;

    public SegmentScanner(FileChannel readChannel, long maxPayloadSize) {
        this.readChannel = Objects.requireNonNull(readChannel);
        this.recordReader = new RecordReader(readChannel, maxPayloadSize);
    }

    /** The validated prefix and captured extent, with a null incomplete tail at exact EOF. */
    public record ScanResult(long validSize, long fileSize, IncompleteRecordException incompleteTail) {}

    /** A physical scan failure that prevents accepting the prefix or recovering the file. */
    public static final class ScanException extends RuntimeException {
        private ScanException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Delivers each validated record and its starting offset in file order.
     * The caller owns the channel and keeps replay state provisional until scan and initialization succeed.
     * Consumer failures propagate directly.
     */
    public ScanResult scan(ObjLongConsumer<ReadResult> consumer) {
        long currentOffset = 0;
        long fileSize = fileSize(currentOffset);
        IncompleteRecordException incompleteTail = null;
        while (currentOffset < fileSize) {
            ReadResult record;
            try {
                record = recordReader.readWithinExtent(new ReadRequest(currentOffset), fileSize);
            } catch (IncompleteRecordException e) {
                if (e.origin() != Origin.FILE_EXTENT) {
                    throw new ScanException("Unexpected EOF at record offset %d within scanned extent %d"
                            .formatted(currentOffset, fileSize), e);
                }
                incompleteTail = e;
                break;
            } catch (RecordIOException e) {
                throw new ScanException("Failed to scan record at offset " + currentOffset, e);
            }
            consumer.accept(record, currentOffset);
            currentOffset = record.nextRecordOffset();
        }

        long finalSize = fileSize(currentOffset);
        if (finalSize != fileSize) {
            throw new ScanException("Failed to verify scanned extent at offset " + currentOffset,
                    new IOException("Segment size changed during scan from %d to %d".formatted(fileSize, finalSize)));
        }
        return new ScanResult(currentOffset, fileSize, incompleteTail);
    }

    private long fileSize(long currentOffset) {
        try {
            return readChannel.size();
        } catch (IOException e) {
            throw new ScanException("Failed to get segment extent while scanning at offset " + currentOffset, e);
        }
    }
}
