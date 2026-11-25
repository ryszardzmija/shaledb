# Durability and recovery

Under the append-prefix model below, successful mutations survive interruption. Recovery preserves the validated record history and applies its latest record for each key. A later complete record can supersede an acknowledged value even if its own caller never received success. The log has no per-record commit or acknowledgment marker.

## What a successful mutation establishes

When the engine needs a new segment, it forces the new file and then its containing directory before accepting appends to that file. The file force persists the file contents and metadata. The directory force persists the name that recovery uses to find it. [Segment management](segment-management.md)

The segment writer appends the entire record at physical EOF. Under `SYNC_EACH_WRITE`, it calls `FileChannel.force(true)` on the write channel to persist the record bytes and file-length metadata. After the force succeeds, `SegmentWriter` returns the original append result from `RecordWriter`. The engine publishes the actual segment ID and returned offset for a put or removes the key for a delete. A failed append or force leaves the directory untouched. Remote acknowledgment also depends on the client receiving successful RPC completion. [FileSystemSync](../storage/src/main/java/com/ryszardzmija/shaledb/storage/durability/FileSystemSync.java) · [SegmentWriter](../storage/src/main/java/com/ryszardzmija/shaledb/storage/hash/segment/model/SegmentWriter.java) · [HashIndexEngine](../storage/src/main/java/com/ryszardzmija/shaledb/storage/hash/HashIndexEngine.java)

A failed append can leave zero bytes, a prefix or the whole record. If the force fails, all bytes were transferred but their persistence is uncertain. If the force succeeds and directory publication or response delivery fails, the record can be durable without acknowledgment. After a terminal storage failure the engine refuses further operations. This prevents reads from a possibly stale directory and keeps a second append from burying a partial record. Closing a failed engine skips the final force. [Storage semantics](storage-semantics.md) · [Concurrency and lifecycle](concurrency-and-lifecycle.md)

## Supported failure model

One cooperating engine owns a stable local database directory and serializes operations. Previously synchronized record bytes, file lengths and published filenames survive. An interrupted append may contribute only a contiguous prefix of the incoming record, up to and including its full length. Rollover retires a complete segment before publishing a successor. Together with fail-stop, this confines an interrupted append to the highest-ID file.

Recovery depends on coherent sizes and reads across the active file's two handles, reliable exclusive locking, and a truncate that preserves bytes before the requested boundary even when interrupted. The filesystem must support forcing a directory. The storage stack must honor ordered file and directory forces, including a force on a reopened file covering retained dirty data from an earlier handle. The database directory and its ancestors must already have durable names. The server can create missing directories but does not force each new ancestor.

| Interruption | What may survive |
| --- | --- |
| Process termination | The in-memory directory disappears and the OS releases the native lock. The OS page cache may retain a complete unforced record or a filename whose directory force never finished. |
| Operating-system crash | Volatile OS state may be lost. Acknowledged history depends on the filesystem preserving completed barriers. An in-progress record has no whole-record atomicity guarantee. |
| Physical power loss | Survival additionally depends on controller and device caches honoring flushes and write ordering. Forcing a file successfully in a test cannot establish physical power-loss behavior. |

## Startup under exclusive ownership

1. The engine acquires ownership before examining segment files. It validates the layout and selects the segment with the highest ID as active. An empty layout gets a durably created initial segment.
2. The engine opens historical segments read-only and replays them to exact EOF in ascending numeric segment ID order. Every record is validated, including obsolete values and tombstones. Any historical failure aborts startup before the active file can be modified. Truncating historical damage could silently erase a mutation that was complete before rollover.
3. The same `SegmentScanner` scans the discovered active segment against a captured file size. It streams complete, validated records and their starting offsets directly into provisional replay, then verifies the final read extent and returns the validated prefix boundary and any eligible incomplete-tail cause. The mutable segment checks that the write handle's size matches the captured extent.
4. If the scan finds an eligible tail, the engine truncates to the **start of the incomplete record**, which may be zero, and verifies the resulting length. The rebuilt directory includes only the effects of complete records. The recovery table describes which tails qualify.
5. The engine forces the discovered active file and then its directory before entering `OPEN`, even if the scan reached exact EOF or the successor is empty. The file force persists retained complete records and any length change before the file can become historical. The directory force persists the name even if it was previously visible only in cache. A fresh initial segment passes both barriers at creation. [SegmentScanner](../storage/src/main/java/com/ryszardzmija/shaledb/storage/serialization/io/SegmentScanner.java) · [SegmentLoader](../storage/src/main/java/com/ryszardzmija/shaledb/storage/hash/segment/loader/SegmentLoader.java)

The global directory and segment registry stay provisional until startup succeeds. If applying a validated record throws, replay aborts before truncation. Failed open cleans up acquired resources under exclusive ownership and retains the primary cause. [Concurrency and lifecycle](concurrency-and-lifecycle.md)

## Recovery policy

Each row describes the observation at the next verified record boundary. Rejection during scan preserves the bytes in question.

| Observation | Discovered active segment | Historical segment |
| --- | --- | --- |
| Complete, size-compatible and structurally valid record with matching CRC32C | Apply record and advance | Same |
| Exact EOF, including a zero-byte file | Finish scan and perform both startup forces | Finish scan |
| 1–12 bytes available for the next header | Remove the incomplete header | Fail with `INCOMPLETE_RECORD` |
| Complete valid, size-compatible header declares more payload bytes than remain | Remove the header and partial payload | Fail with `INCOMPLETE_RECORD` |
| Invalid type, lengths or unrepresentable encoded size | Fail with `STRUCTURAL_CORRUPTION` | Same |
| Complete record with a checksum mismatch | Fail with `CHECKSUM_CORRUPTION`, including at the end of the file | Same |
| Representable payload exceeds `maxPayloadSize` | Fail with `INCOMPATIBLE_RECORD_SIZE` before checking payload extent | Same |
| Ordinary read I/O error, repeated zero progress, premature transfer EOF within an established extent, or inconsistent sizes | Fail with `STORAGE_IO` | Same for historical replay |

A checksum mismatch on a complete record cannot establish which bytes are expendable. Discarding it could roll back acknowledged history. A failed truncation or length check reports storage I/O and can leave the file already shortened. A failed required force aborts open with a durability error. Cleanup preserves the primary failure. [Record format](record-format.md) · [Concurrency and lifecycle](concurrency-and-lifecycle.md)

## Interrupted rollover and recovery

If rollover stops before creating the successor, the retiring file is still the highest ID on disk. During publication, a successor name may or may not survive. After both creation barriers it names a durable empty file, even if the process never installed it in memory. The next owner uses the highest surviving ID in all three cases. If an append started in the successor, the same active-tail rules apply.

Recovery is idempotent under the stated assumptions. After interruption during scanning, truncation or either force, the next open scans from zero again. If the old length survives, it removes any remaining eligible suffix. If the shorter length survives, it retains the validated prefix. Both paths force the discovered active file and directory again before use. Further writes start at the recovered EOF.

## Detection limits

The format lacks an independently verifiable header. A damaged but plausible length pointing past EOF can look exactly like an interrupted payload. A missing segment, loss at a clean record boundary or substitution by another valid record can also go undetected. Recovery cannot reconstruct arbitrary media damage, non-prefix torn writes, external edits or lost files. Real-file tests and deterministic faults exercise the policy and ordering on the test host. Physical durability depends on the environment above. [Testing](testing.md)
