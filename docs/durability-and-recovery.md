# Durability and recovery

ShaleDB persists the append history. On open it accepts every complete record the reader validates, including one whose caller never received success. It has no commit marker, undo log, persisted index, checkpoint or alternate replica. The disk record, the in-memory visibility of its effect and the caller's acknowledgment are three distinct events. [RecordWriter](../storage/src/main/java/com/ryszardzmija/shaledb/storage/serialization/io/RecordWriter.java) · [SegmentWriter](../storage/src/main/java/com/ryszardzmija/shaledb/storage/hash/segment/model/SegmentWriter.java) · [IndexBuilder](../storage/src/main/java/com/ryszardzmija/shaledb/storage/hash/index/IndexBuilder.java)

## Boundaries on the success path

| Event | What has happened when it returns |
| --- | --- |
| Create file | An atomic create-new open has reserved the name. The factory has forced the new file's contents and metadata, closed its channel and forced the directory containing the file. When the server creates a new directory hierarchy, durability of the new ancestors requires separate synchronization. |
| Write record | `RecordWriter.write` has transferred the entire byte buffer and returned the starting offset and encoded size. File content may still be cached. Force and index publication follow the transfer. |
| Force file | `SYNC_EACH_WRITE` is the only defined mode. `forceFile` calls `FileChannel.force(true)` on the append channel, requesting persistence of contents and metadata. Success is the engine's durability boundary for that mutation, conditional on the storage stack honoring the call. |
| Publish and return | `SegmentWriter` records the new offset or deleted marker in memory. A normal embedded return follows. A remote client must additionally receive a successful RPC completion. |
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

## Interrupted rollover

The previous active file stays complete and unchanged throughout rollover. Its prior record and publication barriers protect its acknowledged history under the stated filesystem assumptions. Process termination can leave dirty pages and directory entries visible to the next process. An OS crash or power loss can lose unforced state. Highest-ID discovery determines the next active segment in either case, independent of the previous JVM's handoff.

| Interruption point | Process termination | OS crash or power loss | Next open and recovery implications |
| --- | --- | --- | --- |
| Before rollover or after old-writer retirement, before create-new | Only existing history is present. | The existing published layout survives. | The old file stays active. The incoming record is absent. |
| During creation or before both successor barriers complete | The successor may be absent or visible as an empty file. | The successor directory entry may be lost. A surviving successor is empty under the supported filesystem model. | The greatest surviving ID becomes active. Replay accepts the empty file and restores its publication barriers before use. |
| After both barriers, during successor initialization or in-memory handoff, or just before append | The successor is empty. The old file and incoming mutation are unchanged. | The published empty successor survives. | The successor is active. The previous file is historical and the incoming record is absent. |
| During append or after complete transfer, before a successful record force | The successor may expose zero bytes of the record, a prefix or a complete record. | Unforced bytes may be absent, incomplete or complete. | Complete valid records replay. An eligible short tail in the active file is removed and the retained prefix is forced. |
| After a successful record force, during index publication or before acknowledgment | The complete record is visible in the successor. | The complete record survives under the publication and record-force assumptions. | Replay applies the record even if the caller received no acknowledgment. |
| After acknowledgment | The complete record is visible in the successor. | Both the filename and the record satisfy their force barriers. | Replay preserves the acknowledged mutation. |

Runtime cleanup closes resources and retains the ownership lock until safe release. When creation itself fails, the factory may attempt cleanup of its own new file. A valid empty successor left after successful creation and a later failure remains eligible for discovery. This layout model cannot detect all missing files or identify a caller's acknowledgment from the log. [Segment management](segment-management.md)

## What the environment can invalidate

