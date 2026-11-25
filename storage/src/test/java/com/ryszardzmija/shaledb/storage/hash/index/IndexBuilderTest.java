package com.ryszardzmija.shaledb.storage.hash.index;

import com.ryszardzmija.shaledb.storage.durability.DurabilityConfig;
import com.ryszardzmija.shaledb.storage.durability.FileSystemSync;
import com.ryszardzmija.shaledb.storage.hash.segment.model.LookupResult;
import com.ryszardzmija.shaledb.storage.hash.segment.model.MutableSegment;
import com.ryszardzmija.shaledb.storage.hash.segment.model.SegmentConfig;
import com.ryszardzmija.shaledb.storage.hash.segment.model.SegmentReader;
import com.ryszardzmija.shaledb.storage.hash.segment.model.SegmentWriter;
import com.ryszardzmija.shaledb.storage.serialization.io.IncompleteRecordException;
import com.ryszardzmija.shaledb.storage.serialization.io.RecordIOException;
import com.ryszardzmija.shaledb.storage.serialization.io.RecordReader;
import com.ryszardzmija.shaledb.storage.serialization.io.RecordWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.ryszardzmija.shaledb.storage.durability.DurabilityMode.SYNC_EACH_WRITE;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IndexBuilderTest {
    private static final long MAX_PAYLOAD = 64;

    @TempDir
    Path directory;

    @Test
    void rebuildsEmptyAndSingleRecordSegmentsAtTheirExactEof() throws IOException {
        ByteKey key = new ByteKey(new byte[] {0, -128});
        byte[] value = {-1};
        try (FileChannel channel = FileChannel.open(directory.resolve("segment.log"), CREATE_NEW, READ, WRITE)) {
            IndexBuilder builder = new IndexBuilder(channel, MAX_PAYLOAD);
            Index empty = builder.build();
            assertThat(empty.isDeleted(key)).isFalse();
            assertThat(empty.getKeyOffset(key)).isEmpty();

            segmentWriter(channel).appendValue(key.getData(), value);
            Index rebuilt = builder.build();
            assertPresent(rebuilt, new RecordReader(channel, MAX_PAYLOAD), key, 0, value);
        }
    }

    @Test
    void replayMatchesTheLiveIndexAndPhysicalOffsetsAfterOverwritesDeletesAndReinsertion() throws IOException {
        ByteKey key = new ByteKey(new byte[] {0, -1});
        ByteKey collision = new ByteKey(new byte[] {-1, 30});
        ByteKey deleted = new ByteKey(new byte[] {0});
        ByteKey missing = new ByteKey(new byte[] {0, -1, 0});
        assertThat(collision.hashCode()).isEqualTo(key.hashCode());
        byte[] latestValue = {0, -128, 3};
        Path path = directory.resolve("segment.log");

        Files.createFile(path);
        long overwriteOffset;
        long reinsertionOffset;
        try (MutableSegment live = new MutableSegment(path, new SegmentConfig(256, MAX_PAYLOAD),
                new DurabilityConfig(SYNC_EACH_WRITE))) {
            live.put(key, new byte[] {42});
            live.put(collision, new byte[] {43, 44});
            live.put(deleted, new byte[] {45});
            live.delete(deleted);
            live.delete(deleted);
            overwriteOffset = Files.size(path);
            live.put(collision, latestValue);
            live.delete(key);
            reinsertionOffset = Files.size(path);
            live.put(key, new byte[0]);

            assertThat(live.get(key)).isInstanceOfSatisfying(LookupResult.Present.class,
                    result -> assertThat(result.value()).isEmpty());
            assertThat(live.get(collision)).isInstanceOfSatisfying(LookupResult.Present.class,
                    result -> assertThat(result.value()).containsExactly(latestValue));
            assertThat(live.get(deleted)).isInstanceOf(LookupResult.Deleted.class);
            assertThat(live.get(missing)).isInstanceOf(LookupResult.NotPresent.class);
        }

        byte[] history = Files.readAllBytes(path);
        try (FileChannel channel = FileChannel.open(path, READ)) {
            Index rebuilt = new IndexBuilder(channel, MAX_PAYLOAD).build();
            RecordReader reader = new RecordReader(channel, MAX_PAYLOAD);
            assertPresent(rebuilt, reader, key, reinsertionOffset, new byte[0]);
            assertPresent(rebuilt, reader, collision, overwriteOffset, latestValue);
            assertThat(rebuilt.getKeyOffset(deleted)).isEmpty();
            assertThat(rebuilt.isDeleted(deleted)).isTrue();
            assertThat(rebuilt.getKeyOffset(missing)).isEmpty();
            assertThat(rebuilt.isDeleted(missing)).isFalse();
        }
        assertThat(Files.readAllBytes(path)).containsExactly(history);
    }

    @Test
    void replaysTheValidatedPrefixWhileStrictRebuildRejectsAnIncompleteTail() throws IOException {
        ByteKey key = new ByteKey(new byte[] {0, -1});
        Path path = directory.resolve("segment.log");
        try (FileChannel channel = FileChannel.open(path, CREATE_NEW, READ, WRITE)) {
            SegmentWriter writer = segmentWriter(channel);
            writer.appendValue(key.getData(), new byte[0]);
            long boundary = channel.size();
            writer.appendTombstone(key.getData());
            channel.truncate(channel.size() - 1);
            byte[] history = Files.readAllBytes(path);

            IndexBuilder builder = new IndexBuilder(channel, MAX_PAYLOAD);
            var replay = builder.replay();
            assertPresent(replay.index(), new RecordReader(channel, MAX_PAYLOAD), key, 0, new byte[0]);
            assertThat(replay.scan().validSize()).isEqualTo(boundary);
            assertThat(replay.scan().fileSize()).isEqualTo(history.length);
            assertThat(replay.scan().incompleteTail().origin()).isEqualTo(IncompleteRecordException.Origin.FILE_EXTENT);
            assertThatThrownBy(builder::build)
                    .isInstanceOfSatisfying(IndexBuildException.class, failure ->
                            assertThat(failure.getCause()).isInstanceOfSatisfying(IncompleteRecordException.class,
                                    cause -> assertThat(cause.recordOffset()).isEqualTo(boundary)));
            assertThat(Files.readAllBytes(path)).containsExactly(history);
        }
    }

    @Test
    void ioFailureAfterAValidPrefixAbortsReplayAndPreservesTheCause() throws IOException {
        Path path = directory.resolve("segment.log");
        long secondOffset;
        try (FileChannel channel = FileChannel.open(path, CREATE_NEW, WRITE)) {
            SegmentWriter writer = segmentWriter(channel);
            writer.appendValue(new byte[] {1}, new byte[] {2});
            secondOffset = channel.size();
            writer.appendValue(new byte[] {3}, new byte[] {4});
        }

        byte[] history = Files.readAllBytes(path);
        IOException readFailure = new IOException("Injected positional read failure");
        try (FileChannel channel = FileChannel.open(path, READ)) {
            FileChannel failingChannel = mock(FileChannel.class);
            when(failingChannel.size()).thenReturn(channel.size());
            when(failingChannel.read(any(ByteBuffer.class), anyLong())).thenAnswer(call ->
                    channel.read(call.getArgument(0), call.<Long>getArgument(1)));
            doThrow(readFailure).when(failingChannel).read(any(ByteBuffer.class), eq(secondOffset));

            assertThatThrownBy(() -> new IndexBuilder(failingChannel, MAX_PAYLOAD).build())
                    .isInstanceOf(IndexBuildException.class)
                    .hasCauseInstanceOf(RecordIOException.class)
                    .hasRootCause(readFailure);
        }
        assertThat(Files.readAllBytes(path)).containsExactly(history);
    }

    private static SegmentWriter segmentWriter(FileChannel channel) {
        return new SegmentWriter(new RecordWriter(channel, new FileSystemSync(), MAX_PAYLOAD), SYNC_EACH_WRITE);
    }

    private static void assertPresent(Index index, RecordReader reader, ByteKey key, long offset, byte[] value) {
        assertThat(index.isDeleted(key)).isFalse();
        assertThat(index.getKeyOffset(key)).contains(offset);
        assertThat(new SegmentReader(reader).readValue(offset, key)).containsExactly(value);
    }
}
