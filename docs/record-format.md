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
| `K`, `V` | Two little-endian signed 32-bit integers giving byte counts. Both readers and writers require `K > 0` and `V >= 0`. A tombstone requires `V = 0`. |
| Key, value | Raw uninterpreted bytes. A normal record can store a present value of zero bytes. There is no character encoding, separator or terminating null. |
| Next boundary | For a record starting at file offset `p`, the next start is `p + 13 + K + V`. Ending exactly at the file size completes a scan. Even one extra trailing byte makes the scanner attempt another header. |

## Encoding examples

Spaces delimit bytes. The vertical bars separate the six fields. These vectors come from tests with literal expected bytes that also decode each vector independently of the writer. [RecordSerializationTest](../storage/src/test/java/com/ryszardzmija/shaledb/storage/serialization/io/RecordSerializationTest.java)

```text
dd 05 7e 93 | 00 | 03 00 00 00 | 02 00 00 00 | 00 80 ff | c3 28
01 66 49 7c | 01 | 02 00 00 00 | 00 00 00 00 | de ad   |
```

The first stores value `c3 28` under key `00 80 ff`, with CRC32C `0x937e05dd`. The second deletes key `de ad`, with CRC32C `0x7c496601`. If concatenated, their starting offsets are 0 and 18 and the next boundary is 33. An empty normal value would also encode a zero value length, using type `00` and a checksum computed with that type byte.

## Writing and reading

`FormatInfo` defines the 13-byte header and validates both sizes. It sums the key and value lengths in `long` and checks that the payload fits within `Integer.MAX_VALUE - 13` before narrowing to `int`. The combined length must also be at most `maxPayloadSize`. The encoded size adds the 13-byte header to that payload. The smallest valid record is 14 bytes. These checks apply to writes, lookups and replay, so a successfully serialized record passes the reader's structure and size checks under compatible settings. [FormatInfo](../storage/src/main/java/com/ryszardzmija/shaledb/storage/serialization/spec/FormatInfo.java) · [Configuration](configuration.md)

1. `RecordWriter.write` takes a `WriteRequest` containing a type and a `RecordPayload`. The payload constructor requires a nonempty key array and a non-null value array. The writer checks the request and validates the type and lengths before allocating a heap buffer for the whole record. It encodes the nine covered header bytes into a separate buffer and updates the CRC with that buffer and the two arrays. It stores the low 32 bits of the CRC and writes the remaining fields and payload into the record buffer.
2. The mutable segment opens its writer channel with `WRITE`. For each append the writer obtains the current file size, checks the record fits in the remaining `long` offset range and positions the channel at that file size. It continues relative writes from the advancing buffer and channel positions. Once the entire buffer has transferred, the result gives the append start and encoded byte count. This also establishes the append start after reopening under serialized access and stable file ownership. The segment writer forces the file before publishing the index update. [RecordWriter](../storage/src/main/java/com/ryszardzmija/shaledb/storage/serialization/io/RecordWriter.java) · [SegmentWriter](../storage/src/main/java/com/ryszardzmija/shaledb/storage/hash/segment/model/SegmentWriter.java)
3. `RecordReader.read` checks the requested offset and whether the complete header fits in the observed file extent. It fills that header before decoding the type and lengths. It then checks structure, implementation limits and the configured payload limit, in that order. Only after confirming the declared payload fits in the remaining file bytes does it allocate the payload buffer. The extent checks use subtraction and bound subsequent offset sums by the observed file size. Positional reads use the section start plus the current buffer position and leave the channel cursor alone. The reader verifies the CRC over the original covered header bytes and the complete payload before returning fresh key and value arrays, the type and the next offset. [ReadRequest](../storage/src/main/java/com/ryszardzmija/shaledb/storage/serialization/io/ReadRequest.java) · [RecordReader](../storage/src/main/java/com/ryszardzmija/shaledb/storage/serialization/io/RecordReader.java)

Short positive transfers continue from the bytes already transferred. Three consecutive zero-byte calls within a header read, payload read or record write raise `RecordIOException.NoProgress`. Positive progress resets the count. The limit gives blocking file channels a small, deterministic number of attempts to resume.

## Boundaries of validation

| Condition | Implemented result |
| --- | --- |
| Negative offset or offset beyond the observed file size | A negative offset is rejected at request construction with `IllegalArgumentException`. An offset beyond EOF throws its `ReadRequest.BeyondEnd` subtype, which lookup and replay translate for an expected record. |
| Scan reaches EOF at a record boundary | Normal completion. The scanner checks file size before requesting another record. |
| Extent too short for the expected header or payload, or EOF during a transfer | `IncompleteRecordException` gives the record start, section, expected byte count and bytes seen in the extent or transferred before EOF. A point read starting exactly at EOF reports an incomplete header with zero bytes available. |
| Bad type, invalid lengths or a tombstone carrying value bytes | `DataCorruptionException` with kind `STRUCTURE`, before payload allocation and checksum comparison. The cause identifies the failed type or structure validation. |
| Structurally valid fields declaring a size outside the implementation or configured budget | `RecordIOException.ReadLimitExceeded` preserves the record offset and `FormatInfo.PayloadLimitExceededException` cause. This check precedes validation of payload extent. |
| Channel I/O exception or repeated zero progress | `RecordIOException` retains the original `IOException`. A stalled transfer raises its `NoProgress` subtype. A failed write can leave a prefix in the file. [Durability and recovery](durability-and-recovery.md) |
| CRC mismatch | `DataCorruptionException` with kind `CHECKSUM`. CRC includes the type and both lengths, so a change to the key/value boundary can be detected even at constant total length. CRC provides error detection with possible collisions. It supplies neither record identity nor evidence that the entire file set survived. |

The buffer capacity uses a signed integer. A payload that fits the size and extent checks can still exceed available heap, and the reader also allocates separate output arrays. An incomplete payload describes the observed byte shortage. Damaged length fields can produce the same observation as an interrupted append. Reads leave file bytes intact. Tail repair requires a separate recovery policy. [RecordReaderTest](../storage/src/test/java/com/ryszardzmija/shaledb/storage/serialization/io/RecordReaderTest.java)

## Compatibility and alternatives

A reader interpreting existing files needs the layout above and a payload budget compatible with the entire history. Configuration values are absent from the bytes. Existing valid records retain their encoding, as verified by the independently decoded byte vectors. A historical tombstone containing value bytes fails validation. [Configuration](configuration.md) · [Testing](testing.md)
