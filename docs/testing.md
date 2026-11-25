# Testing

ShaleDB uses JDK 25 and Bazel 9.1.0. Bazelisk selects the version pinned in the repository. Run the storage suite or the combined storage and server suite from the repository root.

```bash
bazel test //storage:storage_tests
bazel test //:tests
```

Both suites use JUnit 5 and AssertJ. The storage suite also uses Mockito for controlled faults. GitHub Actions runs the combined suite on Ubuntu with Temurin 25 and Bazelisk for pushes and pull requests. [Bazel version](../.bazelversion) · [Storage test target](../storage/BUILD.bazel) · [Server test target](../server/BUILD.bazel) · [CI workflow](../.github/workflows/ci.yaml)

## Storage tests

Record tests compare encoder output against literal byte fixtures and feed the same known bytes to the decoder independently. This catches format changes that a round trip can miss. Unit tests also cover binary key identity, index transitions and offsets, and short or stalled file transfers. [RecordSerializationTest](../storage/src/test/java/com/ryszardzmija/shaledb/storage/serialization/io/RecordSerializationTest.java) · [HashIndexTest](../storage/src/test/java/com/ryszardzmija/shaledb/storage/hash/index/HashIndexTest.java) · [RecordTransferTest](../storage/src/test/java/com/ryszardzmija/shaledb/storage/serialization/io/RecordTransferTest.java)

Real-file tests use temporary databases to exercise mutation validation, rollover and replay. They compare the exact bytes on disk to check that rejected mutations leave the log intact and that rollover preserves historical records. The reference-model test compares lookups against a map after seeded writes, deletes, reads and reopens. Small segments exercise rollover, and the seed in assertion failures makes the sequence reproducible. [KeyValueStoreValidationTest](../storage/src/test/java/com/ryszardzmija/shaledb/storage/KeyValueStoreValidationTest.java) · [KeyValueStoreRolloverTest](../storage/src/test/java/com/ryszardzmija/shaledb/storage/KeyValueStoreRolloverTest.java) · [KeyValueStoreModelTest](../storage/src/test/java/com/ryszardzmija/shaledb/storage/KeyValueStoreModelTest.java)

Recovery fixtures place complete records and partial record prefixes directly in segment files. The tests check which bytes startup retains, whether it rejects corrupt records or incomplete historical segments, and whether subsequent appends remain readable. Injected faults in I/O, index publication, truncation, force and cleanup exercise failure ordering and verify that a failed engine stops accepting requests. [KeyValueStoreRecoveryTest](../storage/src/test/java/com/ryszardzmija/shaledb/storage/KeyValueStoreRecoveryTest.java) · [KeyValueStoreFailureTest](../storage/src/test/java/com/ryszardzmija/shaledb/storage/KeyValueStoreFailureTest.java) · [KeyValueStoreRecoveryFailureTest](../storage/src/test/java/com/ryszardzmija/shaledb/storage/KeyValueStoreRecoveryFailureTest.java) · [KeyValueStoreFailStopTest](../storage/src/test/java/com/ryszardzmija/shaledb/storage/KeyValueStoreFailStopTest.java)

## Subprocess tests and durability limits

Separate JVMs exercise native lock contention and reacquisition. Forcibly terminating an idle owner verifies that another process can acquire the lock. The storage suite models interruption during writes through file fixtures and fault injection. Process termination can leave the OS cache intact, and injected faults verify software behavior at known boundaries. Survival across an OS crash or physical power loss depends on filesystem and device behavior. [DatabaseOwnershipProcessTest](../storage/src/test/java/com/ryszardzmija/shaledb/storage/DatabaseOwnershipProcessTest.java) · [Durability and recovery](durability-and-recovery.md)

## Server tests

## Execution for M1.07

| Bazel invocation | Baseline | With M1.07 |
| --- | --- | --- |
| `bazel test //storage:storage_tests` | 20 targets, 182 JUnit cases passed | 23 targets, 207 JUnit cases passed |
| `bazel test //:tests` | 22 targets, 197 JUnit cases passed | 25 targets, 222 JUnit cases passed |

The runs used Linux, the Bazel Java 25 runtime, a writable temporary Bazel cache and an explicit Mockito agent. The complete suite ran with loopback access for the gRPC tests. The final JUnit reports recorded zero failures or skipped cases.

