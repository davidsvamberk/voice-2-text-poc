package cz.svamberk.transcriber.process;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class ProcessRunnerTest {
    @TempDir Path temp;
    private List<String> command(String mode) {
        return List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), Child.class.getName(), mode);
    }
    @Test void drainsBothStreamsAndReturnsExitCode() throws Exception {
        Path log = temp.resolve("stderr.log");
        var result = new ProcessRunner().run(command("streams"), log);
        assertEquals(7, result.exitCode());
        assertEquals("žluťoučký argument", result.stdout());
        assertEquals(200000, Files.size(log));
    }
    @Test void rejectsStdoutAboveEightMiB() {
        assertThrows(IOException.class, () -> new ProcessRunner().run(command("overflow"), temp.resolve("overflow.log")));
    }
    @Test void interruptionStopsProcessAndDescendants() throws Exception {
        var started = new CompletableFuture<Process>();
        var runner = new ProcessRunner() {
            @Override public Process start(List<String> command, Path log) throws IOException {
                var process = super.start(command, log); started.complete(process); return process;
            }
        };
        var failure = new AtomicReference<Throwable>();
        var thread = Thread.ofVirtual().start(() -> {
            try { runner.run(command("wait"), temp.resolve("wait.log")); }
            catch (Throwable e) { failure.set(e); }
        });
        Process process = started.get(10, TimeUnit.SECONDS);
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            List<ProcessHandle> children;
            do { children = process.descendants().toList(); Thread.yield(); }
            while (children.isEmpty() && process.isAlive() && System.nanoTime() < deadline);
            assertFalse(children.isEmpty(), "child must spawn a descendant");
            thread.interrupt(); thread.join(10000);
            assertFalse(thread.isAlive());
            assertInstanceOf(InterruptedException.class, failure.get());
            assertFalse(process.isAlive());
            for (var child : children) assertFalse(child.isAlive());
        } finally { process.descendants().forEach(ProcessHandle::destroyForcibly); process.destroyForcibly(); thread.interrupt(); }
    }
    public static class Child {
        public static void main(String[] args) throws Exception {
            switch (args[0]) {
                case "streams" -> { System.err.write(new byte[200000]); System.out.print("žluťoučký argument"); System.exit(7); }
                case "overflow" -> System.out.write(new byte[8 * 1024 * 1024 + 1]);
                case "wait" -> { new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-cp", System.getProperty("java.class.path"), Child.class.getName(), "sleep").inheritIO().start().waitFor(); }
                case "sleep" -> Thread.sleep(60000);
            }
        }
    }
}
