# Storage semantics

An embedded caller observes a partial map from nonempty byte strings to arbitrary byte strings. `put` and `delete` return `void`. `get` returns an `Optional<byte[]>`. For valid, readable records, the winner for a key is the record with the greatest numeric segment ID, then the greatest starting offset within that segment. This ordering assumes one writer and unique, increasing segment IDs. There is no clock or timestamp in the record. [StorageEngine](../storage/src/main/java/com/ryszardzmija/shaledb/storage/StorageEngine.java) · [HashIndexEngine](../storage/src/main/java/com/ryszardzmija/shaledb/storage/hash/HashIndexEngine.java) · [Indexing](indexing.md)

## Operations and ownership

| Call | Input checks and effect |
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
| Latest record wins, so an overwrite survives replay. | `HashIndex` replaces the local state. `IndexBuilder` scans forward. The engine visits segments in descending recency. | A complete append followed by a force error can supersede the old value on replay. The failed engine disables lookup before it can serve the old index state. |
| A deletion masks all earlier values, including across files. | `markDeleted` retains a local marker. `get` stops at `Deleted`. | Enforced during serial lookup and replay of valid records. Losing a newer file or discarding a tombstone while retaining its older value could expose the old value. The current engine never reclaims a segment. |
| An empty value remains distinguishable from absence. | Record type, `LookupResult`, `Optional` and the RPC `found` flag carry independent presence information. | Enforced on normal write/read paths. A caller of the wire API must inspect `found`. |
| Invalid operations leave persistent state intact, so rejection is safe to retry after correction. | `HashIndexEngine` validates arguments and uses the same format and size rules as `RecordWriter` and `RecordReader`. | Invalid mutation input is rejected before segment append, synchronization, index updates or rollover. |
| An offset resolves to the latest normal record for that key in its own file. | `RecordWriter` appends at the current file size and returns that start after full transfer. `SegmentWriter` publishes it after force. `SegmentReader` verifies the associated record. | Conditional on exclusive serial appends and compatible payload limits. Lookup verifies extent, structure, checksum, type and key. Serialized append and replay establish which offset is latest. [Indexing](indexing.md) |
| Successful writes satisfy the chosen durability policy. | `SegmentWriter` requires successful `force(true)` before publishing. Segment creation forces the new file and then its directory. | Enforced as a call ordering. Physical survival depends on filesystem and device semantics and durable directory ancestry. [Durability and recovery](durability-and-recovery.md) |
| Ordinary close and reopen preserve the logical map. | Closing an operational engine forces the active file. Constructors replay *all* records, including obsolete ones. | Conditional on readable, complete files, compatible settings and intact names. One oversized or incomplete record can deny access to the entire store on the next open. |

With the example limit of 16,384 payload bytes, a one-byte key can store a 16,383-byte value. A 16,384-byte value with the same key is rejected before append. The payload budget counts both fields, and the encoded record uses an additional 13 bytes for the header. [Configuration](configuration.md) · [Record format](record-format.md)

## Visibility, retries and exceptions

Physical append and synchronization precede index publication. The size check and any successor publication occur before append. A successful embedded mutation returns after index publication. A subsequent serialized read finds the published state, subject to the reader's checks. A remote acknowledgment also needs successful response delivery. [Architecture](architecture.md) · [Server architecture](server-architecture.md)

Consecutive successful identical puts have the same final value, and repeated deletes leave the key absent. Each repetition appends and forces another record and may trigger rollover. Before retrying an unsuccessful mutation, account for its possible effects. A retried put can overwrite an intervening update, and a retried delete can remove a reinserted value. The engine has no request identity or conditional write to distinguish these cases.

### Embedded exception contract

`HashIndexEngine` translates expected operational failures to unchecked `StorageEngineException`. Its `operation()` identifies `OPEN`, `GET`, `PUT`, `DELETE` or `CLOSE`. Ownership failures carry their reason directly, and segment failures derive it from the primary cause chain. `KeyValueStore` logs and rethrows the same exception. Unexpected programming errors propagate in their original form. [StorageEngineException](../storage/src/main/java/com/ryszardzmija/shaledb/storage/StorageEngineException.java) · [HashIndexEngine](../storage/src/main/java/com/ryszardzmija/shaledb/storage/hash/HashIndexEngine.java)