The ownership tests use real files for same-JVM contention, equivalent paths, independent directories, lock-file reuse and startup ordering. Fault injection checks cleanup order, conservative retention of ownership and preservation of secondary failures. [DatabaseLockTest](../storage/src/test/java/com/ryszardzmija/shaledb/storage/hash/DatabaseLockTest.java) · [DatabaseOwnershipIntegrationTest](../storage/src/test/java/com/ryszardzmija/shaledb/storage/DatabaseOwnershipIntegrationTest.java) · [KeyValueStoreIoFailureTest](../storage/src/test/java/com/ryszardzmija/shaledb/storage/KeyValueStoreIoFailureTest.java)

Three subprocess cases open `KeyValueStore` through the production path in separate JVMs. They verify exclusion, that a rejected same-JVM open leaves the native lock held, and reacquisition after orderly close and forced termination. They use readiness signaling and bounded waits. The forced termination case uses an idle database to exercise OS lock release. Crash recovery of interrupted writes requires separate verification. [DatabaseOwnershipProcessTest](../storage/src/test/java/com/ryszardzmija/shaledb/storage/DatabaseOwnershipProcessTest.java)

## Execution for M1.06

| Bazel invocation | Baseline | With M1.06 |
| --- | --- | --- |
| `bazel test //storage:storage_tests` | 19 targets, 167 JUnit cases passed | 20 targets, 182 JUnit cases passed |
| `bazel test //:tests` | 21 targets, 182 JUnit cases passed | 22 targets, 197 JUnit cases passed |

The baseline ran from a clean copy of the starting revision. Both runs used Java 25, an explicit Mockito agent and fresh test results. The full suite used loopback for the gRPC integration test.

## Execution for M1.05

| Bazel invocation | Baseline | With M1.05 |
| --- | --- | --- |
| `bazel test //storage:storage_tests` | 16 targets, 154 JUnit cases passed | 19 targets, 167 JUnit cases passed |
| `bazel test //:tests` | 18 targets, 169 JUnit cases passed | 21 targets, 182 JUnit cases passed |

The runs used fresh test results, a writable host Bazel cache, the Bazel Java 25 runtime and an explicit Mockito agent. The full suite used loopback for the gRPC integration test. JUnit reports recorded zero failures or skipped cases.

## Execution for M1.04

| Bazel invocation | Baseline | With M1.04 |
| --- | --- | --- |
| `bazel test //storage:storage_tests` | 14 targets, 133 JUnit cases passed | 16 targets, 154 JUnit cases passed |
| `bazel test //:tests` | 16 targets, 148 JUnit cases passed | 18 targets, 169 JUnit cases passed |

The baseline ran from a clean copy of the starting revision. The runs used fresh test results, the Bazel Java 25 runtime and an explicit Mockito agent. The full suite used a writable host output cache and loopback socket access for the gRPC integration test.

## Execution for M1.03

| Bazel invocation | Baseline | With M1.03 |
| --- | --- | --- |
| `bazel test //storage:storage_tests` | 13 targets, 112 JUnit cases passed | 14 targets, 133 JUnit cases passed |
| `bazel test //:tests` | 15 targets, 127 JUnit cases passed | 16 targets, 148 JUnit cases passed |

The runs used fresh test results, a temporary Bazel output location, the Bazel Java 25 runtime and an explicit Mockito agent. The full suite used loopback socket access for its gRPC integration test. The 21 additional cases exercise record I/O, with 14 in the new transfer test target.

## Execution for M1.02

| Bazel invocation | Baseline | With M1.02 |
| --- | --- | --- |
| `bazel test //storage:storage_tests` | 11 targets, 83 JUnit cases passed | 13 targets, 112 JUnit cases passed |
| `bazel test //:tests` | 13 targets, 98 JUnit cases passed | 15 targets, 127 JUnit cases passed |

The runs used fresh test results, a temporary Bazel output location and the Bazel Java 25 runtime. The counts are from Bazel and JUnit XML. The suite exercised real local files and a loopback RPC server. Findings about untested failure windows, resource leaks and concurrent owners in this guide are from inspection of production code.

