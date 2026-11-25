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
| `storage.maxSegmentSize` | Signed `long` bytes, `65536`. Must accommodate `13 + maxPayloadSize` bytes. | `SizeBasedRolloverPolicy` compares the complete incoming record size against remaining physical capacity before append. Startup keeps the existing layout, and reads use the actual file extent. |
| `storage.maxPayloadSize` | Signed `long` bytes, `16384`. Must be in `[1, Integer.MAX_VALUE - 13]`. Measures combined key and value bytes, excluding the header. | `HashIndexEngine` and `RecordWriter` enforce this budget before append. `RecordReader` uses it on every present lookup and during sequential scanning. A decrease below any historical payload can block the entire open, even for an overwritten or deleted key. A compatible increase can restore readability and also raises the allocation budget. |
| `storage.segmentDir` | YAML string, `data/segments`, converted to non-null `Path`. Mapper requires presence and platform-valid path syntax. | `Application` creates it if necessary. Embedded clients supply an existing directory. The engine resolves the physical directory relative to the process working directory for relative paths. It must be able to open or create a regular `LOCK` and take an exclusive file lock before discovery. Opening an owned directory fails immediately. A directory with no segment entries initializes a new store after acquiring ownership. [Concurrency and lifecycle](concurrency-and-lifecycle.md) |
| `storage.durabilityMode` | Non-null `DurabilityMode` enum. The sole declared name is `SYNC_EACH_WRITE`, also the example. Tests reject the lower-case spelling. The current loader also accepts numeric ordinal `0`, confirmed with the checked-in mapper settings. | `SegmentWriter` forces the active file after each record and before publication. There is no second supported policy to switch to. No bytes in a record describe the mode that wrote it. Directory creation force and final close force occur separately. |

The loader rejects unknown properties, fractional sizes and quoted numeric sizes. The mapper checks for missing fields and the constructor enforces the size relationship before opening the engine. Equality is valid. For example, `maxSegmentSize: 14` and `maxPayloadSize: 1` admit a one-byte key with an empty value or its tombstone. Allocation of an otherwise representable record still depends on available heap. [StorageConfig](../storage/src/main/java/com/ryszardzmija/shaledb/storage/config/StorageConfig.java) · [YAML loader](../server/src/main/java/com/ryszardzmija/shaledb/server/config/ApplicationConfigLoader.java)

Direct construction with invalid settings raises `IllegalArgumentException`. YAML mapping reports missing or invalid storage settings as `StorageConfigException`. If the entire YAML document is `null`, the server raises `NullPointerException` before mapping the storage settings. [Application](../server/src/main/java/com/ryszardzmija/shaledb/server/Application.java)

The server creates the directory and missing parents. Embedded callers supply an existing directory. The owner needs permission to manage the lock and segment files and a filesystem that supports the required locks and file and directory forces. Durability of newly created directory ancestry is an environmental prerequisite. [Concurrency and lifecycle](concurrency-and-lifecycle.md) · [Durability and recovery](durability-and-recovery.md)

## Reopening with different limits

Settings are fixed for an engine instance. A smaller `maxSegmentSize` changes where the next mutation will append. Existing files can exceed that limit and valid records at larger offsets remain readable. A smaller `maxPayloadSize` applies immediately to **all** records during replay, even overwritten values. A complete header declaring a payload above the budget fails open with `INCOMPATIBLE_RECORD_SIZE` before the reader considers tail recovery. Reopening with a compatible budget permits validation to continue. [Segment management](segment-management.md) · [Record format](record-format.md)
