package com.ryszardzmija.shaledb.storage.hash.segment.files;

import com.ryszardzmija.shaledb.storage.durability.DurabilityException;
import com.ryszardzmija.shaledb.storage.durability.FileSystemSync;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class SegmentFileFactoryTest {
    private static final String FIRST_FILENAME = "segment_0000000000000000001.log";
    private static final String SECOND_FILENAME = "segment_0000000000000000002.log";

    @TempDir
    Path tempDir;

    FileSystemSync fileSystemSync;
    SegmentFileFactory factory;

    @BeforeEach
    void setUp() {
        fileSystemSync = mock(FileSystemSync.class);
        factory = new SegmentFileFactory(tempDir, fileSystemSync, 0);
    }

    @Test
    @ResourceLock(Resources.LOCALE)
    void createsFirstSegmentFileInEmptyDirectory() throws IOException {
        Locale original = Locale.getDefault(Locale.Category.FORMAT);
        Path segmentFile;
        try {
            Locale.setDefault(Locale.Category.FORMAT, Locale.forLanguageTag("ar-EG-u-nu-arab"));
            segmentFile = factory.createSegmentFile().path();
        } finally {
            Locale.setDefault(Locale.Category.FORMAT, original);
        }

        assertThat(segmentFile.getParent()).isEqualTo(tempDir);
        assertThat(segmentFile.getFileName().toString()).isEqualTo(FIRST_FILENAME);
        assertThat(segmentFile).exists().isRegularFile();

        try (Stream<Path> files = Files.list(tempDir)) {
            assertThat(files).containsExactlyInAnyOrder(segmentFile);
        }
    }

    @Test
    void createsSegmentFileAfterHighestExistingId() throws IOException {
        Files.createFile(tempDir.resolve(FIRST_FILENAME));
        Files.createFile(tempDir.resolve("segment_17.log"));
        long latestId = new SegmentFileDiscoverer(tempDir).discoverSegmentFiles().lastKey();
        factory = new SegmentFileFactory(tempDir, fileSystemSync, latestId);

        SegmentFile segment = factory.createSegmentFile();
        Path segmentFile = segment.path();

        assertThat(segment.id()).isEqualTo(18);
        assertThat(segmentFile.getFileName().toString()).isEqualTo("segment_0000000000000000018.log");
        assertThat(segmentFile).exists().isRegularFile();
    }

    @Test
    void forcesTheOpenFileBeforeForcingItsDirectoryAndClosesTheChannel() {
        doAnswer(call -> {
            FileChannel channel = call.getArgument(0);
            assertThat(channel.isOpen()).isTrue();
            return null;
        }).when(fileSystemSync).forceFile(any(FileChannel.class));

        factory.createSegmentFile();

        var channel = ArgumentCaptor.forClass(FileChannel.class);
        var ordering = inOrder(fileSystemSync);
        ordering.verify(fileSystemSync).forceFile(channel.capture());
        ordering.verify(fileSystemSync).forceDirectory(tempDir);
        assertThat(channel.getValue().isOpen()).isFalse();
    }

    @Test
    void doesNotReuseIdAfterFailedDirectoryFlush() {
        doThrow(new DurabilityException("error", new IOException("error")))
                .doNothing()
                .doNothing()
                .when(fileSystemSync)
                .forceDirectory(tempDir);
        Path segmentFile = tempDir.resolve(FIRST_FILENAME);

        assertThatThrownBy(() -> factory.createSegmentFile())
                .isInstanceOf(SegmentFileException.class)
                .hasCauseInstanceOf(DurabilityException.class)
                .hasMessage("Failed to flush segment file creation: " + segmentFile);

        Path nextSegmentFile = factory.createSegmentFile().path().getFileName();

        assertThat(nextSegmentFile.toString()).isEqualTo(SECOND_FILENAME);
        verify(fileSystemSync, times(3)).forceDirectory(tempDir);
    }

    @Test
    void usesMonotonicallyIncreasingIds() {
        Path first = factory.createSegmentFile().path().getFileName();
        Path second = factory.createSegmentFile().path().getFileName();

        assertThat(first.toString()).isEqualTo(FIRST_FILENAME);
        assertThat(second.toString()).isEqualTo(SECOND_FILENAME);
    }

    @Test
    void deletesCreatedSegmentFileAfterFailedDirectoryFlush() throws IOException {
        doThrow(new DurabilityException("error", new IOException("error")))
                .doNothing()
                .when(fileSystemSync)
                .forceDirectory(tempDir);
        Path segmentFile = tempDir.resolve(FIRST_FILENAME);

        assertThatThrownBy(() -> factory.createSegmentFile())
                .isInstanceOf(SegmentFileException.class)
                .hasCauseInstanceOf(DurabilityException.class)
                .hasMessage("Failed to flush segment file creation: " + segmentFile);

        try (Stream<Path> files = Files.list(tempDir)) {
            assertThat(files.toList()).isEmpty();
        }

        verify(fileSystemSync, times(2)).forceDirectory(tempDir);
    }

    @Test
    void closesAndRemovesTheNewFileAfterFailedFileForce() throws IOException {
        Path existing = Files.write(tempDir.resolve(FIRST_FILENAME), new byte[] {5});
        Path next = tempDir.resolve(SECOND_FILENAME);
        factory = new SegmentFileFactory(tempDir, fileSystemSync, 1);
        var failure = new DurabilityException("file force failed", new IOException("I/O error"));
        doThrow(failure).when(fileSystemSync).forceFile(any(FileChannel.class));

        assertThatThrownBy(factory::createSegmentFile)
                .isInstanceOf(SegmentFileException.class)
                .hasCause(failure);

        var channel = ArgumentCaptor.forClass(FileChannel.class);
        verify(fileSystemSync).forceFile(channel.capture());
        assertThat(channel.getValue().isOpen()).isFalse();
        verify(fileSystemSync).forceDirectory(tempDir);
        assertThat(next).doesNotExist();
        assertThat(Files.readAllBytes(existing)).containsExactly(5);
    }

    @Test
    void refusesToReplaceAnyExistingEntryAtTheAllocatedPath() throws IOException {
        Path nonempty = Files.write(tempDir.resolve(FIRST_FILENAME), new byte[] {5});
        Path empty = Files.createFile(tempDir.resolve(SECOND_FILENAME));
        Path directory = Files.createDirectory(tempDir.resolve("segment_0000000000000000003.log"));
        Path missingTarget = tempDir.resolve("missing");
        Path symlink = Files.createSymbolicLink(tempDir.resolve("segment_0000000000000000004.log"), missingTarget);

        for (Path path : new Path[] {nonempty, empty, directory, symlink}) {
            assertThatThrownBy(factory::createSegmentFile)
                    .isInstanceOf(SegmentFileException.class)
                    .hasRootCauseInstanceOf(FileAlreadyExistsException.class)
                    .hasMessageContaining(path.toString());
        }
        verifyNoInteractions(fileSystemSync);
        assertThat(Files.readAllBytes(nonempty)).containsExactly(5);
        assertThat(Files.size(empty)).isZero();
        assertThat(directory).isDirectory();
        assertThat(Files.readSymbolicLink(symlink)).isEqualTo(missingTarget);
        assertThat(missingTarget).doesNotExist();
    }

    @Test
    void allocatesTheMaximumIdOnceAndThenReportsExhaustion() {
        factory = new SegmentFileFactory(tempDir, fileSystemSync, Long.MAX_VALUE - 1);
        SegmentFile last = factory.createSegmentFile();

        assertThat(last.id()).isEqualTo(Long.MAX_VALUE);
        assertThat(last.path()).isEqualTo(tempDir.resolve("segment_9223372036854775807.log")).isRegularFile();
        assertThatThrownBy(factory::createSegmentFile)
                .isInstanceOf(SegmentFileException.IdExhausted.class)
                .hasMessageContaining(Long.toString(Long.MAX_VALUE));
        verify(fileSystemSync).forceFile(any(FileChannel.class));
        verify(fileSystemSync).forceDirectory(tempDir);
    }

    @Test
    void rejectsNegativeAllocationStateAndNonpositiveFilenameIds() {
        assertThatIllegalArgumentException().isThrownBy(() -> new SegmentFileFactory(tempDir, fileSystemSync, -1));
        assertThatIllegalArgumentException().isThrownBy(() -> FileFormatInfo.filename(0));
        assertThatIllegalArgumentException().isThrownBy(() -> FileFormatInfo.filename(-1));
        verifyNoInteractions(fileSystemSync);
    }
}