Two focused runtime observations supplement the suite. The production YAML loader mapped numeric `durabilityMode: 0` to `SYNC_EACH_WRITE`. Separately, the JDK 25 filename formatting and matching expressions used by the factory and discoverer disagreed under a locale with Arabic-Indic digits. The predicted restart consequences of that mismatch follow from the source. [Configuration](configuration.md) · [Segment management](segment-management.md)

## Evidence by boundary

| Boundary exercised | What the tests establish | Where the evidence ends |
| --- | --- | --- |
| Format and payload model | Type codes, byte order, header widths, minimum size, empty values, and rejection of negative lengths or absent arrays. Synthetic lengths cover both payload-sum and header-addition overflow without large allocations. [RecordFormatTest](../storage/src/test/java/com/ryszardzmija/shaledb/storage/serialization/spec/RecordFormatTest.java) · [RecordPayloadTest](../storage/src/test/java/com/ryszardzmija/shaledb/storage/serialization/record/RecordPayloadTest.java) | The arithmetic tests exercise representability without allocating maximum-size buffers. |
| Writer and reader on real temporary files | Literal normal and tombstone vectors verify each direction separately. Consecutive records check reported offsets, encoded sizes and a normal empty value. Reopen checks cover both a freshly opened write channel and the production mutable segment. Byte comparisons check that reopening for append preserves history and that invalid writes leave it intact. [RecordSerializationTest](../storage/src/test/java/com/ryszardzmija/shaledb/storage/serialization/io/RecordSerializationTest.java) · [RecordWriterTest](../storage/src/test/java/com/ryszardzmija/shaledb/storage/serialization/io/RecordWriterTest.java) | Real-file success cases exercise normal filesystem behavior on the test host. |
| Reader validation on constructed files | Real files cover every truncated prefix of a representative record, a large payload declared in a small file, configured limits, malformed records and CRC changes. Empty and complete scans succeed. Incomplete suffixes following complete records retain their typed failure through index rebuild. Byte comparisons verify that failed reads and scans preserve the file. [RecordReaderTest](../storage/src/test/java/com/ryszardzmija/shaledb/storage/serialization/io/RecordReaderTest.java) | Engine startup cleanup and recovery after failed appends remain outside this coverage. |
| Binary keys and index transitions | Tests verify owned binary keys, stable equality and hashing, collision independence and all six state transitions, including the offset of an overwrite. [HashIndexTest](../storage/src/test/java/com/ryszardzmija/shaledb/storage/hash/index/HashIndexTest.java) | The transitions assume serialized access. |
| Direct reads and indexed lookups | Real records cover wrong-key and wrong-type associations, normal empty values and reads at zero and nonzero offsets. Direct reads also exercise negative offsets, exact EOF, beyond EOF, truncation, structural and checksum corruption, payload limits and injected I/O failure. Failed indexed reads terminate lookup even with an older value available. [SegmentReaderTest](../storage/src/test/java/com/ryszardzmija/shaledb/storage/hash/segment/model/SegmentReaderTest.java) · [KeyValueStoreFailureTest](../storage/src/test/java/com/ryszardzmija/shaledb/storage/KeyValueStoreFailureTest.java) | Latest-record selection depends on index publication and replay order. |
| Sequential scan and replay | Real records exercise file-order delivery, physical offsets, empty values, tombstones, incomplete tails and corrupt suffixes. Fault injection verifies rejection of transfer EOF and extent changes. A replay-consumer failure aborts delivery and the failed open preserves an otherwise recoverable tail. [SegmentScannerTest](../storage/src/test/java/com/ryszardzmija/shaledb/storage/serialization/io/SegmentScannerTest.java) · [KeyValueStoreRecoveryFailureTest](../storage/src/test/java/com/ryszardzmija/shaledb/storage/KeyValueStoreRecoveryFailureTest.java) | Recovery depends on final extent checks and the startup barriers. |
| Live and rebuilt indexes | Real segment histories exercise empty segments, exact EOF, binary and colliding keys, mixed record lengths, overwrites, repeated deletes and reinsertion. Assertions compare live and rebuilt states and check replayed physical offsets. Strict rebuild rejects an incomplete tail and a failed read preserves its cause and the file bytes. [IndexBuilderTest](../storage/src/test/java/com/ryszardzmija/shaledb/storage/hash/index/IndexBuilderTest.java) | An eligible active tail contributes only its validated record prefix to the replayed index. |
| Record transfer fault injection | Mockito transfers actual bytes for short reads and writes and checks positional offsets and exact encoded bytes. Tests cover zero-progress retries and termination, EOF after an initially sufficient extent, original I/O causes and offsets near `Long.MAX_VALUE`. [RecordTransferTest](../storage/src/test/java/com/ryszardzmija/shaledb/storage/serialization/io/RecordTransferTest.java) | Controlled failures establish the record-layer contracts. Physical persistence after an OS crash or power loss requires separate tests. |
| Durable append and index publication | Real writes check returned offsets against record boundaries and verify normal values, empty values and tombstones. Injected faults exercise append and force failures at the writer and confirm the segment skips index publication. Engine tests verify force before publication and replay after a publication failure. [SegmentWriterTest](../storage/src/test/java/com/ryszardzmija/shaledb/storage/hash/segment/model/SegmentWriterTest.java) · [KeyValueStoreFailStopTest](../storage/src/test/java/com/ryszardzmija/shaledb/storage/KeyValueStoreFailStopTest.java) | The tests observe the force boundary and resulting bytes on the test host. |
| Segment retirement | Retirement closes the writer and transfers the existing reader and local state. The test verifies no read-channel interactions during retirement and successful historical lookups through the original channel. [MutableSegmentTest](../storage/src/test/java/com/ryszardzmija/shaledb/storage/hash/segment/model/MutableSegmentTest.java) | Engine-level fault injection separately exercises interrupted rollover and its cleanup. |
| Segment discovery and reopen | Real paths exercise numeric ordering across filename forms, invalid names, duplicate IDs, links, empty layouts and invalid directories. A directory-stream fault verifies cleanup and cause preservation. Store reopen verifies historical lookup, tombstone precedence, active file selection and allocation after a gap. [SegmentFileDiscovererTest](../storage/src/test/java/com/ryszardzmija/shaledb/storage/hash/segment/files/SegmentFileDiscovererTest.java) · [KeyValueStoreFailureTest](../storage/src/test/java/com/ryszardzmija/shaledb/storage/KeyValueStoreFailureTest.java) · [KeyValueStorePersistenceTest](../storage/src/test/java/com/ryszardzmija/shaledb/storage/KeyValueStorePersistenceTest.java) | File identity checks rely on the provider supplying file keys. |
| Segment creation and synchronization | Real paths exercise canonical names under a non-ASCII format locale, non-destructive collisions and ID exhaustion. Injected faults check file-before-directory force order, channel closure, cleanup and suppression of secondary failures. [SegmentFileFactoryTest](../storage/src/test/java/com/ryszardzmija/shaledb/storage/hash/segment/files/SegmentFileFactoryTest.java) · [KeyValueStoreIoFailureTest](../storage/src/test/java/com/ryszardzmija/shaledb/storage/KeyValueStoreIoFailureTest.java) | The tests observe barrier calls and failure handling on the test host. Physical crash survival requires separate evidence. |
| Configuration boundaries | The default and minimum configurations are valid. Tests cover encoded size equal to and one byte above the segment limit, the integer representation boundary, and rejection of extreme long inputs. Mapping tests verify that invalid size settings retain the constructor's diagnostic. [StorageConfigTest](../storage/src/test/java/com/ryszardzmija/shaledb/storage/config/StorageConfigTest.java) · [StorageConfigMapperTest](../storage/src/test/java/com/ryszardzmija/shaledb/storage/config/StorageConfigMapperTest.java) · [ApplicationConfigLoaderTest](../server/src/test/java/com/ryszardzmija/shaledb/server/config/ApplicationConfigLoaderTest.java) | These assertions do not show that previously written files remain readable after settings change. |
| Engine mutation admission | Oversized inserts, overwrites and deletes preserve segment filenames and exact contents, including two bytes below the rollover threshold. Tests also cover later valid writes, ordinary reopen and exact-limit puts and tombstones. [KeyValueStoreValidationTest](../storage/src/test/java/com/ryszardzmija/shaledb/storage/KeyValueStoreValidationTest.java) | Input rejection is exercised with a healthy, exclusive store and ordinary file I/O. |
| Embedded failure contract | Real files exercise malformed and incomplete history, unreadable segments, read limits, invalid indexed locations and rollover errors. Injected channel faults verify the public operation and reason, underlying exception identity, fail-stop and suppression during startup, rollover and close. [KeyValueStoreFailureTest](../storage/src/test/java/com/ryszardzmija/shaledb/storage/KeyValueStoreFailureTest.java) · [KeyValueStoreIoFailureTest](../storage/src/test/java/com/ryszardzmija/shaledb/storage/KeyValueStoreIoFailureTest.java) | Actual persistence after a failed barrier requires separate crash verification. |
| RPC through storage | A blocking client uses a real Netty server on an ephemeral localhost port, a single-worker executor and a temporary store. Tests verify put/get/delete, response contents, and empty-key rejection for all methods. Teardown shuts down and awaits channel, server and executor, then closes the store. [GrpcKeyValueStoreServiceIntegrationTest](../server/src/test/java/com/ryszardzmija/shaledb/server/grpc/GrpcKeyValueStoreServiceIntegrationTest.java) | The fixture constructs these objects directly. It does not start `Application.main`, exercise its JVM hook, reopen the store, force rollover, or check queued, cancelled and timed-out mutations. |

