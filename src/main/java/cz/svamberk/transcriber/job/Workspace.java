package cz.svamberk.transcriber.job;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;

public final class Workspace implements AutoCloseable {
    private static final Set<Path> OPEN_PATHS = ConcurrentHashMap.newKeySet();
    private final Path path;
    private final FileChannel channel;
    private final FileLock lock;
    private boolean closed;

    private Workspace(Path path, FileChannel channel, FileLock lock) {
        this.path = path;
        this.channel = channel;
        this.lock = lock;
    }

    public static Workspace open(Optional<Path> requested) throws IOException {
        Path path = requested.isPresent() ? requested.get().toAbsolutePath().normalize()
                : Files.createTempDirectory("transcriber-").toAbsolutePath();
        Files.createDirectories(path);
        path = path.toRealPath();
        // Closing any second descriptor can release the process's existing POSIX lock.
        if (!OPEN_PATHS.add(path)) throw new IOException("Workspace is already locked: " + path);
        FileChannel channel = null;
        try {
            channel = FileChannel.open(path.resolve(".lock"), CREATE, WRITE, NOFOLLOW_LINKS);
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException e) {
                throw new IOException("Workspace is already locked: " + path, e);
            }
            if (lock == null) throw new IOException("Workspace is already locked: " + path);
            // Check only after locking; the lock file itself is not foreign content.
            try (var entries = Files.list(path)) {
                var content = entries.filter(p -> !p.getFileName().toString().equals(".lock")).toList();
                if (!content.isEmpty() && !Files.isRegularFile(path.resolve("manifest.json"), NOFOLLOW_LINKS)
                        && !onlyPreflightLogs(content)) {
                    throw new IOException("Unrelated nonempty workspace: " + path);
                }
            }
            return new Workspace(path, channel, lock);
        } catch (IOException | RuntimeException e) {
            if (channel != null) {
                try { channel.close(); } catch (IOException close) { e.addSuppressed(close); }
            }
            OPEN_PATHS.remove(path);
            throw e;
        }
    }

    public Path path() { return path; }

    private static boolean onlyPreflightLogs(List<Path> content) throws IOException {
        if (content.size() != 1) return false;
        Path logs = content.getFirst();
        if (!logs.getFileName().toString().equals("logs") || !Files.isDirectory(logs, NOFOLLOW_LINKS)) return false;
        try (var entries = Files.list(logs)) {
            return entries.allMatch(p -> Files.isRegularFile(p, NOFOLLOW_LINKS)
                    && Set.of("java.log", "probe.log").contains(p.getFileName().toString()));
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) return;
        try {
            if (lock.isValid()) lock.release();
        } finally {
            try {
                channel.close();
            } finally {
                closed = true;
                OPEN_PATHS.remove(path);
            }
        }
    }
}
