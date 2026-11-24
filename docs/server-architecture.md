# Server architecture

The server owns process lifecycle, YAML loading, RPC transport and serialization of access to one local `KeyValueStore`. The storage library has no dependence on gRPC or Protocol Buffers. The `api` target generates Java classes in `com.ryszardzmija.shaledb.api.v1` from the proto3 service `shaledb.v1.KeyValueStore`. [key_value_store.proto](../api/src/main/proto/shaledb/key_value_store.proto) · [API build](../api/BUILD.bazel) · [Server build](../server/BUILD.bazel)

## Startup

`Application.main` selects and loads YAML, maps the storage configuration and calls `Files.createDirectories(segmentDir)`. It acquires the store first, synchronously completing discovery and replay, then a single-thread executor. It constructs `GrpcKeyValueStoreService(executor, store)`, installs it in a shaded Netty gRPC server on fixed port `50001`, starts listening, registers a JVM shutdown hook and waits in `server.awaitTermination()`. The storage executor is passed to the service. The application does not set it as Netty's or gRPC's transport executor. [Application](../server/src/main/java/com/ryszardzmija/shaledb/server/Application.java) · [Configuration](configuration.md)

Failure loading or mapping configuration escapes before the main `try` block. A directory-creation or bind/start `IOException` inside it is logged and leads to exit status 1. Inside the resource scope, `RuntimeException` is logged as fatal, and interruption restores the interrupt flag and leads to exit 1. Java resource unwinding covers successfully acquired store/executor resources, including when server start fails. The server object itself is managed through the hook and termination wait. [Concurrency and lifecycle](concurrency-and-lifecycle.md)

## Wire contract and request execution

| Unary call | Request fields | Success response |
| --- | --- | --- |
| `Get` | `bytes key = 1` | `bool found = 1` and `bytes value = 2`. Missing or deleted maps to false and empty bytes. A stored empty value maps to true and empty bytes. |
| `Put` | `bytes key = 1`, `bytes value = 2` | Empty `PutResponse` after store return. A missing proto3 bytes value defaults to empty, a legal stored value. |
| `Delete` | `bytes key = 1` | Empty `DeleteResponse` after store return, including a successful delete of an absent key. |

The generated service base dispatches to `get`, `put` or `delete`. Each checks for an empty key before enqueueing and calls `onError` immediately on rejection. On get the handler copies key bytes before submission. Put and delete capture the immutable request and copy bytes on the worker. There is no application check for combined payload length. Each accepted runnable calls the synchronous store. Get copies a returned value into a `ByteString`. The same worker then calls `onNext` followed by `onCompleted`. The modifying methods return no revision, durable offset or operation identifier. [GrpcKeyValueStoreService](../server/src/main/java/com/ryszardzmija/shaledb/server/grpc/GrpcKeyValueStoreService.java) · [Storage semantics](storage-semantics.md)

| Condition in the service | RPC status and observation |
| --- | --- |
| Empty or omitted key | `INVALID_ARGUMENT`, “key must not be empty”. The executor and store are never reached. |
| `execute` throws `RejectedExecutionException` | `UNAVAILABLE`, “key value store unavailable”. This particular submission did not run. Shutdown is a normal source of rejection. |
| Worker catches `StorageEngineException` | `INTERNAL`, “storage engine failed”. The façade logs the storage exception. A write/force error can have an uncertain outcome. |
| Other `RuntimeException` in the worker | `INTERNAL`, “unexpected server error”. This includes raw corruption/offset errors and exceptions inside response construction or observer notification. If delivery already began, the client's observed termination also depends on the transport. |
| Client disconnects or its deadline expires | The transport can stop delivering the response. The service has no cancellation check before executing queued work, interrupt protocol, or rollback of committed work. A caller can lose the reply to a completed mutation. |

## Admission, ordering and shutdown

`Executors.newSingleThreadExecutor()` uses a single worker with an unbounded queue. Calls accepted by that executor run serially in queue order. Concurrent submissions have no protocol-defined ordering by client send time. The engine relies on this external serialization. Substituting a multi-worker executor in the service constructor would remove it. Record forces and rollover publication barriers delay subsequent reads as well as writes. Held requests and observer references consume memory as the queue grows. The application sets no queue capacity, admission deadline, or application-level overload signal for a full queue. [Application](../server/src/main/java/com/ryszardzmija/shaledb/server/Application.java) · [GrpcKeyValueStoreService](../server/src/main/java/com/ryszardzmija/shaledb/server/grpc/GrpcKeyValueStoreService.java)

The hook calls `server.shutdown()` then `storageExecutor.shutdown()`. Both start orderly shutdown. The hook itself returns without waiting for either. Work already in the executor can run, while an RPC already admitted by gRPC but still trying to submit storage work can receive `UNAVAILABLE`. The main path waits for gRPC termination and exits its try-with-resources scope. Java closes the executor first (normally waiting for submitted work), then closes the store and forces its active file. There is no application drain timeout. An interrupted wait takes the exceptional exit path. During JVM shutdown there is no hook-level wait guaranteeing that the main thread completes this final drain and store close before the process halts. [Application](../server/src/main/java/com/ryszardzmija/shaledb/server/Application.java) · [Durability and recovery](durability-and-recovery.md)

**Acknowledgment boundary.** A successful Put/Delete completion follows embedded store return and thus a successful file force and index update. A timeout, cancellation or `INTERNAL` response is generally insufficient to decide whether the operation took effect. Identical retries have the logical idempotency limitations of the embedded API. A later overwrite can intervene between attempts. [Storage semantics](storage-semantics.md)

## Alternatives and open questions

A bounded work queue could limit retained requests and make overload visible before enqueue. The cost is explicit rejection and a client retry policy. Cancellation awareness could avoid executing expired work still in the queue, but cancellation after append cannot promise rollback. A shutdown coordinator could stop admitting new RPCs, allow already admitted handlers to submit and finish, then close the worker and store within a defined waiting policy. A bound on that wait requires a decision about how to report unresolved mutations.

Keeping protocol validation, response delivery and overload status in the server preserves the embedded boundary. Storage-side validation and an explicit outcome for uncertain writes would let the adapter map errors consistently without interpreting internal segment exceptions. The acceptable queue growth, shutdown waiting time and client-visible retry contract remain unspecified.
