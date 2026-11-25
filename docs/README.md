# ShaleDB architecture guide

ShaleDB is a local, append-only store for binary keys and values. Both embedded Java callers and the gRPC server use the same storage engine. [Build and run commands](../README.md) · [Engineering guidelines](../AGENTS.md)

## Storage engine

Start with [Architecture](architecture.md) and [Storage semantics](storage-semantics.md) for the system overview and the behavior of reads, writes and deletes.

- [Record format](record-format.md) covers binary layout, checksum and validation.
- [Indexing](indexing.md) covers per-segment lookup and index reconstruction.
- [Segment management](segment-management.md) covers file discovery, capacity and rollover.
- [Durability and recovery](durability-and-recovery.md) covers acknowledgment, filesystem assumptions and startup repair.

## Operation and testing

- [Concurrency and lifecycle](concurrency-and-lifecycle.md) covers caller serialization, directory locking and cleanup.
- [Configuration](configuration.md) covers settings and compatibility when reopening a store.
- [Server architecture](server-architecture.md) covers request execution, RPC status mapping and shutdown.
- [Testing](testing.md) covers the suites, test methods and the limits of durability verification.
