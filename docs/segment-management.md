# Segment management

The filesystem directory is the segment catalog. The segment with the greatest numeric ID is active and accepts appends. All lower IDs are historical and opened read-only. Gaps are legal. Without a manifest listing expected files, discovery cannot reliably detect a missing segment. [SegmentFileDiscoverer](../storage/src/main/java/com/ryszardzmija/shaledb/storage/hash/segment/files/SegmentFileDiscoverer.java)

## Discovery and names

After acquiring exclusive ownership, the engine lists immediate children of the directory. The case-sensitive prefix `segment_` reserves a name. A malformed reserved name, a nonregular reserved entry (including a symlink), duplicate numeric ID or duplicate filesystem file key aborts open with `INVALID_SEGMENT_METADATA`. Entries outside the prefix, including `LOCK`, are ignored by discovery. Detecting hard-linked segment aliases depends on the provider supplying file keys.

New names use `segment_%019d.log` with 19 zero-padded ASCII digits. IDs range from 1 through `Long.MAX_VALUE`. An empty layout starts with `segment_0000000000000000001.log`. Discovery accepts older ASCII decimal names with other padding and opens them in place. A localized name must use one decimal digit set representable in Java `char`s and either 19 digits or the legacy positive `int` width (at least six digits, padded only as needed). Signs, mixed digit sets, zero and overflow are invalid. Ordering is numeric across all accepted forms. Discovery and creation pass each segment's numeric ID with its path. The engine resolves a record location by comparing the active ID and looking up historical IDs in a hash registry. [Filename rules](../storage/src/main/java/com/ryszardzmija/shaledb/storage/hash/segment/files/FileFormatInfo.java)

## Choosing the destination

The engine validates the incoming record before checking the active file's physical size. It counts the full encoded size, including the 13-byte header on every record. Every admitted record fits in an empty segment. The engine rolls over before appending if the active file already exceeds `maxSegmentSize` or the record is larger than the remaining capacity (`maxSegmentSize - currentSize`). Subtraction avoids overflow. An exact fit stays in the current file. [SizeBasedRolloverPolicy](../storage/src/main/java/com/ryszardzmija/shaledb/storage/hash/segment/rollover/SizeBasedRolloverPolicy.java)

The segment limit governs new appends. Reopening with a smaller limit preserves valid historical records and offsets beyond the new threshold. A full or oversized active file remains readable, and the next valid mutation requires rollover. [Configuration](configuration.md)

## Rollover sequence

1. The engine checks for a successor ID before retiring the old writer. Allocation starts after the greatest discovered ID and consumes one ID per creation attempt within the engine instance. Exhaustion leaves the store available for reads and mutations that still fit.
2. The mutable segment closes the retiring writer and transfers its reader and read channel to a historical view with the same ID and path. The existing reader remains usable at the same offsets. Successful record and startup forces establish the durability of that segment before a higher ID can appear.
3. The factory creates the successor at its canonical name with `CREATE_NEW`, which preserves any colliding entry. It forces the empty file, closes the creation channel and forces the containing directory. The engine then opens the successor's read and write channels and verifies that the new file is empty. Initial segment creation uses the same publication protocol.
4. The engine installs the successor and registers the historical view under its original ID before accepting the append. The engine publishes the mutation to the global directory after append and force complete. Existing record locations resolve through the registry after retirement and require no updates. Caller serialization and directory ownership keep readers out of the handoff. [RolloverHandler](../storage/src/main/java/com/ryszardzmija/shaledb/storage/hash/segment/rollover/RolloverHandler.java) · [SegmentFileFactory](../storage/src/main/java/com/ryszardzmija/shaledb/storage/hash/segment/files/SegmentFileFactory.java)

On a failed publication, the factory attempts to remove only the segment it created and to force the directory after removal. A directory-channel close failure after a successful force leaves the published file in place. A later setup or handoff failure stops the engine. The historical reader is closed if registration fails, and the successor remains reachable for close. Channels that remain open after cleanup failure stay with pending cleanup under the database lock. A surviving empty successor is a valid active segment on reopen. [Durability and recovery](durability-and-recovery.md) · [Concurrency and lifecycle](concurrency-and-lifecycle.md)

Historical segment bytes remain immutable. Rollover adds a file and retains every earlier record, including obsolete values and their tombstones. A healthy open engine holds one reader per segment, one active writer and one lock-file channel.