## Properties with limited evidence

The M1.01 persistence tests exercise several rollovers, overwrites, tombstone shadowing and ordinary reopens. The deterministic model tests compare live and replayed state with a reference map at three fixed seeds. Configuration regressions verify that an incompatible payload limit rejects historical records at open and a reduced rollover threshold leaves valid historical offsets readable. [KeyValueStorePersistenceTest](../storage/src/test/java/com/ryszardzmija/shaledb/storage/KeyValueStorePersistenceTest.java) · [KeyValueStoreModelTest](../storage/src/test/java/com/ryszardzmija/shaledb/storage/KeyValueStoreModelTest.java) · [KeyValueStoreFailureTest](../storage/src/test/java/com/ryszardzmija/shaledb/storage/KeyValueStoreFailureTest.java)

**Fault injection is local.** `SegmentWriter` accepts a record writer, and the file factory accepts a sync helper. Engine-level tests use scoped Mockito static and constructor substitutions around existing channels, indexes and sync helpers. [MutableSegment](../storage/src/main/java/com/ryszardzmija/shaledb/storage/hash/segment/model/MutableSegment.java) · [KeyValueStoreFailStopTest](../storage/src/test/java/com/ryszardzmija/shaledb/storage/KeyValueStoreFailStopTest.java)

## What different verification methods can establish

