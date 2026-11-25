# Durability and recovery

Under the append-prefix model below, successful mutations survive interruption. Recovery preserves the validated record history and applies its latest record for each key. A later complete record can supersede an acknowledged value even if its own caller never received success. The log has no per-record commit or acknowledgment marker.

## What a successful mutation establishes

| Event | What has happened when it returns |
| --- | --- |
| Create file | An atomic create-new open has reserved the name. The factory has forced the new file's contents and metadata, closed its channel and forced the directory containing the file. When the server creates a new directory hierarchy, durability of the new ancestors requires separate synchronization. |
| Write record | `RecordWriter.write` has transferred the entire byte buffer and returned the starting offset and encoded size. File content may still be cached. Force and index publication follow the transfer. |
| Force file | `SYNC_EACH_WRITE` is the only defined mode. `forceFile` calls `FileChannel.force(true)` on the append channel, requesting persistence of contents and metadata. `SegmentWriter` returns the original write result after force succeeds. This is the mutation's durability boundary, conditional on the storage stack honoring the call. |
| Publish and return | `MutableSegment` records the returned offset or a deleted marker in memory. A normal embedded return follows. A remote client must additionally receive a successful RPC completion. |
| Close and reopen | Close from `OPEN` attempts another force of the active file. Close from `FAILED` begins directly with channel cleanup. Ownership release follows channel cleanup. Reopen acquires ownership before validating the layout, ordering files by numeric ID and replaying them. A complete compatible history yields the same map after an ordinary reopen. |

The policy requires one file force per mutation and an active-file force when closing an operational engine. Initial creation and rollover each force the new file and then its directory before the successor can accept appends. On reopening a discovered active segment, startup replays and forces the file and then forces the directory before exposing the engine. A process can leave a filename visible in the OS cache even if its directory force never completed. Prior successful mutation forces and the applicable publication barriers establish the durability needed to retire the old writer. Segment publication assumes that the configured directory and its ancestors are already durable. [FileSystemSync](../storage/src/main/java/com/ryszardzmija/shaledb/storage/durability/FileSystemSync.java) · [SegmentFileFactory](../storage/src/main/java/com/ryszardzmija/shaledb/storage/hash/segment/files/SegmentFileFactory.java) · [HashIndexEngine](../storage/src/main/java/com/ryszardzmija/shaledb/storage/hash/HashIndexEngine.java)

The `LOCK` file provides runtime coordination and carries no recovery metadata. Ownership requires no force on that file. Segment publication retains its file and directory barriers. [Concurrency and lifecycle](concurrency-and-lifecycle.md)

## Failure windows

