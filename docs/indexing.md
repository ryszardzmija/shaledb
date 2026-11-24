# Indexing

Each segment has its own in-memory hash index. A record offset is a nonnegative byte position within that segment. The active reader and writer share an index. Each historical reader keeps the index for its file. There is no global key directory or persistent index. [HashIndex](../storage/src/main/java/com/ryszardzmija/shaledb/storage/hash/index/HashIndex.java)

## Key identity and local state

`ByteKey` copies the key bytes and uses content-based hashing and equality. Equal byte sequences supplied in different arrays identify the same key. Distinct keys with the same hash stay independent. Owning the bytes prevents a caller from changing an indexed key's hash by reusing its array. [ByteKey](../storage/src/main/java/com/ryszardzmija/shaledb/storage/hash/index/ByteKey.java)

| Local state | Representation | Action on lookup |
| --- | --- | --- |
| Absent | Key in neither collection | Continue to the next older segment. |
| Present | Key in the offset map | Read and validate the normal record at its offset. Return the value, including an empty value. |
| Deleted | Key in the deletion set | Stop and return absence. The marker already represents a validated or successfully written tombstone. |

A normal record replaces any previous local offset and clears a deletion marker. A tombstone installs a marker and removes the offset. Simply removing a deleted key would make it indistinguishable from a local miss and could expose an older value. These map and set changes rely on caller serialization.

## Lookup and reconstruction

The engine probes the active segment first and historical segments from highest to lowest ID. For a present entry, the reader checks the offset and validates the record against the actual file extent, payload budget and CRC32C. It also requires a normal record with the exact requested key. A valid record for a different key or a tombstone raises `INVALID_INDEX_REFERENCE`. A negative offset or one beyond EOF raises `INVALID_RECORD_LOCATION`. A point read exactly at EOF encounters an incomplete header. A failed indexed read ends the lookup and disables further operations. [SegmentReader](../storage/src/main/java/com/ryszardzmija/shaledb/storage/hash/segment/model/SegmentReader.java) · [Record format](record-format.md)

Live mutations publish their local index transition after the file force. On open, replay starts at offset zero in **each** segment. It reads a complete validated record, applies the same transition and advances by its encoded size. Forward replay makes the last local record win. Historical replay must reach exact EOF. Only a discovered active file can contribute an index built from a recoverable prefix. [IndexBuilder](../storage/src/main/java/com/ryszardzmija/shaledb/storage/hash/index/IndexBuilder.java) · [Durability and recovery](durability-and-recovery.md)

## Cost

With S segments, a lookup can visit all S indexes. Each checks the deletion set and, if necessary, the offset map. Each hash lookup has expected constant cost in entry count, plus the cost of hashing and comparing the key bytes. A present hit adds one record's worth of I/O and checksum work. A miss or deletion marker needs no record read. An ordinary mutation updates one index. Rebuild time grows with total encoded history, including obsolete records. Index memory grows with the sum of distinct keys per segment, including their key bytes and deletion markers. A key overwritten across S files can occupy S entries.
