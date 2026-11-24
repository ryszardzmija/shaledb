# Storage semantics

An embedded caller observes a partial map from nonempty byte strings to arbitrary byte strings. `put` and `delete` return `void`. `get` returns an `Optional<byte[]>`. For valid, readable records, the winner for a key is the record with the greatest numeric segment ID, then the greatest starting offset within that segment. This ordering assumes one writer and unique, increasing segment IDs. There is no clock or timestamp in the record. [StorageEngine](../storage/src/main/java/com/ryszardzmija/shaledb/storage/StorageEngine.java) · [HashIndexEngine](../storage/src/main/java/com/ryszardzmija/shaledb/storage/hash/HashIndexEngine.java) · [Indexing](indexing.md)

## Operations and ownership

| Call | Input checks and effect |
| --- | --- |
| `put(k,v)` | Reject null/empty `k`, null `v`, or an unsupported combined payload size with `IllegalArgumentException` before touching the segment. Append a normal record, force it, publish its starting offset, then check rollover. Overwrites leave the previous bytes on disk. |
| `delete(k)` | Reject null, empty, or oversized `k` with `IllegalArgumentException` before I/O. Append a tombstone with zero value bytes even for an unknown or already deleted key, force it, publish the deleted marker, then check rollover. No pre-read is necessary. |
| `get(k)` | Reject null/empty `k`. The lookup key has no admission size limit. Search the active segment and successively older ones. A tombstone or exhaustive miss returns `Optional.empty()`. A normal record with zero value bytes returns a *present* optional containing a zero-length array. |

`ByteKey` copies the incoming key on construction and returns a copy from `getData()`. A caller can reuse the key array after a synchronous call. `RecordPayload` retains its input arrays. On put, the writer computes a checksum over the value and subsequently copies it into the write buffer. The caller must keep input bytes stable for the duration of the call. Concurrent mutation of the value between those steps could store bytes inconsistent with their checksum. After return, the index holds no reference to the value. A successful value lookup allocates fresh arrays from the record, so changing the returned value does not update the store. [ByteKey](../storage/src/main/java/com/ryszardzmija/shaledb/storage/hash/index/ByteKey.java) · [RecordPayload](../storage/src/main/java/com/ryszardzmija/shaledb/storage/serialization/record/RecordPayload.java) · [RecordWriter](../storage/src/main/java/com/ryszardzmija/shaledb/storage/serialization/io/RecordWriter.java) · [RecordReader](../storage/src/main/java/com/ryszardzmija/shaledb/storage/serialization/io/RecordReader.java)

## Correctness contract

The following properties are the yardstick in the [engineering guidelines](../AGENTS.md). “Conditional” identifies a guarantee on the supported, serialized success path. “Partial” identifies a specific path that defeats the property.

| Property and reason | Responsible components | Enforcement and boundary |
| --- | --- | --- |
| Latest record wins, so an overwrite survives replay. | `HashIndex` replaces the local state. `IndexBuilder` scans forward. The engine visits segments in descending recency. | Conditional on an ordered, complete history and successful publication. A complete append followed by a force error can supersede the old value on replay while the live index still serves the old value. |
| A deletion masks all earlier values, including across files. | `markDeleted` retains a local marker. `get` stops at `Deleted`. | Enforced during serial lookup and replay of valid records. Losing a newer file or discarding a tombstone while retaining its older value could expose the old value. The current engine never reclaims a segment. |
| An empty value remains distinguishable from absence. | Record type, `LookupResult`, `Optional` and the RPC `found` flag carry independent presence information. | Enforced on normal write/read paths. A caller of the wire API must inspect `found`. |
| Invalid operations leave persistent state intact, so rejection is safe to retry after correction. | `HashIndexEngine` validates arguments and uses the same format and size rules as `RecordWriter` and `RecordReader`. | Invalid mutation input is rejected before segment append, synchronization, index updates or rollover. |
| An offset resolves to the latest normal record for that key in its own file. | `RecordWriter` captures the position. `SegmentWriter` publishes it. `SegmentReader` consumes it. | Conditional on exclusive serial appends and compatible limits. Lookup trusts record identity and rejects any present offset at or beyond the current segment threshold. [Indexing](indexing.md) |
| Successful writes satisfy the chosen durability policy. | `SegmentWriter` requires successful `force(true)` before publishing. File creation separately forces the directory. | Enforced as a call ordering. Physical survival depends on filesystem and device semantics and durable directory ancestry. [Durability and recovery](durability-and-recovery.md) |
| Ordinary close and reopen preserve the logical map. | `close` forces the active file. Constructors replay *all* records, including obsolete ones. | Conditional on readable, complete files, compatible settings and intact names. One oversized or incomplete record can deny access to the entire store on the next open. |

With the example limit of 16,384 payload bytes, a one-byte key can store a 16,383-byte value. A 16,384-byte value with the same key is rejected before append. The payload budget counts both fields, and the encoded record uses an additional 13 bytes for the header. [Configuration](configuration.md) · [Record format](record-format.md)

## Visibility, retries and exceptions

Logical visibility begins with the index update for the active instance. A subsequent serialized read can then find the mutation, subject to the reader's checks. Physical append precedes synchronization. Both precede visibility. The embedded acknowledgment comes after the rollover check. A remote acknowledgment also needs successful response delivery. No sequence number or receipt is exposed. [Architecture](architecture.md) · [Server architecture](server-architecture.md)

The interface's idempotency is about the logical map. Consecutive successful identical puts have the same final value, and repeated deletes leave the key absent. Each successful replay appends and forces another record and may trigger rollover. Retrying an uncertain put after a different writer has updated the same key can overwrite the intervening update. Retrying a delete after reinsertion can remove it. The engine has no request identity or conditional write to distinguish these cases.

`KeyValueStore` logs and rethrows `StorageEngineException`. Segment read/write I/O generally reaches that exception via `RecordIOException` and `SegmentIOException`. A failed append or force does not certify the absence of a record. A handled `RolloverException` is logged and the mutation returns normally. A failed size query after publication can instead expose `SegmentIOException` directly. `DataCorruptionException` from decoding and `IllegalStateException` from the offset check also escape the façade's engine-exception handler. Callers cannot infer “nothing changed” from a generic exception or RPC error. [Failure windows](durability-and-recovery.md) · [KeyValueStore](../storage/src/main/java/com/ryszardzmija/shaledb/storage/KeyValueStore.java) · [HashIndexEngine](../storage/src/main/java/com/ryszardzmija/shaledb/storage/hash/HashIndexEngine.java)

An open contract question is whether a mutation with an uncertain persistence outcome should make the instance unavailable until replay.
