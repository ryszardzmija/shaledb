# Storage semantics

`KeyValueStore` maps nonempty binary keys to binary values. Keys compare by byte content. The latest record wins, ordered first by numeric segment ID and then by starting offset within that segment. Callers keep input arrays stable for the duration of each call and can reuse them afterward. A successful read returns a fresh value array. [StorageEngine](../storage/src/main/java/com/ryszardzmija/shaledb/storage/StorageEngine.java) · [Indexing](indexing.md)

| Operation | Contract on an open store |
| --- | --- |
| `put(k,v)` | Reject null/empty `k`, null `v`, or an unsupported combined payload size with `IllegalArgumentException` before touching the segment. Select the active segment by available capacity, rolling over if needed. Append a normal record, force it and publish its starting offset. Overwrites leave the previous bytes on disk. |
| `delete(k)` | Reject null, empty, or oversized `k` with `IllegalArgumentException` before I/O. Check capacity and roll over if needed. Append a tombstone with zero value bytes even for an unknown or already deleted key, force it and publish the deleted marker. No pre-read is necessary. |
| `get(k)` | Reject null/empty `k` with `IllegalArgumentException`. The lookup key has no admission size limit. Search the active segment and successively older ones. A tombstone or exhaustive miss returns `Optional.empty()`. A normal record with zero value bytes returns a *present* optional containing a zero-length array. If an expected record is unreadable or its key or type conflicts with the index, the call fails at that segment. |
| Open | Construct a valid `StorageConfig` before opening. A null configuration reference is rejected with `NullPointerException`. The engine acquires exclusive directory ownership before discovery, initial creation and index rebuild. |
| `close()` | Disable normal operations immediately. Attempt a final force when closing from `OPEN`, then attempt every segment close. Release ownership after all segment channels are quiescent. Report force, segment close or ownership errors. [Concurrency and lifecycle](concurrency-and-lifecycle.md) |

`ByteKey` copies the incoming key on construction and returns a copy from `getData()`. A caller can reuse the key array after a synchronous call. `RecordPayload` retains its input arrays. On put, the writer computes a checksum over the value and subsequently copies it into the write buffer. The caller must keep input bytes stable for the duration of the call. Concurrent mutation of the value between those steps could store bytes inconsistent with their checksum. After return, the index holds no reference to the value. A successful value lookup allocates fresh arrays from the record, so changing the returned value does not update the store. [ByteKey](../storage/src/main/java/com/ryszardzmija/shaledb/storage/hash/index/ByteKey.java) · [RecordPayload](../storage/src/main/java/com/ryszardzmija/shaledb/storage/serialization/record/RecordPayload.java) · [RecordWriter](../storage/src/main/java/com/ryszardzmija/shaledb/storage/serialization/io/RecordWriter.java) · [RecordReader](../storage/src/main/java/com/ryszardzmija/shaledb/storage/serialization/io/RecordReader.java)

## Correctness contract

The following properties are the yardstick in the [engineering guidelines](../AGENTS.md). “Conditional” identifies a guarantee on the supported, serialized success path. “Partial” identifies a specific path that defeats the property.

