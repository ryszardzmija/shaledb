# Indexing

The engine owns one in-memory hash directory from binary keys to live record locations. Each location contains a positive numeric segment ID and a nonnegative byte offset, both stored as `long`. A normal record replaces the location for its key. A tombstone removes the entry, so deleted and never-written keys both resolve to absence. Every valid delete still appends a tombstone to the persistent log. [KeyDirectory](../storage/src/main/java/com/ryszardzmija/shaledb/storage/hash/index/KeyDirectory.java) · [RecordLocation](../storage/src/main/java/com/ryszardzmija/shaledb/storage/hash/index/RecordLocation.java)

`ByteKey` owns a copy of the key bytes and compares the entire byte sequence. Distinct arrays containing the same bytes identify the same key. Hash collisions are resolved by byte equality. A zero-length value stays present because its normal record still has a directory location. [ByteKey](../storage/src/main/java/com/ryszardzmija/shaledb/storage/hash/index/ByteKey.java)

## Point lookup

A directory miss returns immediately without accessing segment files. On a hit, the engine compares the location's ID with the active segment ID and otherwise looks it up in the historical segment registry. It reads the referenced record at the stored offset. Segment IDs survive retirement, so the same locations continue to resolve after rollover. [HashIndexEngine](../storage/src/main/java/com/ryszardzmija/shaledb/storage/hash/HashIndexEngine.java) · [Segment management](segment-management.md)

The record must fit the file extent and payload budget, pass CRC32C validation, and be a normal record with the requested key. An unknown segment ID, a different key or a tombstone raises `INVALID_INDEX_REFERENCE`. A negative offset or one beyond EOF raises `INVALID_RECORD_LOCATION`. A point read at exact EOF encounters an incomplete header. A failed indexed read disables further operations. A valid older record for the same key could pass physical validation, which makes correct publication and replay essential. [SegmentReader](../storage/src/main/java/com/ryszardzmija/shaledb/storage/hash/segment/model/SegmentReader.java) · [Record format](record-format.md)

## Mutation and startup

The engine publishes each mutation after the configured durability barrier succeeds. It uses the actual destination segment ID and the append result's starting offset for a put, or removes the key for a delete. Older physical records remain in the log.

At startup, `SegmentScanner` streams validated records in offset order. The loader visits historical segments by increasing numeric ID and processes the active segment last, applying each complete record directly to the provisional directory. Historical scans require exact EOF. A discovered active segment may contribute a validated prefix under the recovery rules. The engine installs the directory and the segment registry only after validation, recovery and startup forces succeed. [SegmentScanner](../storage/src/main/java/com/ryszardzmija/shaledb/storage/serialization/io/SegmentScanner.java) · [SegmentLoader](../storage/src/main/java/com/ryszardzmija/shaledb/storage/hash/segment/loader/SegmentLoader.java) · [Durability and recovery](durability-and-recovery.md)

## Cost

Directory lookup and historical segment resolution have expected constant cost in entry count. Key hashing and comparison depend on key length. A hit adds one logical record read, including extent checks, decoding and checksum validation. An ordinary mutation changes one directory entry with amortized constant hash-table work. Rollover leaves the directory untouched, although registry growth can occasionally resize its hash table.

Replay validates the entire encoded history in one pass after discovery orders the files. The directory holds one logical entry per live key, including its key bytes, and the registry holds one entry per historical segment. Allocated hash tables may retain capacity after entries are removed. Each historical segment retains its open reader.
