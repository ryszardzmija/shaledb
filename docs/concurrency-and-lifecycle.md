# Concurrency and lifecycle

Each open engine holds exclusive ownership of its physical segment directory. The server executes its storage calls on one worker. Embedded callers must serialize reads, writes and close on each engine and keep input arrays stable during calls. Rollover runs within the mutation call. Ownership coordinates independent engine instances and leaves synchronization within an instance to its caller. [Project overview](../README.md) · [KeyValueStore](../storage/src/main/java/com/ryszardzmija/shaledb/storage/KeyValueStore.java) · [HashIndexEngine](../storage/src/main/java/com/ryszardzmija/shaledb/storage/hash/HashIndexEngine.java)

## Who owns which boundary?

| State or resource | Actual owner and coordination |
| --- | --- |
| Database directory | A `DatabaseLock` manages a JVM-local reservation, one dedicated channel and an exclusive operating-system lock on `LOCK`. The engine holds it from before discovery through segment cleanup. |
| Active reference, history list, factory counter | `HashIndexEngine` and its factory mutate ordinary fields and an `ArrayList`. Switching active and publishing the old immutable takes two updates. Concurrent reads can observe an inconsistent layout. |
| Per-segment key state | The segment reader and mutable writer share `HashIndex`. Its map and set and the two-step transitions lack synchronization. Older indexes are only read through the normal engine path after construction. |
| File position and record contents | `RecordWriter` queries the file size, positions the write channel at that offset and transfers the record. Two writers can observe the same size, and a sequence of short writes has no engine-level mutual exclusion. The value array is read twice during encoding. |
| Segment read channels | A read uses positional I/O, so it need not change a shared cursor. That fact alone cannot protect an index lookup or pin the channel between lookup and use. Rollover and close can close a reader's handle. |
| Server request execution | A single worker executes queued reads, mutations and response callbacks. The worker serializes those requests. Directory ownership applies separately at the storage boundary. [Server architecture](server-architecture.md) |

## Directory identity and lock file

The protected directory is `storage.segmentDir`. Open resolves it with `Path.toRealPath()` and uses its filesystem file key for the JVM reservation when supplied. Otherwise it uses the resolved path. The same resolved directory is used for segment I/O. Relative paths, existing `.` and `..` components and directory symlink aliases resolve through the filesystem. Case aliases are supported when the provider reports a common file key or canonical path. [DatabaseLock](../storage/src/main/java/com/ryszardzmija/shaledb/storage/hash/DatabaseLock.java)

`LOCK` must be a regular file and is opened with `WRITE`, `CREATE` and `NOFOLLOW_LINKS`. This rejects symbolic links at the lock pathname, including dangling links. On providers with Unix link counts, ShaleDB also rejects hard-linked lock entries. Concurrent creators use the same entry. Ownership depends on the native lock, so an existing unlocked file is reusable regardless of its contents. ShaleDB leaves the file in place during close and failed open and does not write or force its contents. [DatabaseLock](../storage/src/main/java/com/ryszardzmija/shaledb/storage/hash/DatabaseLock.java)

## Acquisition and lifecycle

1. `HashIndexEngine` reserves the directory atomically in a JVM-local registry before opening any channel to `LOCK`. Java documents that closing another channel on the same file can drop this JVM's lock on some systems. With the reservation in place, the engine opens its one channel and uses `FileChannel.tryLock()` for a non-blocking exclusive lock over the whole file. Contention fails `OPEN` with `DATABASE_LOCKED`. The native lock excludes other JVMs. [DatabaseLock](../storage/src/main/java/com/ryszardzmija/shaledb/storage/hash/DatabaseLock.java) · [Java 25 `FileLock`](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/channels/FileLock.html)
2. The engine discovers the layout, creates the first segment when needed, opens the segments and rebuilds their indexes. Constructors close channels acquired by a failed construction, and the loader closes any completed segments it still owns. The original initialization failure remains primary and cleanup failures are suppressed. If all segment channels are closed, startup releases ownership. If a segment channel remains open, the reservation retains the native lock and the outstanding channel. Failed open returns no engine to retry that cleanup, so the reservation lasts until process exit. [Segment management](segment-management.md)