The table assumes serialized operations, an initially readable history, compatible settings and exclusive directory ownership. Bytes visible through a file channel can still be in volatile cache. An earlier acknowledged record can remain intact yet become unavailable because open requires *every* segment to replay successfully. The embedded contract defines which failures disable subsequent operations. [Fail-stop policy](storage-semantics.md#fail-stop-policy)

| Point of failure | Possible file and live index state | Caller and continued use | Next open and effect on earlier acknowledgments |
| --- | --- | --- | --- |
| During argument validation | Log and index retain the previous state. | The call fails and the engine stays open for corrected requests. | There is no new record to replay. |
| During append setup or header and payload transfer | A setup failure before the first write adds no bytes. A write failure can leave zero bytes, a prefix or a complete record. The index update and force are skipped on a write exception. | The engine reports the failure and prevents additional appends. | Zero bytes leave the previous state. A complete valid record takes effect. An eligible short active tail is removed. Detectable corruption stops open. |
| After complete append, before force | A full record reached the channel. The index still reflects its prior state. On a crash the record's survival is uncertain. | Loss of the process gives the caller no reliable acknowledgment. A live failure in the mutation path sends the engine to `FAILED`. | A surviving valid record takes effect. A missing record leaves the prior state. A surviving incomplete active tail is eligible for recovery. Detectable corruption blocks startup. |
| Force reports failure | The full append has been transferred. Its durability is uncertain. The index retains the prior value or marker. | The engine reports `DURABILITY` and enters `FAILED`. Close skips the final force. | A complete surviving append is replayed, including a delete. A mutation that reported failure can supersede an earlier acknowledgment. |
| After successful force, before or during index publication | The complete record has reached its file barrier. The index can still reflect its prior state or contain an interrupted update. | A live storage failure disables further access to the index. A process exit can prevent acknowledgment. | Rebuild derives the new state from the forced record. |
| During startup or replay | Earlier files may have been opened and partly or completely indexed in memory. Replay validates history and can shorten the active file at a validated record boundary. Empty-directory initialization can create the first segment. Reopen forces the discovered active file and directory after validating the history. | A failed load returns no engine. Expected open and replay failures trigger channel cleanup. A cause such as temporary I/O or incompatible settings can persist across attempts. | Another open starts again at offset zero in each file. Detectable corruption fails again. A successful truncation can remain in effect after a later startup failure. |
| During close | A failed final force leaves any previously uncertain bytes uncertain. The engine attempts channel release after that force failure. | A force or channel close failure reaches the caller. The first failure remains primary, with later cleanup failures suppressed. [Lifecycle](concurrency-and-lifecycle.md) | Reopen judges the actual surviving files. A final force error alone does not establish that earlier individually forced records were lost. |

The engine appends the entire record at physical EOF. Under `SYNC_EACH_WRITE`, its sole durability policy, it calls `FileChannel.force(true)` on the write channel to persist the record bytes and file-length metadata. After the force succeeds, the engine publishes the offset or deletion marker in the active index and returns. This order keeps a mutation whose force failed out of the live index. Remote acknowledgment also depends on the client receiving successful RPC completion. [FileSystemSync](../storage/src/main/java/com/ryszardzmija/shaledb/storage/durability/FileSystemSync.java) · [SegmentWriter](../storage/src/main/java/com/ryszardzmija/shaledb/storage/hash/segment/model/SegmentWriter.java)

A failed append can leave zero bytes, a prefix or the whole record. If the force fails, all bytes were transferred but their persistence is uncertain. If the force succeeds and index publication or response delivery fails, the record can be durable without acknowledgment. After a terminal storage failure the engine refuses further operations. This prevents reads from a possibly stale index and keeps a second append from burying a partial record. Closing a failed engine skips the final force. [Storage semantics](storage-semantics.md) · [Concurrency and lifecycle](concurrency-and-lifecycle.md)

## Supported failure model

One cooperating engine owns a stable local database directory and serializes operations. Previously synchronized record bytes, file lengths and published filenames survive. An interrupted append may contribute only a contiguous prefix of the incoming record, up to and including its full length. Rollover retires a complete segment before publishing a successor. Together with fail-stop, this confines an interrupted append to the highest-ID file.

Recovery depends on coherent sizes and reads across the active file's two handles, reliable exclusive locking, and a truncate that preserves bytes before the requested boundary even when interrupted. The filesystem must support forcing a directory. The storage stack must honor ordered file and directory forces, including a force on a reopened file covering retained dirty data from an earlier handle. The database directory and its ancestors must already have durable names. The server can create missing directories but does not force each new ancestor.

| Interruption | What may survive |
| --- | --- |
| Process termination | JVM indexes disappear and the OS releases the native lock. The OS page cache may retain a complete unforced record or a filename whose directory force never finished. |
| Operating-system crash | Volatile OS state may be lost. Acknowledged history depends on the filesystem preserving completed barriers. An in-progress record has no whole-record atomicity guarantee. |
| Physical power loss | Survival additionally depends on controller and device caches honoring flushes and write ordering. Forcing a file successfully in a test cannot establish physical power-loss behavior. |

## Startup under exclusive ownership

## Supported append-prefix failure model

One cooperating engine owns the directory exclusively, and its operations are serialized. An interrupted append can leave zero bytes, a contiguous prefix of the encoded record, or the complete record. Previously acknowledged records and their published segment names survive under the supported filesystem and device assumptions. Fail-stop prevents an append after an ambiguous write. Pre-write rollover publishes a successor only after the old active segment is complete and has satisfied the applicable durability barriers. Together these properties confine an interrupted append to the highest-ID segment. [Fail-stop policy](storage-semantics.md#fail-stop-policy) · [Segment management](segment-management.md)

The model assumes that truncation preserves bytes before its requested boundary, including when recovery is interrupted. It also depends on exclusive file locks, stable directory ancestry, coherent file size and reads across the two active handles, and a local storage stack that honors file and directory force operations. Forcing a reopened active file must also persist retained bytes left dirty through an earlier handle. An OS crash or power loss can produce states outside that model. Arbitrary media corruption, non-prefix torn writes, external edits, missing segments, filesystem metadata damage and devices that violate flush semantics are outside the recovery contract.

## Startup recovery sequence

1. Use validated configuration and acquire exclusive ownership of the existing database directory. Discover and validate the segment layout and choose the highest numeric ID as active. An empty layout uses the durable initial creation path.
2. Open all historical segments read-only. Rebuild their indexes strictly, requiring a complete record sequence through the observed EOF. Any historical failure aborts before the active file can be changed.
3. Run the same `SegmentScanner` used for history against the active file's captured extent. The scanner passes complete validated records and offsets to `IndexBuilder` and reports the end of the validated prefix after checking the final extent. The mutable segment verifies that the captured extent matches the active write handle.
4. If the scan identifies an eligible incomplete header or payload, truncate the active write channel to that tracked boundary, possibly zero, and verify the resulting size. A complete active file needs no truncation.
5. Force the active file with `force(true)` and then force the directory. The file force covers payload bytes and length metadata, including complete records whose previous synchronization is unknown. The directory force reestablishes publication for a discovered active filename, including an empty successor that became visible before its original directory barrier.
6. Install the loaded segments and their in-memory indexes and enter `OPEN`. The first append obtains the physical size from the write channel and positions itself there. Rollover likewise uses the recovered physical size. [HashIndexEngine](../storage/src/main/java/com/ryszardzmija/shaledb/storage/hash/HashIndexEngine.java) · [SegmentLoader](../storage/src/main/java/com/ryszardzmija/shaledb/storage/hash/segment/loader/SegmentLoader.java) · [IndexBuilder](../storage/src/main/java/com/ryszardzmija/shaledb/storage/hash/index/IndexBuilder.java)

The reconstructed index stays provisional until startup succeeds. A replay-consumer failure aborts initialization before truncation. Incomplete records contribute no index state. A complete overwrite or tombstone can supersede an acknowledged record even if the caller never received acknowledgment of the later operation. A complete empty value stays distinct from a deletion. [SegmentScanner](../storage/src/main/java/com/ryszardzmija/shaledb/storage/serialization/io/SegmentScanner.java) · [Indexing](indexing.md) · [Storage semantics](storage-semantics.md)

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