| Property and reason | Responsible components | Enforcement and boundary |
| --- | --- | --- |
| Latest record wins, so an overwrite survives replay. | `HashIndex` replaces the local state. `IndexBuilder` replays records in the scanner's file order. The engine visits segments in descending recency. | A complete append followed by a force error can supersede the old value on replay. The failed engine disables lookup before it can serve the old index state. |
| A deletion masks all earlier values, including across files. | `markDeleted` retains a local marker. `get` stops at `Deleted`. | Enforced during serial lookup and replay of valid records. Losing a newer file or discarding a tombstone while retaining its older value could expose the old value. The current engine never reclaims a segment. |
| An empty value remains distinguishable from absence. | Record type, `LookupResult`, `Optional` and the RPC `found` flag carry independent presence information. | Enforced on normal write/read paths. A caller of the wire API must inspect `found`. |
| Invalid operations leave persistent state intact, so rejection is safe to retry after correction. | `HashIndexEngine` validates arguments and uses the same format and size rules as `RecordWriter` and `RecordReader`. | Invalid mutation input is rejected before segment append, synchronization, index updates or rollover. |
| An offset resolves to the latest normal record for that key in its own file. | `RecordWriter` appends at the current file size and returns that start after full transfer. `SegmentWriter` passes it back after force and `MutableSegment` publishes it. `SegmentReader` verifies the associated record. | Conditional on exclusive serial appends and compatible payload limits. Lookup verifies extent, structure, checksum, type and key. Serialized append and replay establish which offset is latest. [Indexing](indexing.md) |
| Successful writes satisfy the chosen durability policy. | `SegmentWriter` requires successful `force(true)` before returning the append result to `MutableSegment`. Segment creation forces the new file and then its directory. | Enforced as a call ordering. Physical survival depends on filesystem and device semantics and durable directory ancestry. [Durability and recovery](durability-and-recovery.md) |
| Ordinary close and reopen preserve the logical map. | Closing an operational engine forces the active file. Constructors replay *all* records, including obsolete ones. | Conditional on readable, complete files, compatible settings and intact names. An incompatible payload or an incomplete historical record can deny access to the entire store. An eligible incomplete active suffix is removed during open. [Durability and recovery](durability-and-recovery.md) |

With the example limit of 16,384 payload bytes, a one-byte key can store a 16,383-byte value. A 16,384-byte value with the same key is rejected before append. The payload budget counts both fields, and the encoded record uses an additional 13 bytes for the header. [Configuration](configuration.md) · [Record format](record-format.md)

All three calls reject a null or empty key. Mutations also check the combined key and value length against the configured payload limit. A tombstone counts only its key. Lookup keys can exceed the payload limit. Invalid input raises `IllegalArgumentException` before the engine queries capacity, rolls over or appends, leaving the log and indexes usable. On a failed or closed store, availability checks precede validation. [Configuration](configuration.md) · [Concurrency and lifecycle](concurrency-and-lifecycle.md)

## Success and retry

Both `put` and `delete` return `void`. A normal return means the record passed its required durability barrier and the engine published its effect. Subsequent serialized reads and ordinary reopens see the latest mutation under the supported storage assumptions. If the caller loses the acknowledgment, a complete record can still take effect on replay. [Durability and recovery](durability-and-recovery.md)

Repeating an identical mutation leaves the same logical value if nothing intervenes, but each successful call appends a record. Retrying a delete after another caller reinserts the key deletes that new value. There are no request IDs, conditional updates or exactly-once semantics.

## Diagnosing failures

Expected operational failures raise `StorageEngineException`. Its `operation()` identifies the failed call, `reason()` classifies the primary cause and `mutationOutcome()` describes the incoming record. Causes retain component diagnostics. Suppressed exceptions record additional cleanup failures. [StorageEngineException](../storage/src/main/java/com/ryszardzmija/shaledb/storage/StorageEngineException.java)

| Failure | What the caller can conclude |
| --- | --- |
| Failure preparing a destination for a mutation | `NOT_APPENDED` applies to the incoming record. A successor file may already exist. `SEGMENT_ID_EXHAUSTED` leaves the engine open for reads and writes that fit. Other operational preparation failures are terminal. |
| Append or file-force failure | `UNKNOWN`. The file can contain a partial or complete record. A force failure reports `DURABILITY`. The engine stops serving reads and writes. |
| Unexpected unchecked failure during index publication | The record has passed its file force and the index may be partly updated. The engine enters `FAILED` and propagates the original failure without assigning a mutation outcome. |
| Operational failure outside a mutation | `NOT_APPLICABLE`. For a failed indexed read, the reason distinguishes corruption, incomplete records, invalid offsets or index references, incompatible payload sizes, and storage I/O. Startup also distinguishes invalid segment metadata, lock contention, invalid lock files and ownership I/O. |
| A subsequent operation on a failed or closed store | `IllegalStateException` before any file access. In `FAILED` it carries the first terminal failure. |

After an ambiguous failure, the live engine leaves the record bytes in place. A new owner reconstructs state from the surviving records on open. The recovery policy specifies which incomplete suffix can be removed and which failures must abort open. [Durability and recovery](durability-and-recovery.md)
