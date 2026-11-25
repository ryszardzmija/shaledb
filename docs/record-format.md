# Record format

A segment is a sequence of records starting at file offset zero. An empty file contains zero records. There is no segment header, version field, padding or end marker. All multibyte fields are little-endian. [FormatInfo](../storage/src/main/java/com/ryszardzmija/shaledb/storage/serialization/spec/FormatInfo.java) · [RecordTypeCodec](../storage/src/main/java/com/ryszardzmija/shaledb/storage/serialization/spec/RecordTypeCodec.java)

## Binary layout

Offsets are in bytes from the start of one record. The first four fields form the 13-byte header.

| Offset | Width | Field | Encoding |
| --- | --- | --- | --- |
| 0 | 4 | Checksum | Unsigned CRC32C of all subsequent bytes in this record |
| 4 | 1 | Type | `00` for a normal value, `01` for a tombstone (hex) |
| 5 | 4 | K | Signed 32-bit key length in bytes, strictly positive |
| 9 | 4 | V | Signed 32-bit value length in bytes, nonnegative |
| 13 | K | Key | Raw bytes |
| 13 + K | V | Value | Raw bytes. A tombstone requires V = 0. A normal record with V = 0 stores a present empty value. |

The payload size is `K + V`. The encoded size is `13 + K + V`, so the smallest record occupies 14 bytes. The codec sums the lengths in a signed 64-bit integer before checking `K + V <= Integer.MAX_VALUE - 13` and `K + V <= maxPayloadSize`. The next record starts at the current file offset plus the encoded size. The writer checks that this end fits in a signed 64-bit file offset. [RecordWriter](../storage/src/main/java/com/ryszardzmija/shaledb/storage/serialization/io/RecordWriter.java) · [Configuration](configuration.md)

The checksum uses CRC32C (Castagnoli, Java `CRC32C`) over the type byte, the eight encoded length bytes, the key and the value, in that order. In the bitwise form, the accumulator starts at `0xffffffff`, processes bits least-significant first with reflected polynomial `0x82f63b78` and ends with an XOR of `0xffffffff`. The writer stores the result at offset zero.

## Encoding examples

Spaces delimit bytes. The vertical bars separate the six fields. These literal fixtures are tested independently in both encoding and decoding directions. [RecordSerializationTest](../storage/src/test/java/com/ryszardzmija/shaledb/storage/serialization/io/RecordSerializationTest.java)

```text
dd 05 7e 93 | 00 | 03 00 00 00 | 02 00 00 00 | 00 80 ff | c3 28
01 66 49 7c | 01 | 02 00 00 00 | 00 00 00 00 | de ad   |
```

The first stores value `c3 28` under key `00 80 ff`, with CRC32C `0x937e05dd`. The second deletes key `de ad`, with CRC32C `0x7c496601`. If concatenated, their starting offsets are 0 and 18 and the next boundary is 33. An empty normal value would also encode a zero value length, using type `00` and a checksum computed with that type byte.

## Writing and reading

`FormatInfo` defines the 13-byte header and validates both sizes. It sums the key and value lengths in `long` and checks that the payload fits within `Integer.MAX_VALUE - 13` before narrowing to `int`. The combined length must also be at most `maxPayloadSize`. The encoded size adds the 13-byte header to that payload. The smallest valid record is 14 bytes. These checks apply to writes, lookups and replay, so a successfully serialized record passes the reader's structure and size checks under compatible settings. [FormatInfo](../storage/src/main/java/com/ryszardzmija/shaledb/storage/serialization/spec/FormatInfo.java) · [Configuration](configuration.md)

