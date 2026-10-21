package cz.svamberk.transcriber.worker;

import cz.svamberk.transcriber.config.RuntimeSettings;
import cz.svamberk.transcriber.io.AtomicJsonFiles;
import cz.svamberk.transcriber.job.*;
import cz.svamberk.transcriber.process.ProcessRunner;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Streaming worker boundary. Caller owns the workspace lock and initialized checkpoint store. */
public final class WorkerClient {
    private static final int MAX_LINE = 1024 * 1024;
    private final RuntimeSettings settings;
    private final ProcessRunner runner;
    private final CheckpointStore store;
    private final ObjectMapper mapper;
    private final AtomicJsonFiles files;

    public WorkerClient(RuntimeSettings settings, ProcessRunner runner, CheckpointStore store, ObjectMapper mapper) {
        this.settings = settings;
        this.runner = runner;
        this.store = store;
        this.mapper = mapper.rebuild().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
        this.files = new AtomicJsonFiles(mapper);
    }

    public Map<String,String> probe(RuntimeSettings runtime) throws IOException, InterruptedException {
        Path log = Files.createTempFile("transcriber-probe-", ".log");
        var result = runner.run(command(runtime, "--probe", "--model-dir", absolute(runtime.modelDir())), log);
        require(result.exitCode() == 0, "Worker probe failed; stderr: " + log);
        var node = object(result.stdout());
        fields(node, Set.of("schemaVersion", "engineVersions"));
        return versions(node.get("engineVersions"));
    }

