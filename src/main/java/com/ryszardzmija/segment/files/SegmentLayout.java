package com.ryszardzmija.segment.files;

import java.nio.file.Path;
import java.util.List;

public record SegmentLayout(List<Path> immutableSegmentPaths, Path mutableSegmentPath) {
}