| Event | Recovery implications |
| --- | --- |
| Normal shutdown and reopen | With complete writer/reader-compatible history, stable names and exclusive access, reconstruction replays the same logical order. A clean shutdown cannot make an oversized record acceptable or repair an already malformed tail. |
| Unexpected process termination | The OS releases the file lock and leaves the `LOCK` entry available to reuse. JVM indexes are lost. The OS and its page cache may survive, so even an unforced complete record may be visible to the next owner. Replay requires complete, compatible historical files and applies the active-tail policy below. |
| Operating-system crash | Volatile OS cache can be lost. Survival of previously forced file data and necessary directory entries depends on the local filesystem's persistence semantics. A write in progress has no whole-record atomicity guarantee. |
| Power loss | Filesystem guarantees additionally depend on controller/device cache flushes and write ordering surviving loss of power. The code cannot observe whether a device honored a successful force. Unforced writes and newly created ancestors are uncertain. |
| Filesystem or device failure | Open, append, force, read and cleanup may independently fail. Neither another force nor a new process guarantees recovery. Damage can affect previously acknowledged records or the directory itself. |
| Arbitrary persistent corruption | Types, lengths and CRCs catch many damaged records during replay or a present lookup. Present lookups also detect valid records with the wrong key or type. A missing segment, clean truncation at a record boundary, or substitution of a valid normal record with the same key can still escape detection. |

The implementation assumes a provider that supports positioning the write channel at EOF, coherent positional reads from a second handle, and opening and forcing a directory. It also assumes durable directory ancestry, stable filenames and a storage stack with meaningful force semantics. The repository's real-file tests exercise the test host. They provide no power-cut or device-failure evidence. [Testing](testing.md)

## Supported append-prefix failure model

