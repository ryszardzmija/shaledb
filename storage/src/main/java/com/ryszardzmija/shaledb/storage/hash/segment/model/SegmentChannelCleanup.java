package com.ryszardzmija.shaledb.storage.hash.segment.model;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Takes ownership only of segment channels that remain open after a close failure. */
public final class SegmentChannelCleanup {
    private record PendingChannel(Path path, FileChannel channel) {}

    private final List<PendingChannel> pending = new ArrayList<>();

    public boolean hasPendingChannels() {
        return !pending.isEmpty();
    }

    public void close(Path path, FileChannel... channels) throws IOException {
        Throwable failure = null;
        for (FileChannel channel : channels) {
            if (channel == null) {
                continue;
            }
            try {
                channel.close();
            } catch (IOException | RuntimeException | Error e) {
                if (channel.isOpen()) {
                    pending.add(new PendingChannel(path, channel));
                }
                failure = suppress(failure, e);
            }
        }
        switch (failure) {
            case IOException e -> throw e;
            case RuntimeException e -> throw e;
            case Error e -> throw e;
            case null -> { }
            default -> throw new AssertionError(failure);
        }
    }

    public void closeAfterFailure(Path path, Throwable failure, FileChannel... channels) {
        try {
            close(path, channels);
        } catch (IOException | RuntimeException | Error e) {
            suppress(failure, e);
        }
    }

    /** Retries channels left open by earlier cleanup attempts, in the order they were retained. */
    public Throwable retryPending(Throwable failure) {
        var iterator = pending.iterator();
        while (iterator.hasNext()) {
            PendingChannel entry = iterator.next();
            try {
                if (entry.channel().isOpen()) {
                    entry.channel().close();
                }
            } catch (IOException | RuntimeException | Error e) {
                failure = suppress(failure, e instanceof IOException
                        ? new SegmentIOException("Failed to close segment " + entry.path(), e) : e);
                if (entry.channel().isOpen()) {
                    continue;
                }
            }
            iterator.remove();
        }
        return failure;
    }

    public static Throwable suppress(Throwable primary, Throwable secondary) {
        if (primary == null) {
            return secondary;
        }
        if (primary != secondary) {
            primary.addSuppressed(secondary);
        }
        return primary;
    }
}