| Condition | Public type or reason | Details available through causes |
| --- | --- | --- |
| Invalid operation argument or mutation size | `IllegalArgumentException` | Validation precedes any mutation. Size rejection includes the rejected size and applicable limit. |
| Invalid configuration | `IllegalArgumentException` during direct construction or `StorageConfigException` during mapping | The mapper preserves the validation exception when one exists. [Configuration](configuration.md) |
| `put`, `get` or `delete` in `FAILED` or after close has begun | `IllegalStateException` | Availability is checked before argument validation or segment access. In `FAILED` the exception carries the cause of the first terminal failure. |
| The database directory is reserved in this JVM or locked by another process | `OPEN / DATABASE_LOCKED` | Open fails immediately and leaves the owner and its segments untouched. |
| `LOCK` is a symlink, a nonregular file or a detected hard link | `OPEN / INVALID_LOCK_FILE` | The path is included in the diagnostic and the entry is preserved. |
| Directory resolution, opening `LOCK`, locking or releasing ownership fails | `OPEN` or `CLOSE / OWNERSHIP_IO` | The failure identifies the path, preserves the filesystem cause and carries any secondary cleanup failures. [Concurrency and lifecycle](concurrency-and-lifecycle.md) |
| Malformed record structure | `STRUCTURAL_CORRUPTION` | `DataCorruptionException` carries the record offset and structure or decoding cause. |
| CRC mismatch in a complete record | `CHECKSUM_CORRUPTION` | `DataCorruptionException` carries the offset and checksum kind. |
| Short header or payload, including EOF during transfer | `INCOMPLETE_RECORD` | `IncompleteRecordException` carries the start offset, section, expected bytes and available or transferred bytes. |
| Record exceeds a configured or implementation read limit | `INCOMPATIBLE_RECORD_SIZE` | `ReadLimitExceeded` carries the offset. The payload-limit cause carries the declared size, maximum and type of limit. Validation reaches this check before checking payload extent or checksum. |
| Detected invalid record offset | `INVALID_RECORD_LOCATION` | `InvalidLocation` carries the offset and retains the low-level argument failure when applicable. A read exactly at EOF still reports an incomplete header. |
| Present entry resolves to a valid record with the wrong key or type | `INVALID_INDEX_REFERENCE` | `IndexConsistencyException` carries the record offset and mismatch description. The surrounding segment exception supplies the path. |
| Malformed segment name, duplicate ID, unsupported file type or detected file alias | `INVALID_SEGMENT_METADATA` | The discovery exception identifies the affected paths, the ID in a collision and the numeric parsing failure when applicable. |
| Rollover requires a segment ID above `Long.MAX_VALUE` | `SEGMENT_ID_EXHAUSTED` | The file exception identifies the exhausted ID and directory. The incoming record is absent and the engine stays open. |
| Filesystem or channel failure | `STORAGE_IO` | Contextual exceptions identify paths and operations. The filesystem exception is retained when one exists. A stalled transfer retains `RecordIOException.NoProgress`. |
| Failed file or directory persistence barrier | `DURABILITY` | `DurabilityException` identifies the file or directory target and retains the filesystem cause. |

Record I/O preserves these distinctions for lookup and replay. The index builder wraps failed replay, and segment and file boundaries add path or operation context. A failed lookup stops the search at the expected record. During failed construction, rollover or close, cleanup failures remain suppressed on the primary failure or its cause. Callers diagnosing a failure should inspect both the causes and their suppressed exceptions. [Record format](record-format.md) · [Segment management](segment-management.md)

### Fail-stop policy

The engine checks availability and validates the complete record before entering the mutation path. The guard starts at the physical size query. Retirement of the old writer is the first rollover step that can change relevant resource state. Creating the successor is the first step that can change the persistent segment layout. Deterministic ID exhaustion is reported before retirement. A size-query error, an uncertain rollover or an append-path failure is terminal. Lookup failures are terminal once a validated lookup begins because the engine cannot establish that its current history and indexes can be used safely. The public reason still distinguishes ordinary read I/O from detected corruption and incompatible records.

| Failure or result | Phase | Engine state | Persistence and index implications | Caller sees |
| --- | --- | --- | --- | --- |
| Invalid argument or unsupported write size | Validation | `OPEN` | File layout, segment bytes and indexes are unchanged. | `IllegalArgumentException` |
| Miss or tombstone | Lookup | `OPEN` | Existing state is unchanged. A normal empty value still returns a present result. | `Optional.empty()` for a miss or tombstone |
| Append setup or immediate write error, partial append, or exhausted zero-progress writes | Append | `FAILED` | A write error can leave zero bytes, a prefix or a complete record. Publication is skipped. | Typically `STORAGE_IO`, outcome `UNKNOWN` |
| Record force reports failure | Synchronization | `FAILED` | The record was fully transferred. Its durability is uncertain, and the prior index state remains. | `DURABILITY`, outcome `UNKNOWN` |
| Unexpected failure before or during index publication | After successful record force | `FAILED` | The record satisfies its force barrier. The index may still contain the previous state or reflect an interrupted update. | The original unchecked exception or error |
| Successor ID exhaustion | Before retirement | `OPEN` | The layout, prior records and indexes are intact. Reads and mutations that fit can continue. | `SEGMENT_ID_EXHAUSTED`, outcome `NOT_APPENDED` |
| Size query, retirement, successor creation, publication force, initialization or handoff fails | Before incoming append | `FAILED` | The incoming record is absent. A transition failure can leave uncertainty about the successor file or live resources. | A reason such as `STORAGE_IO` or `DURABILITY`, outcome `NOT_APPENDED`. Unexpected failures propagate unchanged. |
| Corruption, incomplete expected record, invalid offset, wrong key or type, incompatible size, or filesystem read error | Lookup | `FAILED` | Lookup leaves the files intact and stops the search at the first failure. | `GET` with the specific reason above and outcome `NOT_APPLICABLE` |
| Further operation on an unavailable engine | Availability check | `FAILED` or `CLOSED` | No index or file access. | `IllegalStateException`, with the first terminal failure as its cause while `FAILED` |

The transition records the first failure and retains ownership until safe close. It performs no I/O. Closing a failed engine starts with channel cleanup and skips the final force. A complete unacknowledged put or tombstone can be replayed on the next open. A partial record can prevent startup. The engine leaves the record history in place for inspection or later recovery. [Concurrency and lifecycle](concurrency-and-lifecycle.md) · [Durability and recovery](durability-and-recovery.md)

`NOT_APPENDED` reports the outcome of the incoming record. An empty successor can survive that failure. `FORCE_COMPLETED` retains its API meaning for a failure following a completed mutation force. Unexpected unchecked failures propagate in their original form and carry no engine-assigned outcome. Physical durability in each case depends on the documented storage assumptions. [Failure windows](durability-and-recovery.md#failure-windows)
