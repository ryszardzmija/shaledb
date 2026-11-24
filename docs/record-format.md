# Record format

A segment is a concatenation of records starting at file offset zero. Each record is self-delimiting once its 13-byte header has been validated. A zero-byte file contains zero records. There is no segment header, magic number, record count, version, terminator, padding or alignment. All offsets below are in bytes relative to the first byte of one record. [FormatInfo](../storage/src/main/java/com/ryszardzmija/shaledb/storage/serialization/spec/FormatInfo.java) · [RecordTypeCodec](../storage/src/main/java/com/ryszardzmija/shaledb/storage/serialization/spec/RecordTypeCodec.java) · [IndexBuilder](../storage/src/main/java/com/ryszardzmija/shaledb/storage/hash/index/IndexBuilder.java)

```text
offset   0            4    5             9            13        13+K       13+K+V
         | CRC32C     | T  | K           | V           | key     | value     |
width          4        1         4             4            K          V
                      |<-------------- bytes covered by CRC32C ------------>|
```

| Field | Interpretation |
| --- | --- |
| CRC32C | Unsigned 32-bit Castagnoli CRC in little-endian order. Compute standard CRC32C (Java `CRC32C`) over the raw type byte, both **encoded little-endian length fields**, the key, then the value. For a bitwise implementation, initialize to `0xffffffff`, process bits least-significant first using the reflected polynomial `0x82f63b78`, then XOR with `0xffffffff`. |
| `T` | `0x00` normal (including a present empty value), `0x01` tombstone. The codec rejects every other type code. |
| `K`, `V` | Two little-endian signed 32-bit integers giving byte counts. The reader requires `K > 0`, `V >= 0` and `(long) K + V <= maxPayloadSize`. |
| Key, value | Raw uninterpreted bytes. The engine emits `V = 0` for a tombstone. There is no character encoding, separator or terminating null. |
| Next boundary | For a record starting at file offset `p`, the next start is `p + 13 + K + V`. Ending exactly at the file size completes a scan. Even one extra trailing byte makes the scanner attempt another header. |

## Encoding examples

Spaces delimit bytes. The vertical bars separate the six fields. These vectors come from tests with literal expected bytes that also decode each vector independently of the writer. [RecordSerializationTest](../storage/src/test/java/com/ryszardzmija/shaledb/storage/serialization/io/RecordSerializationTest.java)

```text
dd 05 7e 93 | 00 | 03 00 00 00 | 02 00 00 00 | 00 80 ff | c3 28
01 66 49 7c | 01 | 02 00 00 00 | 00 00 00 00 | de ad   |
```

The first stores value `c3 28` under key `00 80 ff`, with CRC32C `0x937e05dd`. The second deletes key `de ad`, with CRC32C `0x7c496601`. If concatenated, their starting offsets are 0 and 18 and the next boundary is 33. An empty normal value would also encode a zero value length, using type `00` and a checksum computed with that type byte.

## Writing and reading

1. `RecordWriter.write` takes a `WriteRequest` containing a type and a `RecordPayload`. It allocates a heap buffer for the whole record, encodes the nine covered header bytes into a separate buffer, and updates the CRC with that buffer and the two arrays. It stores the low 32 bits of the CRC and writes the remaining fields and payload into the record buffer.
2. It captures `writeChannel.position()` just before writing. The mutable segment opened this channel with `APPEND`. The writer repeatedly calls relative `write(buffer)` until the buffer is exhausted, then returns the captured start. A positive short write advances the buffer. There is no record-level atomic append, rollback or progress bound for repeated zero-byte writes. `flushToStorage` is a separate call controlled by the segment writer. [RecordWriter](../storage/src/main/java/com/ryszardzmija/shaledb/storage/serialization/io/RecordWriter.java) · [SegmentWriter](../storage/src/main/java/com/ryszardzmija/shaledb/storage/hash/segment/model/SegmentWriter.java)
3. `RecordReader.read` accepts a nonnegative `long` offset. It fills a header using positional reads at `base + buffer.position()`, decodes the type and signed lengths, and validates them before allocating the payload buffer. It then fills that buffer starting at `p + 13`. It computes the CRC over the original covered header bytes and payload and compares it with the unsigned stored CRC. Only then does it split out fresh key and value arrays and return the type and next offset. Positional reads leave the channel cursor alone. [ReadRequest](../storage/src/main/java/com/ryszardzmija/shaledb/storage/serialization/io/ReadRequest.java) · [RecordReader](../storage/src/main/java/com/ryszardzmija/shaledb/storage/serialization/io/RecordReader.java)

## Boundaries of validation

| Condition | Implemented result |
| --- | --- |
| Bad type or invalid lengths | `DataCorruptionException` before checksum comparison. An oversized but otherwise correctly encoded record receives the same invalid-length diagnosis as malformed lengths. |
| Short positive read | Continue until the requested buffer is full. EOF during either buffer is corruption. For an EOF in the payload, the exception from the fill loop reports the payload base (`p + 13`). A direct read at EOF also fails. An index scan checks size before asking for another record. |
| I/O error or zero progress | An `IOException` becomes `RecordIOException`. Repeated zero-byte reads can loop. A partial write followed by an exception can leave a prefix in the file. [Durability and recovery](durability-and-recovery.md) |
| CRC mismatch | `DataCorruptionException`. CRC includes the type and both lengths, so changes to the key/value boundary are detectable even at constant total length. CRC provides error detection with possible collisions. It supplies neither record identity nor evidence that the entire file set survived. |
| Tombstone carrying value bytes | The normal delete path emits an empty value. Neither the low-level `WriteRequest` nor the reader enforces this type-specific convention. Replay marks the key deleted and ignores the decoded value. The accepted representation is wider than the engine's canonical output. |

**Writer/reader asymmetry.** `RecordPayload` validates key and value presence, while `RecordWriter` has no configured payload limit. The reader applies that limit on every data read and every rebuild. A successful write can therefore create data the store itself cannot replay. [Storage semantics](storage-semantics.md)

**Representability and memory.** The reader widens the length sum for its comparison, then performs `K + V` in `int` for `ByteBuffer.allocate`. A sufficiently high configured limit can admit a sum greater than `Integer.MAX_VALUE` and cause allocation to fail outside the corruption path. The writer computes `13 + K + V` in `int`. An unrepresentable size can fail during allocation or buffer assembly. Even a representable declared payload can exceed available heap. The reader allocates before proving that many bytes remain in the file, and subsequently allocates separate output arrays. The existing overflow fixture uses a *small* cap and establishes early rejection in that case. [RecordReaderTest](../storage/src/test/java/com/ryszardzmija/shaledb/storage/serialization/io/RecordReaderTest.java) · [StorageConfig](../storage/src/main/java/com/ryszardzmija/shaledb/storage/config/StorageConfig.java)

## Compatibility and alternatives

A reader interpreting existing files needs the layout above and a payload budget compatible with the entire history. Configuration values are absent from the bytes. Changing the type codes, byte order or checksum coverage would change interpretation with no in-band discriminator. Preserving the byte vectors and a shared, overflow-checked sizing rule could align the reader and writer without changing existing files. A file-extent check before allocation can give a clearer incomplete-record diagnosis under exclusive ownership. Whether noncanonical tombstones must remain readable is a compatibility decision. [Configuration](configuration.md) · [Testing](testing.md)
