package com.ryszardzmija.shaledb.storage.hash.segment.files;

import java.util.List;

/**
 * Describes the segment files to open.
 * @param historicalFiles historical segments in ascending numeric ID order
 * @param activeFile current active segment
 */
public record SegmentLayout(List<SegmentFile> historicalFiles, SegmentFile activeFile) {
}
