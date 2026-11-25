package com.ryszardzmija.shaledb.storage.hash.segment.model;

import com.ryszardzmija.shaledb.storage.durability.DurabilityConfig;
import com.ryszardzmija.shaledb.storage.durability.DurabilityMode;
import com.ryszardzmija.shaledb.storage.hash.index.ByteKey;
import com.ryszardzmija.shaledb.storage.hash.segment.files.SegmentFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;

import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;

class MutableSegmentTest {
    @TempDir
    Path directory;

    @Test
    void rejectsUnexpectedBytesInAFreshSegmentWithoutRecoveringThem() throws IOException {
        byte[] unexpectedTail = {1, 2, 3};
        Path path = Files.write(directory.resolve("segment.log"), unexpectedTail);
        var config = new SegmentConfig(256, 64);
        var durability = new DurabilityConfig(DurabilityMode.SYNC_EACH_WRITE);
        try (FileChannel reader = FileChannel.open(path, READ);
             FileChannel writer = FileChannel.open(path, WRITE);
             var channels = mockStatic(FileChannel.class)) {
            channels.when(() -> FileChannel.open(path, READ)).thenReturn(reader);
            channels.when(() -> FileChannel.open(path, WRITE)).thenReturn(writer);
            assertThatThrownBy(() -> new MutableSegment(new SegmentFile(1, path), config, durability,
                    new SegmentChannelCleanup())).isInstanceOf(SegmentIOException.class)
                    .hasRootCauseMessage("New segment is not empty");
            assertThat(reader.isOpen()).isFalse();
            assertThat(writer.isOpen()).isFalse();
        }
        assertThat(Files.readAllBytes(path)).containsExactly(unexpectedTail);
    }

    @Test
    void retirementKeepsTheIdentityAndTransfersTheExistingReaderWithoutScanning() throws IOException {
        Path path = Files.createFile(directory.resolve("segment.log"));
        var key = new ByteKey(new byte[] {0, -1});
        var deleted = new ByteKey(new byte[] {-1, 30});
        byte[] value = {0, -128};
        try (FileChannel readChannel = FileChannel.open(path, READ);
             FileChannel writeChannel = FileChannel.open(path, WRITE)) {
            FileChannel reader = mock(FileChannel.class, delegatesTo(readChannel));
            MutableSegment active;
            try (var channels = mockStatic(FileChannel.class)) {
                channels.when(() -> FileChannel.open(path, READ)).thenReturn(reader);
                channels.when(() -> FileChannel.open(path, WRITE)).thenReturn(writeChannel);
                active = new MutableSegment(new SegmentFile(Long.MAX_VALUE, path), new SegmentConfig(256, 64),
                        new DurabilityConfig(DurabilityMode.SYNC_EACH_WRITE), new SegmentChannelCleanup());
            }

            try (active) {
                long oldest = active.appendValue(key.getData(), new byte[0]).writeOffset();
                active.appendValue(deleted.getData(), value);
                long latest = active.appendValue(key.getData(), value).writeOffset();
                long tombstone = active.appendTombstone(deleted.getData()).writeOffset();
                byte[] history = Files.readAllBytes(path);
                clearInvocations(reader);

                try (ImmutableSegment historical = active.retire()) {
                    assertThat(writeChannel.isOpen()).isFalse();
                    assertThatThrownBy(active::retire).isInstanceOf(IllegalStateException.class);
                    active.close();
                    assertThat(readChannel.isOpen()).isTrue();
                    assertThat(historical.id()).isEqualTo(active.id()).isEqualTo(Long.MAX_VALUE);
                    verifyNoInteractions(reader);
                    assertThat(historical.readValue(oldest, key)).isEmpty();
                    assertThat(historical.readValue(latest, key)).containsExactly(value);
                    assertThatThrownBy(() -> historical.readValue(tombstone, deleted))
                            .hasCauseInstanceOf(IndexConsistencyException.class);
                }

                assertThat(readChannel.isOpen()).isFalse();
                assertThat(Files.readAllBytes(path)).containsExactly(history);
            }
        }
    }
}
