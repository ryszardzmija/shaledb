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

The first stores value `c3 28` under key `00 80 ff`. The second deletes key `de ad`. Concatenated, they begin at offsets 0 and 18 and end at offset 33.

## Decoding and compatibility

At a verified record boundary, exact EOF ends a sequential scan. When the reader expects a record at an offset, it first checks that the observed file extent contains all 13 header bytes. A point read at EOF therefore reports an incomplete header.

The reader decodes the type and lengths, rejecting unknown types, invalid lengths, tombstones with value bytes and unrepresentable sizes. It checks the configured payload budget next, then checks that the entire declared payload fits within the observed extent before allocating its buffer. It returns a record and the next offset only after verifying the checksum. Reads and writes continue after short positive transfers and fail after three consecutive zero-progress transfers. If a transfer reaches EOF despite fitting in the observed extent, recovery treats it as an I/O failure. [RecordReader](../storage/src/main/java/com/ryszardzmija/shaledb/storage/serialization/io/RecordReader.java)

The bytes carry neither a version nor the writer's configuration. A compatible reader needs the same layout and a sufficient read budget for every record, including obsolete ones. The checksum covers the lengths but can only be verified once the full payload is present. A plausible damaged length can therefore resemble an interrupted append. [Durability and recovery](durability-and-recovery.md)
