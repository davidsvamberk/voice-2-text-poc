package cz.svamberk.transcriber.process;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Owns the best-effort termination of every external process in one job. */
public final class ProcessSupervisor implements AutoCloseable {
    private final Map<Process, ProcessRunner> processes = new LinkedHashMap<>();
    private boolean closed;

    synchronized void register(Process process, ProcessRunner runner) throws IOException {
        if (closed) {
            runner.terminate(process);
            throw new IOException("Process supervisor is closed");
        }
        processes.put(process, runner);
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        processes.forEach((process, runner) -> {
            if (process.isAlive()) runner.terminate(process);
        });
    }
}
