# ShaleDB

<div align="center">
  <img src="assets/logo.png" alt="ShaleDB Logo" width="300"/>
  <p>
    <a href="https://github.com/ryszardzmija/shaledb/actions/workflows/ci.yaml">
      <img src="https://github.com/ryszardzmija/shaledb/actions/workflows/ci.yaml/badge.svg" alt="CI"/>
    </a>
  </p>
</div>

## Overview

ShaleDB is a persistent key-value store written in Java. It implements a local storage engine with append-only segment files, a global in-memory key directory, CRC32C-validated records, tombstone deletes, size-based segment rollover, chronological startup replay, conservative startup recovery, and validated YAML configuration. The store is also available through a small gRPC API for `Get`, `Put`, and `Delete`.

For the storage contract, binary format, recovery policy and developer testing guide, see the [architecture guide](docs/README.md).

## Building and running

ShaleDB requires JDK 25 or newer and uses Bazel for builds. Bazelisk is recommended because it reads `.bazelversion` and runs the pinned Bazel version for this repository.

Run the test suite with:

```bash
bazel test //:tests
```

Build the project with:

```bash
bazel build //server:server
```

Run the server with:

```bash
bazel run //server:server
```

The server reads `config/application.yaml` by default, stores segment files under `data/segments`, and exposes the gRPC API on port `50001`.

The gRPC API is defined in `api/src/main/proto/shaledb/key_value_store.proto` as `shaledb.v1.KeyValueStore`. It exposes three operations:

- `Get`
- `Put`
- `Delete`

## Project structure

The `api` module defines the `shaledb.v1.KeyValueStore` gRPC service and generated Java bindings.

The `storage` module contains the key-value store implementation. It owns the public `KeyValueStore` API, the hash-indexed storage engine, segment files, record serialization, durability handling, and configuration validation.

The `server` module contains the application entry point, configuration loading, and the gRPC service that connects the network API to the storage engine.

## Storage design

Writes are append-only. A `put` writes a normal record, while a `delete` writes a tombstone record. ShaleDB checks whether each complete encoded record fits before appending it. If space is insufficient, it publishes a new mutable segment and keeps previous segments immutable.

The global key directory maps each live key to its latest record's segment ID and byte offset. A hit goes directly to that record, and a miss requires no file access. Each delete appends a persistent tombstone and removes the key from the directory.

Records include a CRC32C checksum, record type, key length, value length, key bytes, and value bytes. Reads, writes, and chronological startup replay enforce the same record structure and payload limit. The engine rejects invalid mutations before append, and reads check for truncated records and checksum mismatches.

The durability mode is `SYNC_EACH_WRITE`. The segment writer returns the physical append result after forcing the file, and the engine then updates the key directory. On startup, a sequential scanner supplies validated records for replay and identifies eligible incomplete tails in the active segment. [Durability and recovery](docs/durability-and-recovery.md)

## Configuration

Runtime storage settings live in `config/application.yaml`. The configuration controls the maximum segment size, maximum payload size, segment directory, and durability mode. Configuration is validated at startup so missing fields or malformed YAML fail early.

## Current limitations

The current implementation should be treated as single-threaded. The gRPC service serializes storage operations through a single executor, and the storage engine does not support compaction, transactions, snapshots, or range scans. Startup rebuilds the key directory by scanning segment files in numeric ID order, so startup time grows with the amount of data on disk.
