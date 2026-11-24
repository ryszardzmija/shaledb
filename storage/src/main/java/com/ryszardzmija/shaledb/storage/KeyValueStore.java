package com.ryszardzmija.shaledb.storage;

import com.ryszardzmija.shaledb.storage.config.StorageConfig;
import com.ryszardzmija.shaledb.storage.hash.HashIndexEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Optional;

/** Embedded key-value store with the operation and failure semantics of {@link StorageEngine}. */
public class KeyValueStore implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(KeyValueStore.class);

    private final StorageEngine storageEngine;

    /**
     * Acquires exclusive ownership of the existing database directory and rebuilds its segment indexes.
     *
     * @throws NullPointerException if the storage config is null
     * @throws StorageEngineException if ownership cannot be acquired or segments cannot be discovered,
     *                                created or fully loaded
     */
    public KeyValueStore(StorageConfig storageConfig) {
        Objects.requireNonNull(storageConfig);

        try {
            this.storageEngine = new HashIndexEngine(storageConfig);
        } catch (StorageEngineException e) {
            logger.error("Key-value store startup failed", e);
            throw e;
        }
    }

    /** @see StorageEngine#put(byte[], byte[]) */
    public void put(byte[] key, byte[] value) {
        try {
            storageEngine.put(key, value);
        } catch (StorageEngineException e) {
            logger.error("Failed to store key-value pair", e);
            throw e;
        }
    }

    /** @see StorageEngine#delete(byte[]) */
    public void delete(byte[] key) {
        try {
            storageEngine.delete(key);
        } catch (StorageEngineException e) {
            logger.error("Failed to delete key-value pair", e);
            throw e;
        }
    }

    /** @see StorageEngine#get(byte[]) */
    public Optional<byte[]> get(byte[] key) {
        try {
            return storageEngine.get(key);
        } catch (StorageEngineException e) {
            logger.error("Failed to retrieve key-value pair", e);
            throw e;
        }
    }

    /** @see StorageEngine#close() */
    @Override
    public void close() {
        try {
            storageEngine.close();
        } catch (StorageEngineException e) {
            logger.error("Failed to successfully close the storage engine", e);
            throw e;
        }
    }
}
