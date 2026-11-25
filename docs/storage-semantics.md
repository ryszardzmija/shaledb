# Storage semantics

`KeyValueStore` maps nonempty binary keys to binary values. Keys compare by byte content. The latest record wins, ordered first by numeric segment ID and then by starting offset within that segment. Callers keep input arrays stable for the duration of each call and can reuse them afterward. A successful read returns a fresh value array. [StorageEngine](../storage/src/main/java/com/ryszardzmija/shaledb/storage/StorageEngine.java) · [Indexing](indexing.md)

| Operation | Contract on an open store |
| --- | --- |
| `put(key, value)` | Insert or overwrite by appending a normal record. The value must be non-null and may be empty. A put after a delete makes the key present again. |
| `get(key)` | Return `Optional.of(value)` for a present key, including a zero-length value. Return `Optional.empty()` for a missing or deleted key. An unreadable indexed record raises an error even if an older value exists. |
| `delete(key)` | Append a tombstone, even for a missing or already deleted key. The tombstone shadows every earlier value, including values in historical segments. |

All three calls reject a null or empty key. Mutations also check the combined key and value length against the configured payload limit. A tombstone counts only its key. Lookup keys can exceed the payload limit. Invalid input raises `IllegalArgumentException` before the engine queries capacity, rolls over or appends, leaving the log and key directory usable. On a failed or closed store, availability checks precede validation. [Configuration](configuration.md) · [Concurrency and lifecycle](concurrency-and-lifecycle.md)

## Success and retry

Both `put` and `delete` return `void`. A normal return means the record passed its required durability barrier and the engine published the destination segment ID and offset or removed the deleted key from the global directory. Subsequent serialized reads and ordinary reopens see the latest mutation under the supported storage assumptions. If the caller loses the acknowledgment, a complete record can still take effect on replay. [Durability and recovery](durability-and-recovery.md)

Repeating an identical mutation leaves the same logical value if nothing intervenes, but each successful call appends a record. Retrying a delete after another caller reinserts the key deletes that new value. There are no request IDs, conditional updates or exactly-once semantics.

## Diagnosing failures

Expected operational failures raise `StorageEngineException`. Its `operation()` identifies the failed call, `reason()` classifies the primary cause and `mutationOutcome()` describes the incoming record. Causes retain component diagnostics. Suppressed exceptions record additional cleanup failures. [StorageEngineException](../storage/src/main/java/com/ryszardzmija/shaledb/storage/StorageEngineException.java)

| Failure | What the caller can conclude |
| --- | --- |
| Failure preparing a destination for a mutation | `NOT_APPENDED` applies to the incoming record. A successor file may already exist. `SEGMENT_ID_EXHAUSTED` leaves the engine open for reads and writes that fit. Other operational preparation failures are terminal. |
| Append or file-force failure | `UNKNOWN`. The file can contain a partial or complete record. A force failure reports `DURABILITY`. The engine stops serving reads and writes. |
| Unexpected unchecked failure during directory publication | The record has passed its file force and the directory update may have taken effect. The engine enters `FAILED` and propagates the original failure without assigning a mutation outcome. |
| Operational failure outside a mutation | `NOT_APPLICABLE`. For a failed indexed read, the reason distinguishes corruption, incomplete records, invalid offsets or index references, incompatible payload sizes, and storage I/O. Startup also distinguishes invalid segment metadata, lock contention, invalid lock files and ownership I/O. |
| A subsequent operation on a failed or closed store | `IllegalStateException` before any file access. In `FAILED` it carries the first terminal failure. |

After an ambiguous failure, the live engine leaves the record bytes in place. A new owner reconstructs state from the surviving records on open. The recovery policy specifies which incomplete suffix can be removed and which failures must abort open. [Durability and recovery](durability-and-recovery.md)
