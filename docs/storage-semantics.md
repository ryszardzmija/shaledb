# Storage semantics

An embedded caller observes a partial map from nonempty byte strings to arbitrary byte strings. `put` and `delete` return `void`. `get` returns an `Optional<byte[]>`. For valid, readable records, the winner for a key is the record with the greatest numeric segment ID, then the greatest starting offset within that segment. This ordering assumes one writer and unique, increasing segment IDs. There is no clock or timestamp in the record. [StorageEngine](../storage/src/main/java/com/ryszardzmija/shaledb/storage/StorageEngine.java) · [HashIndexEngine](../storage/src/main/java/com/ryszardzmija/shaledb/storage/hash/HashIndexEngine.java) · [Indexing](indexing.md)

## Operations and ownership

| Call | Input checks and effect |
| --- | --- |
| `put(k,v)` | Reject null/empty `k`, null `v`, or an unsupported combined payload size with `IllegalArgumentException` before touching the segment. Append a normal record, force it, publish its starting offset, then check rollover. Overwrites leave the previous bytes on disk. |
| `delete(k)` | Reject null, empty, or oversized `k` with `IllegalArgumentException` before I/O. Append a tombstone with zero value bytes even for an unknown or already deleted key, force it, publish the deleted marker, then check rollover. No pre-read is necessary. |
| `get(k)` | Reject null/empty `k` with `IllegalArgumentException`. The lookup key has no admission size limit. Search the active segment and successively older ones. A tombstone or exhaustive miss returns `Optional.empty()`. A normal record with zero value bytes returns a *present* optional containing a zero-length array. An unreadable expected record fails the call. |
| Open | Construct a valid `StorageConfig` before opening. A null configuration reference is rejected with `NullPointerException`. Discovery, initial creation and complete index rebuild must succeed before a store is returned. |
| `close()` | Force the active file, then attempt to close every loaded segment. A failed force or close is reported. Treat the instance as closed after the call. |

`ByteKey` copies the incoming key on construction and returns a copy from `getData()`. A caller can reuse the key array after a synchronous call. `RecordPayload` retains its input arrays. On put, the writer computes a checksum over the value and subsequently copies it into the write buffer. The caller must keep input bytes stable for the duration of the call. Concurrent mutation of the value between those steps could store bytes inconsistent with their checksum. After return, the index holds no reference to the value. A successful value lookup allocates fresh arrays from the record, so changing the returned value does not update the store. [ByteKey](../storage/src/main/java/com/ryszardzmija/shaledb/storage/hash/index/ByteKey.java) · [RecordPayload](../storage/src/main/java/com/ryszardzmija/shaledb/storage/serialization/record/RecordPayload.java) · [RecordWriter](../storage/src/main/java/com/ryszardzmija/shaledb/storage/serialization/io/RecordWriter.java) · [RecordReader](../storage/src/main/java/com/ryszardzmija/shaledb/storage/serialization/io/RecordReader.java)

## Correctness contract

The following properties are the yardstick in the [engineering guidelines](../AGENTS.md). “Conditional” identifies a guarantee on the supported, serialized success path. “Partial” identifies a specific path that defeats the property.

| Property and reason | Responsible components | Enforcement and boundary |
| --- | --- | --- |
| Latest record wins, so an overwrite survives replay. | `HashIndex` replaces the local state. `IndexBuilder` scans forward. The engine visits segments in descending recency. | Conditional on an ordered, complete history and successful publication. A complete append followed by a force error can supersede the old value on replay while the live index still serves the old value. |
| A deletion masks all earlier values, including across files. | `markDeleted` retains a local marker. `get` stops at `Deleted`. | Enforced during serial lookup and replay of valid records. Losing a newer file or discarding a tombstone while retaining its older value could expose the old value. The current engine never reclaims a segment. |
| An empty value remains distinguishable from absence. | Record type, `LookupResult`, `Optional` and the RPC `found` flag carry independent presence information. | Enforced on normal write/read paths. A caller of the wire API must inspect `found`. |
| Invalid operations leave persistent state intact, so rejection is safe to retry after correction. | `HashIndexEngine` validates arguments and uses the same format and size rules as `RecordWriter` and `RecordReader`. | Invalid mutation input is rejected before segment append, synchronization, index updates or rollover. |
| An offset resolves to the latest normal record for that key in its own file. | `RecordWriter` appends at the current file size and returns that start after full transfer. `SegmentWriter` publishes it after force. `SegmentReader` consumes it. | Conditional on exclusive serial appends and compatible limits. Lookup trusts record identity and rejects any present offset at or beyond the current segment threshold. [Indexing](indexing.md) |
| Successful writes satisfy the chosen durability policy. | `SegmentWriter` requires successful `force(true)` before publishing. File creation separately forces the directory. | Enforced as a call ordering. Physical survival depends on filesystem and device semantics and durable directory ancestry. [Durability and recovery](durability-and-recovery.md) |
| Ordinary close and reopen preserve the logical map. | `close` forces the active file. Constructors replay *all* records, including obsolete ones. | Conditional on readable, complete files, compatible settings and intact names. One oversized or incomplete record can deny access to the entire store on the next open. |

