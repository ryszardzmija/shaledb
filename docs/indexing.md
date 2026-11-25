# Indexing

Each segment owns one in-memory hash index. A record offset is a nonnegative byte position within that segment. The physical reader and writer operate independently of the index. The engine reconstructs local indexes from segment files at startup. [HashIndex](../storage/src/main/java/com/ryszardzmija/shaledb/storage/hash/index/HashIndex.java)

## Key identity and local state

`ByteKey` copies the key bytes and uses content-based hashing and equality. Equal byte sequences supplied in different arrays identify the same key. Distinct keys with the same hash stay independent. Owning the bytes prevents a caller from changing an indexed key's hash by reusing its array. [ByteKey](../storage/src/main/java/com/ryszardzmija/shaledb/storage/hash/index/ByteKey.java)

| Local state | Representation | Action on lookup |
| --- | --- | --- |
| Absent | Key in neither collection | Continue to the next older segment. |
| Present | Key in the offset map | Read and validate the normal record at its offset. Return the value, including an empty value. |
| Deleted | Key in the deletion set | Stop and return absence. The marker already represents a validated or successfully written tombstone. |

A normal record replaces any previous local offset and clears a deletion marker. A tombstone installs a marker and removes the offset. Simply removing a deleted key would make it indistinguishable from a local miss and could expose an older value. These map and set changes rely on caller serialization.

## Lookup

The engine probes the active segment first and historical segments from highest to lowest ID. Both segment types use `SegmentLookup` to check the deletion marker before resolving an offset. A present entry calls `SegmentReader.readValue(offset, key)`. The record must fit the actual file extent and payload budget, pass CRC32C validation, and be a normal record with the exact requested key. A valid record for a different key or a tombstone raises `INVALID_INDEX_REFERENCE`. A negative offset or one beyond EOF raises `INVALID_RECORD_LOCATION`. A point read exactly at EOF encounters an incomplete header. A failed indexed read ends the lookup and disables further operations. [SegmentLookup](../storage/src/main/java/com/ryszardzmija/shaledb/storage/hash/segment/model/SegmentLookup.java) · [SegmentReader](../storage/src/main/java/com/ryszardzmija/shaledb/storage/hash/segment/model/SegmentReader.java) · [Record format](record-format.md)

## Live updates and replay

`SegmentWriter` returns the physical write result after append and force. `MutableSegment` then publishes the returned starting offset for a put or a deletion marker for a delete. Append and force failures abort the mutation before publication. [SegmentWriter](../storage/src/main/java/com/ryszardzmija/shaledb/storage/hash/segment/model/SegmentWriter.java) · [MutableSegment](../storage/src/main/java/com/ryszardzmija/shaledb/storage/hash/segment/model/MutableSegment.java)

On open, `SegmentScanner` captures the file extent and scans each segment from offset zero. It passes each fully validated record and its starting offset to `IndexBuilder`, which applies the same transitions to a provisional `HashIndex`. Replay in file order makes the last local record win. Historical replay requires exact EOF. Only a discovered active file can contribute an index built from a recoverable prefix. [SegmentScanner](../storage/src/main/java/com/ryszardzmija/shaledb/storage/serialization/io/SegmentScanner.java) · [IndexBuilder](../storage/src/main/java/com/ryszardzmija/shaledb/storage/hash/index/IndexBuilder.java) · [Durability and recovery](durability-and-recovery.md)

## Cost

With S segments, a lookup can visit all S indexes. Each checks the deletion set and, if necessary, the offset map. Each hash lookup has expected constant cost in entry count, plus the cost of hashing and comparing the key bytes. A present hit adds one record's worth of I/O and checksum work. A miss or deletion marker needs no record read. An ordinary mutation updates one index. Rebuild time grows with total encoded history, including obsolete records. Index memory grows with the sum of distinct keys per segment, including their key bytes and deletion markers. A key overwritten across S files can occupy S entries.
