package com.ryszardzmija.shaledb.storage;

import com.ryszardzmija.shaledb.storage.StorageEngineException.MutationOutcome;
import com.ryszardzmija.shaledb.storage.StorageEngineException.Operation;
import com.ryszardzmija.shaledb.storage.StorageEngineException.Reason;
import com.ryszardzmija.shaledb.storage.hash.index.ByteKey;
import com.ryszardzmija.shaledb.storage.hash.index.KeyDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.FutureTask;

import static com.ryszardzmija.shaledb.storage.KeyValueStoreTestSupport.assertFailure;
import static com.ryszardzmija.shaledb.storage.KeyValueStoreTestSupport.assertValue;
import static com.ryszardzmija.shaledb.storage.KeyValueStoreTestSupport.config;
import static com.ryszardzmija.shaledb.storage.KeyValueStoreTestSupport.encodeRecord;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.withSettings;

class DatabaseOwnershipProcessTest {
    private static final String READY = "READY";
    private static final String CLOSE = "CLOSE";
    private static final String INTERRUPT_PUT = "interrupt-put";
    private static final String INTERRUPT_DELETE = "interrupt-delete";
    private static final byte[] KEY = {1};
    private static final byte[] ORIGINAL = {2};
    private static final byte[] REPLACEMENT = {3};
    private static final int CONTENTION_EXIT = 3;
    private static final int TIMEOUT_SECONDS = 15;

    @TempDir
    Path directory;

    @Test
    void childOwnerExcludesTheParentUntilOrderlyClose() throws Exception {
        Path database = Files.createDirectory(directory.resolve("database"));
        try (Child owner = startChild(database, "hold")) {
            owner.awaitReady();
            assertLockedInParent(database);
            assertThat(database.resolve("LOCK")).isRegularFile();
            owner.closeStore();
        }

        try (var store = new KeyValueStore(config(database, 256))) {
            assertThat(store.get(new byte[] {1})).isEmpty();
        }
    }

    @Test
    void sameJvmContentionDoesNotDropTheParentsOperatingSystemLock() throws Exception {
        Path database = Files.createDirectory(directory.resolve("database"));
        byte[] key = {1};
        byte[] value = {2};
        try (var owner = new KeyValueStore(config(database, 256))) {
            assertLockedInParent(database);
            try (Child contender = startChild(database, "probe")) {
                contender.expectExit(CONTENTION_EXIT);
            }
            owner.put(key, value);
            assertValue(owner, key, value);
        }

        try (Child successor = startChild(database, "probe")) {
            successor.expectExit(0);
        }
    }

    @Test
    void anotherJvmCanAcquireAfterTheOwnerIsForciblyTerminated() throws Exception {
        Path database = Files.createDirectory(directory.resolve("database"));
        try (Child owner = startChild(database, "hold")) {
            owner.awaitReady();
            try (Child contender = startChild(database, "probe")) {
                contender.expectExit(CONTENTION_EXIT);
            }
            owner.terminate();
            assertThat(owner.process.exitValue()).isNotZero();
        }
        assertThat(database.resolve("LOCK")).isRegularFile();
        try (Child successor = startChild(database, "probe")) {
            successor.expectExit(0);
        }
    }

    @Test
    void replaysOverwriteAfterKillingOwnerAtDirectoryPublication() throws Exception {
        Path database = killOwnerAtPublication(INTERRUPT_PUT, encodeRecord(0, KEY, REPLACEMENT));
        try (var store = new KeyValueStore(config(database, 256))) {
            assertValue(store, KEY, REPLACEMENT);
        }
    }

    @Test
    void replaysTombstoneAfterKillingOwnerAtDirectoryPublication() throws Exception {
        Path database = killOwnerAtPublication(INTERRUPT_DELETE, encodeRecord(1, KEY, new byte[0]));
        try (var store = new KeyValueStore(config(database, 256))) {
            assertThat(store.get(KEY)).isEmpty();
        }
    }

    private Path killOwnerAtPublication(String mode, byte[] record) throws Exception {
        Path database = Files.createDirectory(directory.resolve("database"));
        byte[] history = encodeRecord(0, KEY, ORIGINAL);
        Path historical = Files.write(database.resolve("segment_000001.log"), history);
        Path active = Files.createFile(database.resolve("segment_000002.log"));
        try (Child owner = startChild(database, mode)) {
            owner.awaitReady();
            assertThat(owner.process.isAlive()).isTrue();
            assertThat(Files.readAllBytes(active)).containsExactly(record);
            assertLockedInParent(database);
            owner.terminate();
            assertThat(owner.process.exitValue()).isNotZero();
        }
        assertThat(Files.readAllBytes(historical)).containsExactly(history);
        return database;
    }

