# Concurrency and lifecycle

An engine has exclusive ownership of one database directory. Calls on that engine, including reads and close, must also be serialized by its caller. The gRPC server supplies a single worker. An embedded caller must provide equivalent serialization. Index transitions update two collections, rollover transfers a live reader, and close releases channels. Directory locking alone cannot protect these operations from one another. [StorageEngine](../storage/src/main/java/com/ryszardzmija/shaledb/storage/StorageEngine.java)

## Acquiring ownership

| State or resource | Actual owner and coordination |
| --- | --- |
| Database directory | A `DatabaseLock` manages a JVM-local reservation, one dedicated channel and an exclusive operating-system lock on `LOCK`. The engine holds it from before discovery through segment cleanup. |
| Active reference, history list, factory counter | `HashIndexEngine` and its factory mutate ordinary fields and an `ArrayList`. Switching active and publishing the old immutable takes two updates. Concurrent reads can observe an inconsistent layout. |
| Per-segment key state | Each segment owns its `HashIndex`. The mutable segment updates it after the append and force return, and both segment types consult their own index before reading a record. The index transitions rely on serialized access. Historical indexes receive only lookups after initialization or retirement. |
| File position and record contents | `RecordWriter` queries the file size, positions the write channel at that offset and transfers the record. Two writers can observe the same size, and a sequence of short writes has no engine-level mutual exclusion. The value array is read twice during encoding. |
| Segment read channels | A read uses positional I/O, so it need not change a shared cursor. That fact alone cannot protect an index lookup or pin the channel between lookup and use. A failed rollover or close can release the channel a reader would use. |
| Server request execution | A single worker executes queued reads, mutations and response callbacks. The worker serializes those requests. Directory ownership applies separately at the storage boundary. [Server architecture](server-architecture.md) |

## Directory identity and lock file

The protected directory is `storage.segmentDir`. Open resolves it with `Path.toRealPath()` and uses its filesystem file key for the JVM reservation when supplied. Otherwise it uses the resolved path. The same resolved directory is used for segment I/O. Relative paths, existing `.` and `..` components and directory symlink aliases resolve through the filesystem. Case aliases are supported when the provider reports a common file key or canonical path. [DatabaseLock](../storage/src/main/java/com/ryszardzmija/shaledb/storage/hash/DatabaseLock.java)

After reserving the directory, the engine opens the stable `LOCK` file on a dedicated channel and acquires an exclusive lock with nonblocking `tryLock()`. The reservation comes first because closing a second channel for the same file can release the JVM's existing native lock on some platforms. Contention in the registry or at the native lock reports `OPEN / DATABASE_LOCKED`.

The engine discovers, loads and recovers segments only after both ownership checks succeed. This prevents two owners from choosing a successor or repairing the same tail. Ownership spans every operation, rollover and segment cleanup. [DatabaseLock](../storage/src/main/java/com/ryszardzmija/shaledb/storage/hash/DatabaseLock.java)

`LOCK` is a regular file opened with `CREATE`, `WRITE` and `NOFOLLOW_LINKS`. Symlinks and detectable hard-linked lock files are rejected. The file stays in place across closes and process exits. Its contents carry no ownership or recovery state, and an existing unlocked file is reusable. Deleting or replacing it would allow two owners to lock different files under the same name.

Ownership assumes a local filesystem with reliable exclusive file locks, stable identity and cooperating processes. External segment edits, replacing the directory or lock entry while owned, unmanaged channels to the lock, and aliases that bypass directory identity are outside the operating model. Process termination releases the native lock. [Durability and recovery](durability-and-recovery.md)

## Operational state

| State | Behavior |
| --- | --- |
| `OPENING` | The engine holds ownership while it validates the layout, rebuilds indexes and completes startup forces. It installs the entire layout before entering `OPEN`. Failed construction unwinds acquired resources under ownership and returns no engine. |
| `OPEN` | The engine accepts serialized `get`, `put` and `delete`. Invalid input, ordinary misses and successor ID exhaustion leave the engine usable. |
| `FAILED` | A terminal lookup, mutation or rollover failure records its first cause. Normal calls throw `IllegalStateException` with that cause before checking arguments or accessing files. The engine retains ownership so another instance cannot operate alongside uncertain resources. Recovery requires a new open. |
| `CLOSED` | Entered immediately when close begins from `OPEN` or `FAILED`. Normal calls are permanently disabled, including if cleanup fails. Close can be called again. |

[Engine transitions and guards](../storage/src/main/java/com/ryszardzmija/shaledb/storage/hash/HashIndexEngine.java) · [Mutation outcomes](storage-semantics.md)

## Cleanup and release

The first close from `OPEN` attempts a final active-file force. Close from `FAILED` proceeds directly to cleanup. The engine retries outstanding segment channels, closes the active segment and every historical segment, and retains the first failure with subsequent cleanup failures suppressed. A channel close may report an error even when the channel has become unusable. [SegmentChannelCleanup](../storage/src/main/java/com/ryszardzmija/shaledb/storage/hash/segment/model/SegmentChannelCleanup.java)

If any segment channel remains open, ownership stays held and a repeated close retries the outstanding channels. It skips the final force and released segments. At failed construction, the registry keeps such channels and ownership reachable until process exit because the caller received no engine to close.

Once all segment channels are unusable, the engine releases the native lock, closes its channel and removes the JVM reservation. A successful lock-channel close permits release even if explicit unlock reported an error. If lock-channel close itself fails, native release is uncertain. The reservation remains until process exit and repeated close reports the saved failure. After successful release, repeated close is harmless. Unexpected native overlap during acquisition also retains the reservation and attempt channel to avoid interfering with an unmanaged JVM lock. [DatabaseLock](../storage/src/main/java/com/ryszardzmija/shaledb/storage/hash/DatabaseLock.java)