1. `RecordWriter.write` takes a `WriteRequest` containing a type and a `RecordPayload`. The payload constructor requires a nonempty key array and a non-null value array. The writer checks the request and validates the type and lengths before allocating a heap buffer for the whole record. It encodes the nine covered header bytes into a separate buffer and updates the CRC with that buffer and the two arrays. It stores the low 32 bits of the CRC and writes the remaining fields and payload into the record buffer.
2. The mutable segment opens its writer channel with `WRITE`. For each append the writer obtains the current file size, checks the record fits in the remaining `long` offset range and positions the channel at that file size. It continues relative writes from the advancing buffer and channel positions. Once the entire buffer has transferred, the result gives the append start and encoded byte count. This also establishes the append start after reopening under serialized access and stable file ownership. The segment writer returns that result after force, then the mutable segment publishes the index update. [RecordWriter](../storage/src/main/java/com/ryszardzmija/shaledb/storage/serialization/io/RecordWriter.java) · [SegmentWriter](../storage/src/main/java/com/ryszardzmija/shaledb/storage/hash/segment/model/SegmentWriter.java)
3. `RecordReader.read` checks the requested offset and whether the complete header fits in the observed file extent. It fills that header before decoding the type and lengths. It then checks structure, implementation limits and the configured payload limit, in that order. Only after confirming the declared payload fits in the remaining file bytes does it allocate the payload buffer. The extent checks use subtraction and bound subsequent offset sums by the observed file size. Positional reads use the section start plus the current buffer position and leave the channel cursor alone. The reader verifies the CRC over the original covered header bytes and the complete payload before returning fresh key and value arrays, the type and the next offset. [ReadRequest](../storage/src/main/java/com/ryszardzmija/shaledb/storage/serialization/io/ReadRequest.java) · [RecordReader](../storage/src/main/java/com/ryszardzmija/shaledb/storage/serialization/io/RecordReader.java)

Short positive transfers continue from the bytes already transferred. Three consecutive zero-byte calls within a header read, payload read or record write raise `RecordIOException.NoProgress`. Positive progress resets the count. The limit gives blocking file channels a small, deterministic number of attempts to resume.

## Boundaries of validation

| Condition | Implemented result |
| --- | --- |
| Negative offset or offset beyond the observed file size | `SegmentReader` rejects negative offsets and translates `ReadRequest.BeyondEnd` from the record decoder. Both cases yield `RecordIOException.InvalidLocation` for a point read. |
| Scan reaches EOF at a record boundary | Normal completion. The scanner compares the next verified offset to the captured scan extent. |
| Extent too short for the expected header or payload, or EOF during a transfer | `IncompleteRecordException` gives the record start, section, expected byte count and bytes seen in the extent or transferred before EOF. Its origin distinguishes a shortage established by the size check from an unexpected transfer EOF. Only the former can qualify for active-tail recovery. A point read starting exactly at EOF reports an incomplete header with zero bytes available. |
| Bad type, invalid lengths, an unrepresentable encoded size or a tombstone carrying value bytes | `DataCorruptionException` with kind `STRUCTURE`, before payload allocation and checksum comparison. The cause identifies the failed type or structure validation. |
| Structurally valid fields declaring a size outside the configured budget | `RecordIOException.ReadLimitExceeded` preserves the record offset and `FormatInfo.PayloadLimitExceededException` cause. This check precedes validation of payload extent. |
| Channel I/O exception or repeated zero progress | `RecordIOException` retains the original `IOException`. A stalled transfer raises its `NoProgress` subtype. A failed write can leave a prefix in the file. [Durability and recovery](durability-and-recovery.md) |
| CRC mismatch | `DataCorruptionException` with kind `CHECKSUM`. CRC includes the type and both lengths, so a change to the key/value boundary can be detected even at constant total length. CRC provides error detection with possible collisions. It supplies neither record identity nor evidence that the entire file set survived. |

At a verified record boundary, exact EOF ends a sequential scan. When the reader expects a record at an offset, it first checks that the observed file extent contains all 13 header bytes. A point read at EOF therefore reports an incomplete header.

The reader decodes the type and lengths, rejecting unknown types, invalid lengths, tombstones with value bytes and unrepresentable sizes. It checks the configured payload budget next, then checks that the entire declared payload fits within the observed extent before allocating its buffer. It returns a record and the next offset only after verifying the checksum. Reads and writes continue after short positive transfers and fail after three consecutive zero-progress transfers. If a transfer reaches EOF despite fitting in the observed extent, recovery treats it as an I/O failure. [RecordReader](../storage/src/main/java/com/ryszardzmija/shaledb/storage/serialization/io/RecordReader.java)

The bytes carry neither a version nor the writer's configuration. A compatible reader needs the same layout and a sufficient read budget for every record, including obsolete ones. The checksum covers the lengths but can only be verified once the full payload is present. A plausible damaged length can therefore resemble an interrupted append. [Durability and recovery](durability-and-recovery.md)
