# ShaleDB architecture guide

ShaleDB stores binary keys and values in a local directory. Each change appends a record. An in-memory index for each file lets the engine find a value without replaying the log on every read. The same engine serves embedded Java callers and a small gRPC server.

This guide covers the storage engine through M1.07, including exclusive database-directory ownership, validated segment discovery and durable segment creation. Build and run commands are in the project overview, and storage invariants are in the engineering guidelines. [Project overview](../README.md) · [Engineering guidelines](../AGENTS.md)

## Reading order

| Order | Document | Question it answers |
| --- | --- | --- |
| 1 | [Architecture](architecture.md) | Which component owns each piece of state, and how does a call travel through the system? |
| 2 | [Storage semantics](storage-semantics.md) | What do a returned value, a successful mutation and a failed operation mean to a caller? |
| 3 | [Record format](record-format.md) | How can an independent reader decode and validate a segment byte by byte? |
| 4 | [Indexing](indexing.md) | How do offsets and deletion markers reconstruct the latest state? |
| 5 | [Segment management](segment-management.md) | Which file receives writes, how does that change, and who owns the open channels? |
| 6 | [Durability and recovery](durability-and-recovery.md) | What might survive at each failure boundary, and what can the next open recover? |
| 7 | [Concurrency and lifecycle](concurrency-and-lifecycle.md) | Who serializes access, owns the directory and releases resources? |
| 8 | [Configuration](configuration.md) | Which settings change admission, replay or the ability to read existing data? |
| 9 | [Server architecture](server-architecture.md) | How do RPCs enter the executor, reach the engine and receive an acknowledgment? |
| 10 | [Testing](testing.md) | Which observations have executable evidence, and where does that evidence end? |

## How to read the findings

Descriptions of **current behavior** follow method bodies, including the exceptions each layer actually catches. **Intended behavior** comes from an interface, project guideline or documented decision. A stated **design rationale** identifies its source. An **inference** explains a trade-off where the repository does not record a motive.

An **invariant** is a property the design needs to preserve. An **assumption** identifies something a caller or the environment must supply. **Guarantees** are qualified by those conditions. A **correctness defect** identifies a path that conflicts with a stated invariant or contract. A **failure mode** describes an outcome and the conditions under which it can occur. A **limitation** describes a boundary of cost or supported use.

The verification chapter identifies the test cases and their limits. Findings about crash outcomes follow from static inspection and their stated assumptions. Alternatives and open questions describe design choices that remain undecided. [Verification evidence](testing.md)
