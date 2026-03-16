package com.ryszardzmija.storage.serialization.io;

import com.ryszardzmija.storage.serialization.record.RecordPayload;
import com.ryszardzmija.storage.serialization.record.RecordType;

public record ReadResult(RecordPayload recordPayload, RecordType recordType, long nextRecordOffset) {
}
