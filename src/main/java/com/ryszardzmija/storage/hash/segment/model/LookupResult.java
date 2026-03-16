package com.ryszardzmija.storage.hash.segment.model;

public sealed interface LookupResult {
    record Deleted() implements LookupResult {}
    record Present(byte[] value) implements LookupResult {}
    record NotPresent() implements LookupResult {}
}