With the example limit of 16,384 payload bytes, a one-byte key can store a 16,383-byte value. A 16,384-byte value with the same key is rejected before append. The payload budget counts both fields, and the encoded record uses an additional 13 bytes for the header. [Configuration](configuration.md) · [Record format](record-format.md)

## Visibility, retries and exceptions

Logical visibility begins with the index update for the active instance. A subsequent serialized read can then find the mutation, subject to the reader's checks. Physical append precedes synchronization. Both precede visibility. The embedded acknowledgment comes after the rollover check. A remote acknowledgment also needs successful response delivery. No sequence number or receipt is exposed. [Architecture](architecture.md) · [Server architecture](server-architecture.md)

Consecutive successful identical puts have the same final value, and repeated deletes leave the key absent. Each repetition appends and forces another record and may trigger rollover. Before retrying an unsuccessful mutation, account for its possible effects. A retried put can overwrite an intervening update, and a retried delete can remove a reinserted value. The engine has no request identity or conditional write to distinguish these cases.

### Embedded exception contract

`HashIndexEngine` translates expected operational failures to unchecked `StorageEngineException`. Its `operation()` identifies `OPEN`, `GET`, `PUT`, `DELETE` or `CLOSE`. The engine assigns `reason()` from the primary cause chain, and `KeyValueStore` logs and rethrows the same exception. Unexpected programming errors propagate in their original form. [StorageEngineException](../storage/src/main/java/com/ryszardzmija/shaledb/storage/StorageEngineException.java) · [HashIndexEngine](../storage/src/main/java/com/ryszardzmija/shaledb/storage/hash/HashIndexEngine.java)

| Condition | Public type or reason | Details available through causes |
| --- | --- | --- |
| Invalid operation argument or mutation size | `IllegalArgumentException` | Validation precedes any mutation. Size rejection includes the rejected size and applicable limit. |
| Invalid configuration | `IllegalArgumentException` during direct construction or `StorageConfigException` during mapping | The mapper preserves the validation exception when one exists. [Configuration](configuration.md) |
| Malformed record structure | `STRUCTURAL_CORRUPTION` | `DataCorruptionException` carries the record offset and structure or decoding cause. |
| CRC mismatch in a complete record | `CHECKSUM_CORRUPTION` | `DataCorruptionException` carries the offset and checksum kind. |
| Short header or payload, including EOF during transfer | `INCOMPLETE_RECORD` | `IncompleteRecordException` carries the start offset, section, expected bytes and available or transferred bytes. |
| Record exceeds a configured or implementation read limit | `INCOMPATIBLE_RECORD_SIZE` | `ReadLimitExceeded` carries the offset. The payload-limit cause carries the declared size, maximum and type of limit. Validation reaches this check before checking payload extent or checksum. |
| Detected invalid record offset | `INVALID_RECORD_LOCATION` | `InvalidLocation` carries the offset and retains the low-level argument failure when applicable. A read exactly at EOF still reports an incomplete header. |
| A matching segment filename has an unrepresentable ID | `INVALID_SEGMENT_METADATA` | The discovery exception identifies the filename and preserves the numeric parsing failure. |
| Filesystem or channel failure | `STORAGE_IO` | Contextual exceptions identify paths and operations. The filesystem exception is retained when one exists. A stalled transfer retains `RecordIOException.NoProgress`. |
| Failed file or directory persistence barrier | `DURABILITY` | `DurabilityException` identifies the file or directory target and retains the filesystem cause. |

Record I/O preserves these distinctions for lookup and replay. The index builder wraps failed replay, and segment and file boundaries add path or operation context. A failed lookup stops the search at the expected record. During failed construction, rollover or close, cleanup failures remain suppressed on the primary failure or its cause. Callers diagnosing a failure should inspect both the causes and their suppressed exceptions. [Record format](record-format.md) · [Segment management](segment-management.md)

### Mutation outcomes

Deterministic validation rejection establishes that the call made no persistent changes. For a failed put or delete, `StorageEngineException.mutationOutcome()` is `UNKNOWN` during the append and force path. An append error can leave zero bytes, a prefix or a complete record. A force error can leave a complete record that survives reopening, even though the in-memory index was never updated. The classification is conservative and includes failures obtaining the append position before the first write.

`FORCE_COMPLETED` means the mutation's file force and index publication completed before the rollover size check or rollover itself failed. That status refers to the triggering record's barrier. A later directory force can fail in the same call, and hardware survival still depends on the storage stack. `NOT_APPLICABLE` is used for open, get and close. [Failure windows](durability-and-recovery.md)

Failures outside the translated operational paths, such as programming defects during publication, carry no engine-assigned mutation outcome. M1.09 will address finer failure phases and whether the engine can accept more calls after uncertainty. [Concurrency and lifecycle](concurrency-and-lifecycle.md)
