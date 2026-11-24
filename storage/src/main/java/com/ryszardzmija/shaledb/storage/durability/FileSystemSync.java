package com.ryszardzmija.shaledb.storage.durability;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public class FileSystemSync {
    public void forceFile(FileChannel channel) {
        try {
            channel.force(true);
        } catch (IOException e) {
            throw new DurabilityException("Failed to force file contents to durable storage", e,
                    DurabilityException.Target.FILE);
        }
    }

    public void forceDirectory(Path directory) {
        try (FileChannel dirChannel = openDirectoryForSync(directory)) {
            try {
                dirChannel.force(true);
            } catch (IOException e) {
                throw new DurabilityException("Failed to force directory to durable storage: " + directory, e,
                        DurabilityException.Target.DIRECTORY);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to close directory after force: " + directory, e);
        }
    }

    private static FileChannel openDirectoryForSync(Path directory) {
        try {
            return FileChannel.open(directory, StandardOpenOption.READ);
        } catch (IOException e) {
            throw new DurabilityException("Failed to open directory for force: " + directory, e,
                    DurabilityException.Target.DIRECTORY);
        }
    }
}
