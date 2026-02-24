package com.ryszardzmija;

import java.io.IOException;
import java.nio.file.Path;

public class Main {
    static void main(String[] args) {
        try (KeyValueStore keyValueStore = new KeyValueStore(Path.of("logfile"))) {
            keyValueStore.put("hello", "world");
            System.out.println(keyValueStore.get("hello").orElseThrow());
            keyValueStore.put("answer", "42");
            System.out.println(keyValueStore.get("answer").orElseThrow());
            keyValueStore.put("hello", "universe");
            System.out.println(keyValueStore.get("hello").orElseThrow());
        } catch (IOException e) {
            System.err.println("Fatal error: " + e);
            System.exit(1);
        }
    }
}
