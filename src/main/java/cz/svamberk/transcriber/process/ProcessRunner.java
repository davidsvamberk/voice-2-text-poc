package cz.svamberk.transcriber.process;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Shell-free processes; subclass or inject this runner at external-process boundaries. */
public class ProcessRunner {
    private static final int MAX_STDOUT = 8 * 1024 * 1024;
    private final ProcessSupervisor supervisor;

    public ProcessRunner() { this(new ProcessSupervisor()); }

    public ProcessRunner(ProcessSupervisor supervisor) { this.supervisor = supervisor; }

    public ProcessSupervisor supervisor() { return supervisor; }

    /** Starts a process with stderr streamed directly to disk; caller owns its lifecycle and stdout/stdin. */
    public Process start(List<String> command, Path stderrLog) throws IOException {
        Files.createDirectories(stderrLog.toAbsolutePath().getParent());
        if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Process launch interrupted");
        Process process = new ProcessBuilder(List.copyOf(command)).redirectError(stderrLog.toFile()).start();
        supervisor.register(process, this);
        return process;
    }

    public ProcessResult run(List<String> command, Path stderrLog) throws IOException, InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Process launch interrupted");
        Process process = start(command, stderrLog);
        var stdout = new FutureTask<String>(() -> {
            try (var input = process.getInputStream(); var output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (count > MAX_STDOUT - output.size()) throw new IOException("Process stdout exceeds 8 MiB");
                    output.write(buffer, 0, count);
                }
                return output.toString(StandardCharsets.UTF_8);
            } catch (IOException e) {
                terminate(process);
                throw e;
            }
        });
        Thread.ofVirtual().name("process-stdout").start(stdout);
        try {
            process.getOutputStream().close();
            int exitCode = process.waitFor();
            return new ProcessResult(exitCode, stdout.get());
        } catch (InterruptedException e) {
            terminate(process);
            stdout.cancel(true);
            throw e;
        } catch (ExecutionException e) {
            throw new IOException("Cannot read process stdout", e.getCause());
        } finally {
            if (process.isAlive()) terminate(process);
        }
    }

    /**
     * Best-effort cleanup shared with streaming supervisors: request root termination before waiting.
     * Retain discovered handles and rescan around that request. Java descendant snapshots cannot
     * atomically prevent spawning or rediscover children reparented before a scan; no tree-wide
     * termination guarantee is possible here. Prompt root termination limits that race for our
     * controlled FFmpeg/Python commands, at the cost of relying on the OS to reap orphaned children.
     */
    public void terminate(Process process) {
        boolean interrupted = Thread.interrupted();
        var descendants = new LinkedHashSet<>(process.descendants().toList());
        descendants.forEach(ProcessHandle::destroyForcibly);
        // Capture any new children while the root is still available, without waiting for exits.
        descendants.addAll(process.descendants().toList());
        process.destroyForcibly();
        // A forcible termination request need not take effect immediately.
        descendants.addAll(process.descendants().toList());
        descendants.forEach(ProcessHandle::destroyForcibly);
        try {
            for (var child : descendants) {
                try { child.onExit().get(2, TimeUnit.SECONDS); }
                catch (ExecutionException | TimeoutException ignored) { /* Best effort for already exiting descendants. */ }
            }
        } catch (InterruptedException e) {
            interrupted = true;
        } finally {
            process.destroyForcibly();
            try { process.waitFor(2, TimeUnit.SECONDS); }
            catch (InterruptedException e) { interrupted = true; }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