    public void run(WorkerRequest request, Consumer<WorkerEvent> events) throws IOException, InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Worker launch interrupted");
        require(request != null && request.schemaVersion() == 1 && request.workDir() != null
                && request.workDir().isAbsolute() && request.modelDir() != null && request.modelDir().isAbsolute()
                && request.config() != null && request.modelDir().equals(request.config().modelDir()), "Invalid worker request");
        require(store != null && request.plan() != null && store.loadPlan().filter(request.plan()::equals).isPresent(),
                "Worker request requires the saved plan");
        require(request.pendingBlockIds() != null, "Missing pending IDs");
        var pending = new HashSet<Integer>();
        var blocks = new HashMap<Integer,Block>();
        for (var block : request.plan().blocks()) blocks.put(block.id(), block);
        for (var id : request.pendingBlockIds()) {
            require(id != null && blocks.containsKey(id) && pending.add(id), "Invalid or duplicate pending ID");
        }
        var manifest = new JobManifest(1, request.inputSha256(), request.config(), request.configSha256());
        Path requestFile = request.workDir().resolve("request.json");
        // Jackson's default Path serializer emits file: URIs; Python needs native absolute paths.
        ObjectNode wire = mapper.valueToTree(request);
        wire.put("workDir", absolute(request.workDir()));
        wire.put("modelDir", absolute(request.modelDir()));
        ((ObjectNode) wire.get("config")).put("modelDir", absolute(request.config().modelDir()));
        files.write(requestFile, wire);
        Process process = runner.start(command(settings, "--request", absolute(requestFile)),
                request.workDir().resolve("logs/worker.log"));
        var stdout = new FutureTask<Void>(() -> {
            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                boolean ready = false;
                String line;
                while ((line = line(reader)) != null) {
                    var event = event(object(line));
                    if (event.type().equals("error")) throw new IOException("Worker " + event.code() + ": " + event.message());
                    if (event.type().equals("ready")) {
                        require(!ready && event.engineVersions().equals(request.config().engineVersions()), "Unexpected ready/engine versions");
                        ready = true;
                    } else {
                        require(ready && pending.contains(event.blockId()), "Unexpected or duplicate block ID");
                        var block = blocks.get(event.blockId());
                        if (event.type().equals("block_done")) {
                            require(store.loadValidBlock(block, manifest).isPresent(), "Worker completion without valid checkpoint");
                            pending.remove(event.blockId());
                        } else {
                            require(event.secondsProcessed() >= 0 && event.secondsProcessed() <= block.end()-block.start(), "Invalid progress range");
                        }
                    }
                    events.accept(event);
                }
                require(ready && pending.isEmpty(), "Early EOF from worker (ready=" + ready + ", pending=" + pending + ")");
                return null;
            }
        });
        Thread.ofVirtual().name("worker-stdout").start(stdout);
        try {
            process.getOutputStream().close();
            // Wait on the reader first: invalid JSON must fail even if the worker stays alive.
            stdout.get();
            require(process.waitFor() == 0, "Worker exited unsuccessfully");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof IOException io) throw io;
            throw new IOException("Cannot consume worker events", e.getCause());
        } finally {
            // Applies to cancellation, protocol failure and a root that outlives its stdout.
            if (process.isAlive()) stop(process);
            stdout.cancel(true);
        }
    }

    private void stop(Process process) {
        boolean interrupted = Thread.interrupted();
        var descendants = new LinkedHashSet<>(process.descendants().toList());
        descendants.forEach(ProcessHandle::destroy);
        process.destroy();
        try { process.waitFor(5, TimeUnit.SECONDS); }
        catch (InterruptedException e) { interrupted = true; }
        finally {
            // Retain handles before reparenting; ProcessRunner supplies best-effort rescans/root cleanup.
            descendants.forEach(child -> { if (child.isAlive()) child.destroyForcibly(); });
            runner.terminate(process);
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private List<String> command(RuntimeSettings runtime, String... args) {
        // runpy supplies a module search path without a shell or changing the JVM working directory.
        var command = new ArrayList<>(List.of(absolute(runtime.python()), "-u", "-c",
                "import runpy,sys; sys.path.insert(0,sys.argv.pop(1)); runpy.run_module('transcriber_worker',run_name='__main__')",
                absolute(runtime.workerDir())));
        command.addAll(List.of(args));
        return command;
    }

    private static String absolute(Path path) { return path.toAbsolutePath().normalize().toString(); }

    private JsonNode object(String json) throws IOException {
        try {
            var node = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(json);
            require(node != null && node.isObject(), "Worker JSON must be an object");
            require(integer(node.get("schemaVersion")) == 1, "Unsupported worker schemaVersion");
            return node;
        } catch (RuntimeException e) { throw new IOException("Invalid worker JSON", e); }
    }

    private WorkerEvent event(JsonNode n) throws IOException {
        String type = string(n.get("type"));
        return switch (type) {
            case "ready" -> {
                fields(n, Set.of("schemaVersion","type","engineVersions"));
                yield new WorkerEvent(1,type,versions(n.get("engineVersions")),null,null,null,null);
            }
            case "block_done", "progress" -> {
                fields(n, type.equals("block_done") ? Set.of("schemaVersion","type","blockId")
                        : Set.of("schemaVersion","type","blockId","secondsProcessed"));
                Double seconds = null;
                if (type.equals("progress")) {
                    var value = n.get("secondsProcessed");
                    require(value != null && value.isNumber() && Double.isFinite(value.asDouble()), "Invalid progress number");
                    seconds = value.asDouble();
                }
                yield new WorkerEvent(1,type,null,integer(n.get("blockId")),seconds,null,null);
            }
            case "error" -> {
                fields(n, Set.of("schemaVersion","type","code","message"));
                yield new WorkerEvent(1,type,null,null,null,string(n.get("code")),string(n.get("message")));
            }
            default -> throw new IOException("Unknown worker event: " + type);
        };
    }

    private static int integer(JsonNode value) throws IOException {
        require(value != null && value.isIntegralNumber() && value.canConvertToInt(), "Expected integer");
        return value.asInt();
    }

    private static String string(JsonNode value) throws IOException {
        require(value != null && value.isString(), "Expected string");
        return value.asString();
    }

    private static Map<String,String> versions(JsonNode value) throws IOException {
        require(value != null && value.isObject() && !value.isEmpty(), "Missing engine versions");
        var result = new TreeMap<String,String>();
        for (var entry : value.properties()) {
            var version = string(entry.getValue());
            require(!version.isBlank(), "Empty engine version");
            result.put(entry.getKey(), version);
        }
        return Map.copyOf(result);
    }

    private static void fields(JsonNode node, Set<String> expected) throws IOException {
        var actual = new HashSet<String>();
        node.propertyNames().forEach(actual::add);
        require(actual.equals(expected), "Unexpected/missing worker fields");
    }

    private static String line(Reader reader) throws IOException {
        var value = new StringBuilder();
        int c;
        while ((c = reader.read()) != -1) {
            if (c == '\n') return value.toString();
            require(value.length() < MAX_LINE, "Worker event exceeds 1 MiB");
            value.append((char)c);
        }
        require(value.isEmpty(), "Truncated JSONL event");
        return null;
    }

    private static void require(boolean valid, String message) throws IOException {
        if (!valid) throw new IOException(message);
    }
}