| Method | Architectural question it can answer |
| --- | --- |
| Real-file round trips plus independent binary fixtures | Does the current implementation read existing bytes and emit known bytes at nonzero offsets? Fixed vectors catch a reader and writer that drift together. They can also distinguish a canonical tombstone from a normal empty value. |
| Deterministic model-based comparison | With seeded operations and small rollover thresholds, does the public store agree with a binary-key reference map after each acknowledged operation and at clean reopens? Keeping the operation history explains the first mismatch across a segment boundary. |
| Controlled short I/O and failed barriers | Observing both the file prefix and index after failure distinguishes rejected, partially appended and fully appended but unacknowledged writes. An injected force exception alone cannot prove which bytes survived on real hardware. |
| Process-level crash observation | Termination at known append, force and publication boundaries, followed by a fresh opener, can establish replay behavior on the chosen filesystem. Killing a process leaves the OS cache alive. OS-crash or power-loss claims require observations at those separate boundaries and an explicit storage-stack model. |
| Controlled concurrency and lifecycle observation | Coordinating operations around index publication, rollover and close can expose stale layouts and released handles. Separate instances can exercise directory ownership. Server-level observations can distinguish queue admission, storage completion and client acknowledgment. The contract must specify which of those interleavings it supports. |

Integrated crash observations should check the recovered state against the append-prefix model, including the effect of a complete unacknowledged record when one survives. [Durability and recovery](durability-and-recovery.md) · [Concurrency and lifecycle](concurrency-and-lifecycle.md)
