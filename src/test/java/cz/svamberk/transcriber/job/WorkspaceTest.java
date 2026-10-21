package cz.svamberk.transcriber.job;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
    }

    @Test
    void rejectsConcurrentOpen() throws Exception {
        Path path = temp.resolve("práce s mezerou");
        try (var first = Workspace.open(Optional.of(path))) {
            assertEquals(path.toAbsolutePath(), first.path());
            assertThrows(IOException.class, () -> Workspace.open(Optional.of(path)));
        }
        try (var reopened = Workspace.open(Optional.of(path))) {
            assertEquals(path.toAbsolutePath(), reopened.path());
        }
    }
}
