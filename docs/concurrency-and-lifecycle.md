# Concurrency and lifecycle

An engine has exclusive ownership of one database directory. Calls on that engine, including reads and close, must also be serialized by its caller. The gRPC server supplies a single worker. An embedded caller must provide equivalent serialization. The engine owns the global key directory and updates it after a durable append. Rollover changes how the engine resolves a segment ID and transfers ownership of the retired reader. Close releases channels and drops the directory. Directory locking alone cannot protect these operations from one another. [StorageEngine](../storage/src/main/java/com/ryszardzmija/shaledb/storage/StorageEngine.java)

## Acquiring ownership

The engine resolves the existing directory with `toRealPath()` and atomically reserves its identity in the JVM. The identity uses the filesystem and directory file key, falling back to the resolved path if the provider supplies no key. Relative paths and symlink aliases that resolve to the same directory share ownership. Cooperating engines use the same class loader so they share the registry.

After reserving the directory, the engine opens the stable `LOCK` file on a dedicated channel and acquires an exclusive lock with nonblocking `tryLock()`. The reservation comes first because closing a second channel for the same file can release the JVM's existing native lock on some platforms. Contention in the registry or at the native lock reports `OPEN / DATABASE_LOCKED`.

The engine discovers, loads and recovers segments only after both ownership checks succeed. This prevents two owners from choosing a successor or repairing the same tail. Ownership spans every operation, rollover and segment cleanup. [DatabaseLock](../storage/src/main/java/com/ryszardzmija/shaledb/storage/hash/DatabaseLock.java)

`LOCK` is a regular file opened with `CREATE`, `WRITE` and `NOFOLLOW_LINKS`. Symlinks and detectable hard-linked lock files are rejected. The file stays in place across closes and process exits. Its contents carry no ownership or recovery state, and an existing unlocked file is reusable. Deleting or replacing it would allow two owners to lock different files under the same name.

Ownership assumes a local filesystem with reliable exclusive file locks, stable identity and cooperating processes. External segment edits, replacing the directory or lock entry while owned, unmanaged channels to the lock, and aliases that bypass directory identity are outside the operating model. Process termination releases the native lock. [Durability and recovery](durability-and-recovery.md)

## Operational state

| State | Behavior |
| --- | --- |
| `OPENING` | The engine holds ownership while it validates the layout, reconstructs the provisional directory and completes startup forces. It installs the directory and segment registry before entering `OPEN`. Failed construction unwinds acquired resources under ownership and returns no engine. |
| `OPEN` | The engine accepts serialized `get`, `put` and `delete`. Invalid input, ordinary misses and successor ID exhaustion leave the engine usable. |
| `FAILED` | A terminal lookup, mutation or rollover failure records its first cause. Unexpected failures during directory publication or registry insertion also stop the engine. Normal calls throw `IllegalStateException` with that cause before checking arguments or accessing files. The engine retains ownership so another instance cannot operate alongside uncertain resources. Recovery requires a new open. |
| `CLOSED` | Entered immediately when close begins from `OPEN` or `FAILED`. Normal calls are permanently disabled, including if cleanup fails. Close can be called again. |

[Engine transitions and guards](../storage/src/main/java/com/ryszardzmija/shaledb/storage/hash/HashIndexEngine.java) · [Mutation outcomes](storage-semantics.md)

## Cleanup and release

The retiring writer closes before the reader transfers to the historical view. If rollover registry insertion throws, the engine closes the retired reader even if insertion linked it, and retains the successor for close. An interrupted startup registration similarly closes the segment being registered as well as the rest of the acquired segments. Segment close is idempotent, and ownership tracks any channels that stay open.

The first close from `OPEN` attempts a final active-file force. Close from `FAILED` proceeds directly to cleanup. The engine retries outstanding segment channels, closes the active segment and then visits the historical registry in insertion order. It retains the first failure and suppresses subsequent cleanup failures. A channel close may report an error even when the channel has become unusable. [SegmentChannelCleanup](../storage/src/main/java/com/ryszardzmija/shaledb/storage/hash/segment/model/SegmentChannelCleanup.java)

If any segment channel remains open, ownership stays held and a repeated close retries the outstanding channels. It skips the final force and released segments. At failed construction, the registry keeps such channels and ownership reachable until process exit because the caller received no engine to close.

Once all segment channels are unusable, the engine releases the native lock, closes its channel and removes the JVM reservation. A successful lock-channel close permits release even if explicit unlock reported an error. If lock-channel close itself fails, native release is uncertain. The reservation remains until process exit and repeated close reports the saved failure. After successful release, repeated close is harmless. Unexpected native overlap during acquisition also retains the reservation and attempt channel to avoid interfering with an unmanaged JVM lock. [DatabaseLock](../storage/src/main/java/com/ryszardzmija/shaledb/storage/hash/DatabaseLock.java)
