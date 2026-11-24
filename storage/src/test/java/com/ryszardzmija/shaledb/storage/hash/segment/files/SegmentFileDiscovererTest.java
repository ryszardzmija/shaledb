package com.ryszardzmija.shaledb.storage.hash.segment.files;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SegmentFileDiscovererTest {
    @TempDir
    Path directory;

    @Test
    void discoversOnlySegmentEntriesAndOrdersTheirNumericIdsAcrossNameFormats() throws IOException {
        Path latest = Files.createFile(directory.resolve("segment_0000000000000000100.log"));
        Path oldest = Files.createFile(directory.resolve("segment_2.log"));
        Path localized = Files.createFile(directory.resolve("segment_٠٠٠٠٠٩.log"));
        Path padded = Files.createFile(directory.resolve("segment_000010.log"));
        Files.createFile(directory.resolve("metadata.log"));
        Files.createDirectory(directory.resolve("SEGMENT_7.log"));

        var segments = new SegmentFileDiscoverer(directory).discoverSegmentFiles();

        assertThat(segments.entrySet()).containsExactly(
                Map.entry(2L, oldest), Map.entry(9L, localized), Map.entry(10L, padded), Map.entry(100L, latest));
    }

    @Test
    void returnsAnEmptyLayoutWhenOnlyUnrelatedEntriesExist() throws IOException {
        Files.createFile(directory.resolve(".lock"));
        Files.createDirectory(directory.resolve("metadata"));

        assertThat(new SegmentFileDiscoverer(directory).discoverSegmentFiles()).isEmpty();
    }

    @Test
    void rejectsMalformedSegmentNamesAndPreservesTheirFiles() throws IOException {
        for (String name : List.of("segment_.log", "segment_0.log", "segment_-1.log", "segment_+1.log",
                "segment_9223372036854775808.log", "segment_1.LOG", "segment_1.log.bak", "segment_1 .log",
                "segment_1.log ", "segment_٠٠٠٠٠1.log", "segment_١.log", "segment_².log",
                "segment_٢١٤٧٤٨٣٦٤٨.log", "segment_٠٠٠٠٠٠١.log")) {
            Path testDirectory = Files.createTempDirectory(directory, "layout");
            Path malformed = Files.write(testDirectory.resolve(name), new byte[] {9});

            assertThatThrownBy(new SegmentFileDiscoverer(testDirectory)::discoverSegmentFiles)
                    .isInstanceOf(SegmentFileDiscoveryException.InvalidLayout.class)
                    .hasMessageContaining(malformed.toString())
                    .hasCauseInstanceOf(NumberFormatException.class);
            assertThat(Files.readAllBytes(malformed)).containsExactly(9);
        }
    }

    @Test
    void rejectsARegularFileAndAMissingPathAsDatabaseDirectories() throws IOException {
        Path missing = directory.resolve("missing");
        Path regularFile = Files.createFile(directory.resolve("ordinary-file"));

        assertThatThrownBy(new SegmentFileDiscoverer(missing)::discoverSegmentFiles)
                .isInstanceOf(SegmentFileDiscoveryException.class)
                .hasRootCauseInstanceOf(NoSuchFileException.class);
        assertThatThrownBy(new SegmentFileDiscoverer(regularFile)::discoverSegmentFiles)
                .isInstanceOf(SegmentFileDiscoveryException.class)
                .hasRootCauseInstanceOf(NotDirectoryException.class);
    }

    @Test
    void rejectsDirectoriesAndSymlinksOccupyingSegmentPaths() throws IOException {
        Path disguisedDirectory = Files.createDirectory(directory.resolve("segment_1.log"));
        assertUnsupportedEntry(disguisedDirectory);
        assertThat(disguisedDirectory).isDirectory();

        Path otherDirectory = Files.createDirectory(directory.resolve("other"));
        Path regularFile = Files.write(otherDirectory.resolve("target"), new byte[] {8});
        Path link = Files.createSymbolicLink(otherDirectory.resolve("segment_1.log"), regularFile);
        assertUnsupportedEntry(link);
        assertThat(Files.readSymbolicLink(link)).isEqualTo(regularFile);
        assertThat(Files.readAllBytes(regularFile)).containsExactly(8);
    }

    @Test
    void rejectsHardLinkedSegmentsWithDifferentIds() throws IOException {
        Path original = Files.write(directory.resolve("segment_1.log"), new byte[] {7});
        Path alias = Files.createLink(directory.resolve("segment_2.log"), original);

        assertThatThrownBy(new SegmentFileDiscoverer(directory)::discoverSegmentFiles)
                .isInstanceOf(SegmentFileDiscoveryException.InvalidLayout.class)
                .hasMessageContaining("same file")
                .hasMessageContaining(original.toString())
                .hasMessageContaining(alias.toString());
        assertThat(Files.isSameFile(original, alias)).isTrue();
        assertThat(Files.readAllBytes(original)).containsExactly(7);
    }

    @Test
    void closesDirectoryStreamAndPreservesIterationAndCloseFailures() throws IOException {
        IOException readFailure = new IOException("iteration failed");
        IOException closeFailure = new IOException("close failed");
        DirectoryIteratorException iterationFailure = new DirectoryIteratorException(readFailure);
        @SuppressWarnings("unchecked")
        DirectoryStream<Path> stream = mock(DirectoryStream.class);
        @SuppressWarnings("unchecked")
        Iterator<Path> iterator = mock(Iterator.class);
        when(stream.iterator()).thenReturn(iterator);
        when(iterator.hasNext()).thenThrow(iterationFailure);
        doThrow(closeFailure).when(stream).close();

        try (MockedStatic<Files> files = mockStatic(Files.class)) {
            files.when(() -> Files.newDirectoryStream(directory)).thenReturn(stream);
            assertThatThrownBy(new SegmentFileDiscoverer(directory)::discoverSegmentFiles)
                    .isInstanceOf(SegmentFileDiscoveryException.class)
                    .hasCause(iterationFailure)
                    .hasRootCause(readFailure);
        }
        assertThat(iterationFailure.getSuppressed()).containsExactly(closeFailure);
        verify(stream).close();
    }

    private static void assertUnsupportedEntry(Path path) {
        assertThatThrownBy(new SegmentFileDiscoverer(path.getParent())::discoverSegmentFiles)
                .isInstanceOf(SegmentFileDiscoveryException.InvalidLayout.class)
                .hasMessageContaining("regular file")
                .hasMessageContaining(path.toString());
    }
}
