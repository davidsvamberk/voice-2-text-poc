package cz.svamberk.transcriber.job;

import cz.svamberk.transcriber.audio.*;
import cz.svamberk.transcriber.cli.CliArguments;
import cz.svamberk.transcriber.config.RuntimeSettings;
import cz.svamberk.transcriber.io.AtomicJsonFiles;
import cz.svamberk.transcriber.output.*;
import cz.svamberk.transcriber.process.ProcessRunner;
import cz.svamberk.transcriber.worker.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** One CLI job; the checkpoint files, rather than worker events, determine completion. */
public final class TranscriptionJob {
    private static final Logger LOG = LoggerFactory.getLogger(TranscriptionJob.class);
    private final ObjectMapper mapper;
    private final ProcessRunner runner;
    private Path workDirectory;

    public TranscriptionJob(ObjectMapper mapper) { this(mapper, new ProcessRunner()); }
    public TranscriptionJob(ObjectMapper mapper, ProcessRunner runner) {
        this.mapper = mapper;
        this.runner = runner;
    }

    public Optional<Path> workDirectory() { return Optional.ofNullable(workDirectory); }

    public Path run(CliArguments args, RuntimeSettings settings) throws IOException, InterruptedException {
        Path input = args.input().toRealPath();
        if (!Files.isRegularFile(input) || !Files.isReadable(input)) throw new IOException("Input is not a readable file: " + input);
        Path model = settings.modelDir().toAbsolutePath().normalize();
        if (args.workDir().isPresent()) checkLocation(input, model, canonical(args.workDir().get()));
        try (var workspace = Workspace.open(args.workDir())) {
            workDirectory = workspace.path();
            System.out.println("Workspace: " + workDirectory);
            checkLocation(input, model, workDirectory);
            Path invalidation = workDirectory.resolve("invalidated.json");
            if (Files.exists(invalidation, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Workspace invalidated after input/model change; use a new workspace");
            }
            protectFiles(workDirectory, input);
            try (var log = new WorkspaceLog(workDirectory); var supervisor = runner.supervisor()) {
                Thread owner = Thread.currentThread();
                Thread hook = Thread.ofPlatform().name("transcriber-shutdown").unstarted(() -> {
                    owner.interrupt();
                    supervisor.close();
                    try { owner.join(10000); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    System.err.println("Interrupted. Workspace: " + workDirectory);
                    System.err.println("Resume with INPUT --work-dir " + workDirectory);
                });
                Runtime.getRuntime().addShutdownHook(hook);
                try {
                    LOG.info("Opened workspace for {}", input);
                    var store = new CheckpointStore(workspace, mapper);
                    var worker = new WorkerClient(settings, runner, store, mapper);
                    var stored = store.loadManifest();
                    Map<String,String> versions = stored.isPresent() ? stored.get().config().engineVersions()
                            : worker.probe(settings, workDirectory.resolve("logs/probe.log"));
                    var manifest = JobIdentity.identify(input, config(model, versions));
                    store.initializeOrValidate(workspace, manifest);
                    var savedPlan = store.loadSavedPlan();
                    BlockPlan plan;
                    boolean probed = stored.isEmpty();
                    if (savedPlan.isEmpty()) {
                        if (!probed) validateRuntime(worker, settings, versions);
                        probed = true;
                        checkInterrupted();
                        var audio = new AudioPreparer(runner, settings.ffmpeg().toString(), settings.ffprobe().toString())
                                .prepare(input, workspace);
                        plan = new BlockPlanner().plan(audio.duration(), audio.silences());
                        if (!PreparedWav.matches(audio.path(), plan.duration())) throw new IOException("Invalid prepared WAV");
                        store.savePlan(plan);
                    } else plan = savedPlan.get();
                    var pending = new ArrayList<Integer>();
                    for (var block : plan.blocks()) {
                        if (store.loadSavedBlock(block, manifest).isEmpty()) pending.add(block.id());
                    }
                    if (!pending.isEmpty()) {
                        if (!probed) validateRuntime(worker, settings, versions);
                        checkInterrupted();
                        if (!PreparedWav.matches(workDirectory.resolve("audio.wav"), plan.duration())) {
                            LOG.info("Recovering prepared audio, retaining saved plan and completed blocks");
                            var audio = new AudioPreparer(runner, settings.ffmpeg().toString(), settings.ffprobe().toString())
                                    .prepare(input, workspace);
                            if (!PreparedWav.matches(audio.path(), plan.duration())) {
                                throw new IOException("Recovered audio does not match saved plan duration/format; use a new workspace");
                            }
                        }
                        LOG.info("Running pending blocks {}", pending);
                        System.out.println("Pending blocks: " + pending.size());
                        worker.run(new WorkerRequest(1, workDirectory, manifest.config().modelDir(), manifest.inputSha256(),
                                manifest.configSha256(), manifest.config(), plan, List.copyOf(pending)), event -> {
                            LOG.info("Worker {} block {}", event.type(), event.blockId());
                            if (event.type().equals("block_done")) System.out.println("Completed block: " + event.blockId());
                        });
                    }
                    var results = new ArrayList<BlockResult>();
                    for (var block : plan.blocks()) {
                        checkInterrupted();
                        results.add(store.loadSavedBlock(block, manifest)
                                .orElseThrow(() -> new IOException("Missing completed block: " + block.id())));
                    }
                    checkInterrupted();
                    var transcript = new TranscriptAssembler().assemble(manifest, plan, results);
                    new TranscriptWriter(mapper).write(workspace, transcript);
                    checkInterrupted();
                    var after = JobIdentity.identify(input, manifest.config());
                    if (!after.inputSha256().equals(manifest.inputSha256()) || !after.configSha256().equals(manifest.configSha256())) {
                        new AtomicJsonFiles(mapper).write(invalidation,
                                Map.of("schemaVersion", 1, "reason", "INPUT_OR_MODEL_CHANGED"));
                        throw new IOException("Input/model changed during job; use a new workspace");
                    }
                    checkInterrupted();
                    LOG.info("Saved transcript.txt and transcript.json; input/model hashes rechecked");
                    return workDirectory;
                } catch (IOException | RuntimeException e) {
                    LOG.error("Job failed; checkpoints preserved", e);
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Job interrupted");
                    throw e;
                } catch (InterruptedException e) {
                    LOG.info("Job interrupted; checkpoints preserved");
                    throw e;
                } finally {
                    try { Runtime.getRuntime().removeShutdownHook(hook); }
                    catch (IllegalStateException ignored) { /* shutdown is already running */ }
                }
            }
        }
    }

    private void validateRuntime(WorkerClient worker, RuntimeSettings settings, Map<String,String> versions)
            throws IOException, InterruptedException {
        if (!worker.probe(settings, workDirectory.resolve("logs/probe.log")).equals(versions)) {
            throw new IOException("Installed engine versions changed; restore the original environment or use a new workspace");
        }
    }

    private static JobConfig config(Path model, Map<String,String> versions) {
        return new JobConfig(model, "", "cuda", "float16", "cs", 5, 0.0, true, false, true, 600, 30, 2, versions);
    }

    private static void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Job interrupted");
    }

    private static Path canonical(Path path) throws IOException {
        path = path.toAbsolutePath().normalize();
        if (Files.exists(path)) return path.toRealPath();
        return canonical(path.getParent()).resolve(path.getFileName());
    }

    private static void checkLocation(Path input, Path model, Path workspace) throws IOException {
        Path realModel = canonical(model);
        if (input.startsWith(workspace) || realModel.startsWith(workspace) || workspace.startsWith(realModel)) {
            throw new IOException("Input and model must be outside the workspace");
        }
    }

    private static void protectFiles(Path workspace, Path input) throws IOException {
        try (var paths = Files.walk(workspace)) {
            for (var path : paths.toList()) {
                if (Files.isSymbolicLink(path)) throw new IOException("Workspace symlink is not allowed: " + path);
                if (Files.isRegularFile(path) && Files.isSameFile(path, input)) {
                    throw new IOException("Workspace file aliases original input: " + path);
                }
            }
        }
    }
}