One cooperating engine owns the directory exclusively, and its operations are serialized. An interrupted append can leave zero bytes, a contiguous prefix of the encoded record, or the complete record. Previously acknowledged records and their published segment names survive under the supported filesystem and device assumptions. Fail-stop prevents an append after an ambiguous write. Pre-write rollover publishes a successor only after the old active segment is complete and has satisfied the applicable durability barriers. Together these properties confine an interrupted append to the highest-ID segment. [Fail-stop policy](storage-semantics.md#fail-stop-policy) · [Segment management](segment-management.md)

The model assumes that truncation preserves bytes before its requested boundary, including when recovery is interrupted. It also depends on exclusive file locks, stable directory ancestry, coherent file size and reads across the two active handles, and a local storage stack that honors file and directory force operations. Forcing a reopened active file must also persist retained bytes left dirty through an earlier handle. An OS crash or power loss can produce states outside that model. Arbitrary media corruption, non-prefix torn writes, external edits, missing segments, filesystem metadata damage and devices that violate flush semantics are outside the recovery contract.

## Startup recovery sequence

1. Use validated configuration and acquire exclusive ownership of the existing database directory. Discover and validate the segment layout and choose the highest numeric ID as active. An empty layout uses the durable initial creation path.
2. Open all historical segments read-only. Rebuild their indexes strictly, requiring a complete record sequence through the observed EOF. Any historical failure aborts before the active file can be changed.
3. Scan the active segment against its captured file size using the same decoder and replay loop used for history. Apply a record to the index only after complete decoding and CRC validation. Track the end of the last complete record using the validated encoded size. Confirm the scan extent remained stable and that it matches the active write handle.
4. If the scan identifies an eligible incomplete header or payload, truncate the active write channel to that tracked boundary, possibly zero, and verify the resulting size. A complete active file needs no truncation.
5. Force the active file with `force(true)` and then force the directory. The file force covers payload bytes and length metadata, including complete records whose previous synchronization is unknown. The directory force reestablishes publication for a discovered active filename, including an empty successor that became visible before its original directory barrier.
6. Install the loaded segments and their in-memory indexes and enter `OPEN`. The first append obtains the physical size from the write channel and positions itself there. Rollover likewise uses the recovered physical size. [HashIndexEngine](../storage/src/main/java/com/ryszardzmija/shaledb/storage/hash/HashIndexEngine.java) · [SegmentLoader](../storage/src/main/java/com/ryszardzmija/shaledb/storage/hash/segment/loader/SegmentLoader.java) · [IndexBuilder](../storage/src/main/java/com/ryszardzmija/shaledb/storage/hash/index/IndexBuilder.java)

The active reader and writer share the replayed index. Incomplete records contribute no index state. A complete overwrite or tombstone can supersede an acknowledged record even if the caller never received acknowledgment of the later operation. A complete empty value stays distinct from a deletion. [Indexing](indexing.md) · [Storage semantics](storage-semantics.md)

## Recovery policy

| Scan or startup outcome | Discovered active segment | Historical segment | Startup failure reason when applicable |
| --- | --- | --- | --- |
| Empty segment or EOF exactly at a complete record boundary | Retain the bytes and force the active file and directory | Accept with read-only replay | — |
| Fewer than 13 bytes at the next record boundary, with at least one byte present | Truncate the incomplete header | Reject and preserve the bytes | `INCOMPLETE_RECORD` for history |
| Complete valid header with compatible payload size, ending beyond the observed file extent | Truncate the incomplete payload and its header | Reject and preserve the bytes | `INCOMPLETE_RECORD` for history |
| Invalid type or lengths, or an unrepresentable encoded size | Reject and preserve the bytes | Reject and preserve the bytes | `STRUCTURAL_CORRUPTION` |
| Complete record with bad CRC32C | Reject and preserve the bytes | Reject and preserve the bytes | `CHECKSUM_CORRUPTION` |
| Payload exceeds the configured read limit | Reject and preserve the bytes | Reject and preserve the bytes | `INCOMPATIBLE_RECORD_SIZE` |
| Read error, repeated zero progress, unexpected transfer EOF, or a contradictory extent | Reject without truncation | Reject and preserve the bytes | `STORAGE_IO` |
| Invalid or ambiguous layout | Reject before opening segments | Reject before opening segments | `INVALID_SEGMENT_METADATA` |
| Truncation or verification of the resulting length fails | Reject. The length may already have changed. | — | `STORAGE_IO` |
| Required file or directory force fails | Reject. Any earlier truncation may remain in effect. | — | `DURABILITY` |

The extent check for a complete header follows type, length, representability and configured read-limit validation and precedes allocation of the payload buffer. An unexpected EOF during transfer contradicts the established extent. The reader carries that distinction along with the record offset, section and byte counts. Indexed reads still fail on a missing or incomplete expected record. Recovery carries startup failures through the existing exception chain, preserves the primary cause, and suppresses secondary cleanup failures. Ownership stays held until safe release. [Record format](record-format.md) · [Concurrency and lifecycle](concurrency-and-lifecycle.md)

## Interrupted recovery and repeatability

| Interruption boundary | Behavior on the next open |
| --- | --- |
| Before or during scanning, or after scanning and before truncation | Rebuild from the beginning and classify the same suffix if it survives. |
| During truncation | Validate the resulting bytes and remove any remaining eligible suffix. This depends on preservation of the validated prefix. |
| After truncation and before or during file or directory force | Scan the retained prefix and retry both startup barriers. If the shortened length did not survive and the suffix reappears, classify it again. |
| After the barriers, during initialization or before `OPEN` | Rebuild the indexes from disk and repeat the startup barriers. Exact EOF requires no further truncation. |

The record boundary comes from the bytes that survive each attempt. Under the model, repeating startup preserves the same complete records without a recovery marker. A failed attempt can leave an already shortened file. Startup never reconstructs discarded bytes.

## Limits of format and verification

The checksum covers type, lengths and payload when all covered bytes are present. A structurally plausible corrupted length can claim bytes beyond EOF, producing the same observation as an interrupted append. A short header can likewise come from a source outside the failure model. Detected structural or checksum corruption stops startup. The format cannot reliably detect a lost segment or history lost at an exact record boundary. Independently checksummed headers or stronger record framing could improve diagnosis. An authoritative, durably published description of the file set could make missing segments detectable.

M1.11 verifies recovery on the test host using real files and deterministic failure injection. M1.12 retains integrated subprocess crash verification at controlled boundaries. Power-loss and device-failure guarantees still depend on the stated storage model. [Testing](testing.md)