| State | Event | Result |
| --- | --- | --- |
| `OPENING` | Configuration and exclusive ownership are established, and the validated segment layout and indexes are installed. | Enter `OPEN`. |
| `OPENING` | Initialization fails. | Clean up under ownership and propagate the failure. |
| `OPEN` | The engine records a terminal failure. | Enter `FAILED` and retain the first cause and exclusive ownership. |
| `OPEN` or `FAILED` | Close begins. | Enter `CLOSED` before cleanup. |
| `FAILED` or `CLOSED` | Another failure notification arrives. | Keep the current state. |
| `CLOSED` | Close is called again. | Stay `CLOSED` and handle any outstanding cleanup. |

`put`, `get` and `delete` require `OPEN` and check availability before arguments, indexes or channels. An unavailable engine throws `IllegalStateException` with the first terminal cause when in `FAILED`. A rollover error after retirement of the installed active segment begins triggers this state because its channels may already be closed. The policy for other operational errors remains open for M1.09. Recovery requires a new engine. [HashIndexEngine](../storage/src/main/java/com/ryszardzmija/shaledb/storage/hash/HashIndexEngine.java) · [Storage semantics](storage-semantics.md#embedded-exception-contract)

## Cleanup and ownership release

1. The first close of an `OPEN` engine attempts the existing final force. Cleanup of a `FAILED` engine goes directly to releasing resources, so it cannot force an uncertain append as part of closing. Mutation acknowledgment still depends on the per-write force. [Durability and recovery](durability-and-recovery.md)
2. The engine retries channels left open by a prior rollover or close, then closes the active segment and each historical segment it still owns. Each segment attempts all its channels. The first cleanup error stays primary, and later errors are suppressed. A segment close error remains visible even if the channel's `isOpen()` result establishes that further I/O through it is impossible.
3. A segment channel that remains open after an error transfers to `SegmentChannelCleanup`, which is reachable through the ownership reservation. The engine holds the native lock while such a channel remains open. Repeated close retries only outstanding channels and skips the final force and released segments.
4. Once no segment channels remain usable, the engine releases the native lock and closes the lock-file channel. A successful lock-channel close removes the exact JVM reservation and makes future close calls harmless, including after a reported force, segment close or explicit unlock error. A lock-channel close error leaves the native release outcome uncertain. ShaleDB retains the local reservation until process exit and repeated close reports the saved failure without repeating native release. If the JVM reports an unexpected overlap at acquisition, ShaleDB also retains the attempt channel to protect the unmanaged lock. [DatabaseLock](../storage/src/main/java/com/ryszardzmija/shaledb/storage/hash/DatabaseLock.java)

## Supported environments

Ownership protects cooperating processes on local filesystems with reliable exclusive Java file locks. The directory and `LOCK` must stay in place while a process owns the database. Direct segment edits, separate channels to `LOCK` in an owning JVM, lock-file deletion or replacement, renaming or replacing an active directory, hard-link aliases and unusual mount aliases are outside the supported protocol. Cooperating engines in one JVM must share the registry through one class loader. Network or distributed filesystems and providers with insufficient locking semantics are unsupported. The OS releases the native lock on process termination, and the next open independently validates the segment history. [Java 25 `FileChannel`](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/channels/FileChannel.html) · [Durability and recovery](durability-and-recovery.md)

A reader could capture the old active segment during rollover and use its read channel after the writer closes it. Publishing the historical view and the successor takes separate updates. Coordinating concurrent access would require publishing the complete layout and pinning the channels that in-flight readers can still reach.

## Architectural alternatives

- An internal lock or single engine-owned worker covering complete operations, including open/close transitions and rollover, could make safety independent of the entry point. A lock adds contention around I/O. An asynchronous worker adds a queue and an ownership decision for pending calls. External serialization keeps the present embedded path simple when one caller already owns it.
- Append and force errors can leave the active file attached to an `OPEN` engine. M1.09 will decide when those errors must trigger the existing `FAILED` transition. M1.11 covers recovery of interrupted writes. [Failure windows](durability-and-recovery.md)
- Concurrent reads with a serialized writer require a consistent view of both the active index and segment ordering, plus a way to keep channels alive until in-flight reads finish. Recovery needs a stable file set. Future compaction would also need to defer reclamation while any reader can still reach the old files. [Indexing](indexing.md) · [Segment management](segment-management.md)
