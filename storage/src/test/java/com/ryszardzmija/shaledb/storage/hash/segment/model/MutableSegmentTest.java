package com.ryszardzmija.shaledb.storage.hash.segment.model;

import com.ryszardzmija.shaledb.storage.durability.DurabilityConfig;
import com.ryszardzmija.shaledb.storage.durability.DurabilityMode;
import com.ryszardzmija.shaledb.storage.hash.index.ByteKey;
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
    void retirementTransfersTheExistingReadChannelAndLocalStateWithoutReadingHistory() throws IOException {
        Path path = Files.createFile(directory.resolve("segment.log"));
        var key = new ByteKey(new byte[] {0, -1});
        var deleted = new ByteKey(new byte[] {-1, 30});
        var missing = new ByteKey(new byte[] {10});
        byte[] value = {0, -128};
        try (FileChannel readChannel = FileChannel.open(path, READ);
             FileChannel writeChannel = FileChannel.open(path, WRITE)) {
            FileChannel reader = mock(FileChannel.class, delegatesTo(readChannel));
            MutableSegment active;
            try (var channels = mockStatic(FileChannel.class)) {
                channels.when(() -> FileChannel.open(path, READ)).thenReturn(reader);
                channels.when(() -> FileChannel.open(path, WRITE)).thenReturn(writeChannel);
                active = new MutableSegment(path, new SegmentConfig(256, 64),
                        new DurabilityConfig(DurabilityMode.SYNC_EACH_WRITE));
            }

            try (active) {
                active.put(key, new byte[0]);
                active.put(deleted, value);
                active.put(key, value);
                active.delete(deleted);
                byte[] history = Files.readAllBytes(path);
                clearInvocations(reader);

                try (ImmutableSegment historical = active.retire()) {
                    assertThat(writeChannel.isOpen()).isFalse();
                    assertThatThrownBy(active::retire).isInstanceOf(IllegalStateException.class);
                    active.close();
                    assertThat(readChannel.isOpen()).isTrue();
                    assertThat(historical.get(deleted)).isInstanceOf(LookupResult.Deleted.class);
                    assertThat(historical.get(missing)).isInstanceOf(LookupResult.NotPresent.class);
                    verifyNoInteractions(reader);
                    assertThat(historical.get(key)).isInstanceOfSatisfying(LookupResult.Present.class,
                            result -> assertThat(result.value()).containsExactly(value));
                }

                assertThat(readChannel.isOpen()).isFalse();
                assertThat(Files.readAllBytes(path)).containsExactly(history);
            }
        }
    }
}
