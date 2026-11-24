package com.ryszardzmija.shaledb.storage.hash.index;

import com.ryszardzmija.shaledb.storage.durability.DurabilityMode;
import com.ryszardzmija.shaledb.storage.durability.FileSystemSync;
import com.ryszardzmija.shaledb.storage.hash.segment.model.LookupResult;
import com.ryszardzmija.shaledb.storage.hash.segment.model.SegmentReader;
import com.ryszardzmija.shaledb.storage.hash.segment.model.SegmentWriter;
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
import java.util.List;

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

            segmentWriter(channel, new HashIndex()).put(key, value);
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

        Index live = new HashIndex();
        long overwriteOffset;
        long reinsertionOffset;
        try (FileChannel channel = FileChannel.open(path, CREATE_NEW, WRITE)) {
            SegmentWriter writer = segmentWriter(channel, live);
            writer.put(key, new byte[] {42});
            writer.put(collision, new byte[] {43, 44});
            writer.put(deleted, new byte[] {45});
            writer.delete(deleted);
            writer.delete(deleted);
            overwriteOffset = channel.size();
            writer.put(collision, latestValue);
            writer.delete(key);
            reinsertionOffset = channel.size();
            writer.put(key, new byte[0]);
        }

        byte[] history = Files.readAllBytes(path);
        try (FileChannel channel = FileChannel.open(path, READ)) {
            Index rebuilt = new IndexBuilder(channel, MAX_PAYLOAD).build();
            RecordReader reader = new RecordReader(channel, MAX_PAYLOAD);
            for (Index index : List.of(live, rebuilt)) {
                assertPresent(index, reader, key, reinsertionOffset, new byte[0]);
                assertPresent(index, reader, collision, overwriteOffset, latestValue);
                assertThat(index.getKeyOffset(deleted)).isEmpty();
                assertThat(index.isDeleted(deleted)).isTrue();
                assertThat(index.getKeyOffset(missing)).isEmpty();
                assertThat(index.isDeleted(missing)).isFalse();
            }
        }
        assertThat(Files.readAllBytes(path)).containsExactly(history);
    }

    @Test
    void ioFailureAfterAValidPrefixAbortsReplayAndPreservesTheCause() throws IOException {
        Path path = directory.resolve("segment.log");
        long secondOffset;
        try (FileChannel channel = FileChannel.open(path, CREATE_NEW, WRITE)) {
            SegmentWriter writer = segmentWriter(channel, new HashIndex());
            writer.put(new ByteKey(new byte[] {1}), new byte[] {2});
            secondOffset = channel.size();
            writer.put(new ByteKey(new byte[] {3}), new byte[] {4});
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

    private static SegmentWriter segmentWriter(FileChannel channel, Index index) {
        return new SegmentWriter(new RecordWriter(channel, new FileSystemSync(), MAX_PAYLOAD), index,
                DurabilityMode.SYNC_EACH_WRITE);
    }

    private static void assertPresent(Index index, RecordReader reader, ByteKey key, long offset, byte[] value) {
        assertThat(index.isDeleted(key)).isFalse();
        assertThat(index.getKeyOffset(key)).contains(offset);
        assertThat(new SegmentReader(reader, index).get(key)).isInstanceOfSatisfying(LookupResult.Present.class,
                result -> assertThat(result.value()).containsExactly(value));
    }
}
