# Server architecture

The server exposes the proto3 service `shaledb.v1.KeyValueStore` through Netty on fixed port `50001`. Startup loads YAML, prepares the directory, then opens one `KeyValueStore`. Only after storage recovery succeeds does the application start accepting RPCs. It gives the service a single-thread storage executor and waits for gRPC termination. [Application](../server/src/main/java/com/ryszardzmija/shaledb/server/Application.java) · [Configuration](configuration.md) · [Service definition](../api/src/main/proto/shaledb/key_value_store.proto)

## Wire contract

| Unary RPC | Request | Successful reply |
| --- | --- | --- |
| `Get` | Binary key | `found` distinguishes a stored empty value (true, empty bytes) from a missing or deleted key (false, empty bytes). Otherwise `value` contains the stored bytes. |
| `Put` | Binary key and value | Empty `PutResponse` after store return. Omitting a proto3 bytes value supplies a legal empty value. |
| `Delete` | Binary key | Empty `DeleteResponse` after store return, including for an absent key. |

## Scheduling and errors

Handlers reject empty keys before submission. A single worker takes accepted operations from an unbounded queue, executes each storage call and delivers its response. A slow file force can delay later reads, and queued requests consume memory. Concurrent clients have no ordering guarantee based on send time. [GrpcKeyValueStoreService](../server/src/main/java/com/ryszardzmija/shaledb/server/grpc/GrpcKeyValueStoreService.java)

| Condition | RPC status |
| --- | --- |
| Empty or omitted key | `INVALID_ARGUMENT` |
| Executor rejects submission, for example during shutdown | `UNAVAILABLE` |
| Worker catches `StorageEngineException` | `INTERNAL` with “storage engine failed” |
| Other runtime exception in the worker, including an oversized payload or a call on a failed store | `INTERNAL` with “unexpected server error” |

The wire response carries no storage reason or mutation outcome. A deadline or disconnect can hide a successful mutation, and the service does not check cancellation before executing queued work. [Storage semantics](storage-semantics.md)

## Shutdown

The JVM hook requests orderly shutdown of the gRPC server and storage executor. An admitted RPC can still fail submission as the executor shuts down. On normal termination the main resource scope closes the executor before the store, allowing submitted operations to finish. The hook itself does not wait for the queue to drain or guarantee the main thread has closed storage before JVM exit. [Application](../server/src/main/java/com/ryszardzmija/shaledb/server/Application.java)
