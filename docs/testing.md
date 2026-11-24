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

Configuration tests exercise YAML loading and field validation. The RPC integration test starts a Netty service against a temporary store and calls it through a blocking client on a loopback socket. It exercises put, get, delete and empty-key rejection. [ApplicationConfigLoaderTest](../server/src/test/java/com/ryszardzmija/shaledb/server/config/ApplicationConfigLoaderTest.java) · [GrpcKeyValueStoreServiceIntegrationTest](../server/src/test/java/com/ryszardzmija/shaledb/server/grpc/GrpcKeyValueStoreServiceIntegrationTest.java)
