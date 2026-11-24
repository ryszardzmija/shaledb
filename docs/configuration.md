# Configuration

Embedded callers construct a `StorageConfig`. The server loads YAML from its first argument, defaulting to `config/application.yaml` relative to the working directory. All four fields in the `storage` section are required. The following are the values shipped in the default file. Omitting a field fails validation. [Shipped configuration](../config/application.yaml) · [StorageConfigMapper](../storage/src/main/java/com/ryszardzmija/shaledb/storage/config/StorageConfigMapper.java)

```yaml
storage:
  maxSegmentSize: 65536
  maxPayloadSize: 16384
  segmentDir: data/segments
  durabilityMode: SYNC_EACH_WRITE
```

| Field under `storage` | Type and constraint | Use |
| --- | --- | --- |
| `maxSegmentSize` | Signed 64-bit integer in bytes, from `13 + maxPayloadSize` through `Long.MAX_VALUE` | Capacity for new appends, including each record's header |
| `maxPayloadSize` | Signed 64-bit integer in bytes, from 1 through `Integer.MAX_VALUE - 13` | Maximum combined key and value length for a mutation or decoded record |
| `segmentDir` | Required path string in YAML, non-null `Path` in Java | Directory containing segment files and `LOCK`. Relative paths resolve against the process working directory. |
| `durabilityMode` | Required enum. The only policy is `SYNC_EACH_WRITE`. The YAML loader also accepts its numeric ordinal `0`. | Force each record before publishing its index entry |

The loader rejects unknown properties, fractional sizes and quoted numeric sizes. The mapper checks for missing fields and the constructor enforces the size relationship before opening the engine. Equality is valid. For example, `maxSegmentSize: 14` and `maxPayloadSize: 1` admit a one-byte key with an empty value or its tombstone. Allocation of an otherwise representable record still depends on available heap. [StorageConfig](../storage/src/main/java/com/ryszardzmija/shaledb/storage/config/StorageConfig.java) · [YAML loader](../server/src/main/java/com/ryszardzmija/shaledb/server/config/ApplicationConfigLoader.java)

Direct construction with invalid settings raises `IllegalArgumentException`. YAML mapping reports missing or invalid storage settings as `StorageConfigException`. If the entire YAML document is `null`, the server raises `NullPointerException` before mapping the storage settings. [Application](../server/src/main/java/com/ryszardzmija/shaledb/server/Application.java)

The server creates the directory and missing parents. Embedded callers supply an existing directory. The owner needs permission to manage the lock and segment files and a filesystem that supports the required locks and file and directory forces. Durability of newly created directory ancestry is an environmental prerequisite. [Concurrency and lifecycle](concurrency-and-lifecycle.md) · [Durability and recovery](durability-and-recovery.md)

## Reopening with different limits

Settings are fixed for an engine instance. A smaller `maxSegmentSize` changes where the next mutation will append. Existing files can exceed that limit and valid records at larger offsets remain readable. A smaller `maxPayloadSize` applies immediately to **all** records during replay, even overwritten values. A complete header declaring a payload above the budget fails open with `INCOMPATIBLE_RECORD_SIZE` before the reader considers tail recovery. Reopening with a compatible budget permits validation to continue. [Segment management](segment-management.md) · [Record format](record-format.md)
