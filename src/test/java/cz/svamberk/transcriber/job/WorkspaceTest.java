package cz.svamberk.transcriber.job;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.channels.FileChannel;
import java.util.concurrent.TimeUnit;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceTest {
    @TempDir Path temp;

    @Test
    void createsAndKeepsTemporaryWorkspace() throws Exception {
        Path path;
        try (var workspace = Workspace.open(Optional.empty())) {
            path = workspace.path();
            assertTrue(path.isAbsolute());
            assertTrue(Files.isDirectory(path));
            Files.writeString(path.resolve("keep.txt"), "keep");
        }
        assertEquals("keep", Files.readString(path.resolve("keep.txt")));
        Files.delete(path.resolve("keep.txt"));
        Files.delete(path.resolve(".lock"));
        Files.delete(path);
    }

    @Test
    void rejectsUnrelatedDirectory() throws Exception {
        Files.writeString(temp.resolve("foreign.txt"), "untouched");
        assertThrows(IOException.class, () -> Workspace.open(Optional.of(temp)));
        assertEquals("untouched", Files.readString(temp.resolve("foreign.txt")));
        Files.delete(temp.resolve("foreign.txt"));
        try (var workspace = Workspace.open(Optional.of(temp))) {
            assertEquals(temp.toRealPath(), workspace.path());
        }
    }

    @Test
    void rejectsConcurrentOpen() throws Exception {
        Path path = temp.resolve("práce s mezerou");
        Files.createDirectories(path);
        Path alias = Files.createSymbolicLink(temp.resolve("alias"), path);
        try (var first = Workspace.open(Optional.of(path))) {
            assertEquals(path.toAbsolutePath(), first.path());
            assertExternalAcquisition(path, 1);
            assertThrows(IOException.class, () -> Workspace.open(Optional.of(path)));
            assertThrows(IOException.class, () -> Workspace.open(Optional.of(alias)));
            assertExternalAcquisition(path, 1);
        }
        assertExternalAcquisition(path, 0);
        try (var reopened = Workspace.open(Optional.of(path))) {
            assertEquals(path.toAbsolutePath(), reopened.path());
            assertExternalAcquisition(path, 1);
        }
    }

    private void assertExternalAcquisition(Path path, int expectedExit) throws Exception {
        Path output = temp.resolve("lock-probe.log");
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), LockProbe.class.getName(), path.toString())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Lock probe timed out");
            assertEquals(expectedExit, process.exitValue(), Files.readString(output));
        } finally {
            process.destroyForcibly();
        }
    }

    public static class LockProbe {
        public static void main(String[] args) throws Exception {
            boolean acquired;
            try (var channel = FileChannel.open(Path.of(args[0]).resolve(".lock"),
                    java.nio.file.StandardOpenOption.WRITE);
                 var lock = channel.tryLock()) {
                acquired = lock != null;
            }
            System.exit(acquired ? 0 : 1);
        }
    }
}