    private static void assertLockedInParent(Path database) {
        assertFailure(() -> {
            try (var unexpected = new KeyValueStore(config(database, 256))) {
                throw new AssertionError("Contender opened an owned database");
            }
        }, Operation.OPEN, Reason.DATABASE_LOCKED, MutationOutcome.NOT_APPLICABLE);
    }

    private Child startChild(Path database, String mode) throws IOException {
        Path errorLog = Files.createTempFile(directory, "child-", ".stderr");
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        if (!Files.isRegularFile(java)) {
            java = java.resolveSibling("java.exe");
        }
        Process process = new ProcessBuilder(java.toString(), "-cp", System.getProperty("java.class.path"),
                ChildMain.class.getName(), mode, database.toString())
                .redirectError(errorLog.toFile())
                .start();
        return new Child(process, errorLog);
    }

    private record Child(Process process, Path errorLog) implements AutoCloseable {
        void awaitReady() throws Exception {
            var readiness = new FutureTask<>(() -> process.inputReader().readLine());
            Thread.ofVirtual().start(readiness);
            try {
                assertThat(readiness.get(TIMEOUT_SECONDS, SECONDS)).as("child readiness, logs at %s", errorLog)
                        .isEqualTo(READY);
            } catch (Exception | AssertionError e) {
                throw new AssertionError("Child did not report readiness\n" + Files.readString(errorLog), e);
            }
        }

        void closeStore() throws Exception {
            var input = process.outputWriter();
            input.write(CLOSE);
            input.newLine();
            input.flush();
            expectExit(0);
        }

        void expectExit(int status) throws Exception {
            if (!process.waitFor(TIMEOUT_SECONDS, SECONDS) || process.exitValue() != status) {
                String actual = process.isAlive() ? "timed out" : Integer.toString(process.exitValue());
                throw new AssertionError("Expected child exit " + status + ", got " + actual
                        + "\n" + Files.readString(errorLog));
            }
        }

        void terminate() throws InterruptedException {
            process.destroyForcibly();
            assertThat(process.waitFor(TIMEOUT_SECONDS, SECONDS)).as("terminated child JVM exited").isTrue();
        }

        @Override
        public void close() throws Exception {
            if (process.isAlive()) {
                terminate();
            }
            process.getInputStream().close();
            process.getOutputStream().close();
        }
    }

    public static final class ChildMain {
        public static void main(String[] args) throws IOException {
            var protocol = System.out;
            System.setOut(System.err);
            Path database = Path.of(args[1]);
            if (args[0].equals(INTERRUPT_PUT) || args[0].equals(INTERRUPT_DELETE)) {
                pauseDuringPublication(database, protocol, args[0].equals(INTERRUPT_DELETE));
                return;
            }
            boolean hold = args[0].equals("hold");
            try (var store = new KeyValueStore(config(database, 256))) {
                if (hold) {
                    protocol.println(READY);
                    protocol.flush();
                    var input = new BufferedReader(new InputStreamReader(System.in));
                    if (!CLOSE.equals(input.readLine())) {
                        throw new IllegalStateException("Expected CLOSE command from parent");
                    }
                }
            } catch (StorageEngineException failure) {
                if (!hold && failure.operation() == Operation.OPEN && failure.reason() == Reason.DATABASE_LOCKED
                        && failure.mutationOutcome() == MutationOutcome.NOT_APPLICABLE) {
                    System.exit(CONTENTION_EXIT);
                }
                throw failure;
            }
        }

        private static void pauseDuringPublication(Path database, PrintStream protocol, boolean delete) {
            var actualDirectory = new KeyDirectory();
            try (var directories = mockConstruction(KeyDirectory.class, withSettings().defaultAnswer(delegatesTo(actualDirectory)));
                 var store = new KeyValueStore(config(database, 256))) {
                assertValue(store, KEY, ORIGINAL);
                var directory = directories.constructed().getFirst();
                var key = new ByteKey(KEY);
                if (delete) {
                    doAnswer(call -> awaitTermination(protocol)).when(directory).remove(key);
                    store.delete(KEY);
                } else {
                    doAnswer(call -> awaitTermination(protocol)).when(directory).markPresent(eq(key), any());
                    store.put(KEY, REPLACEMENT);
                }
                throw new AssertionError("Mutation returned before the parent killed the child");
            }
        }

        private static Void awaitTermination(PrintStream protocol) throws IOException {
            // Signal only after the append returns and before updating the directory.
            protocol.println(READY);
            protocol.flush();
            new BufferedReader(new InputStreamReader(System.in)).readLine();
            throw new AssertionError("Expected the parent to kill the child while publication was paused");
        }
    }
}
