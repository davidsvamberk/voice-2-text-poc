package cz.svamberk.transcriber.job;

import cz.svamberk.transcriber.io.AtomicJsonFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.type.LogicalType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

public final class CheckpointStore {
    private static final Logger LOG = LoggerFactory.getLogger(CheckpointStore.class);
    private final Workspace workspace;
    private final ObjectMapper mapper;
    private final AtomicJsonFiles files;
    private JobManifest manifest;

    public CheckpointStore(Workspace workspace, ObjectMapper mapper) {
        this.workspace = workspace;
        this.mapper = mapper.rebuild()
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .withCoercionConfig(LogicalType.Textual, coercion -> coercion
                        .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
                .build();
        this.files = new AtomicJsonFiles(mapper);
    }

    /** Read the original identity without initializing or rewriting it. */
    public Optional<JobManifest> loadManifest() throws IOException {
        Path path = workspace.path().resolve("manifest.json");
        if (!Files.exists(path, NOFOLLOW_LINKS)) return Optional.empty();
        var stored = read(path, JobManifest.class);
        validateManifest(stored);
        return Optional.of(stored);
    }

    public void initializeOrValidate(Workspace workspace, JobManifest expected) throws IOException {
        require(this.workspace == workspace, "Wrong workspace");
        validateManifest(expected);
        Path path = workspace.path().resolve("manifest.json");
        if (Files.exists(path, NOFOLLOW_LINKS)) {
            var stored = read(path, JobManifest.class);
            validateManifest(stored);
            require(stored.inputSha256().equals(expected.inputSha256())
                    && stored.configSha256().equals(expected.configSha256()), "Input or configuration changed");
        } else {
            files.write(path, expected);
        }
        manifest = expected;
    }

    public void savePlan(BlockPlan plan) throws IOException {
        requireInitialized();
        validatePlan(plan);
        requireAudio();
        files.write(workspace.path().resolve("plan.json"), plan);
    }

    public Optional<BlockPlan> loadPlan() throws IOException {
        var plan = loadSavedPlan();
        if (plan.isEmpty()) return plan;
        try {
            requireAudio();
            return plan;
        } catch (IOException e) {
            pending(workspace.path().resolve("audio.wav"), e);
            return Optional.empty();
        }
    }

    /** Plan/result validation for reassembly also works when prepared audio needs recovery. */
    public Optional<BlockPlan> loadSavedPlan() throws IOException {
        Path path = workspace.path().resolve("plan.json");
        if (!Files.exists(path, NOFOLLOW_LINKS)) return Optional.empty();
        try {
            var plan = read(path, BlockPlan.class);
            validatePlan(plan);
            return Optional.of(plan);
        } catch (UnsupportedSchemaException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            pending(path, e);
            return Optional.empty();
        }
    }

    public void saveBlock(BlockResult result) throws IOException {
        requireInitialized();
        var plan = loadPlan().orElseThrow(() -> new IOException("No valid audio plan"));
        require(result != null, "Missing block result");
        var block = plan.blocks().stream().filter(b -> b.id() == result.blockId()).findFirst()
                .orElseThrow(() -> new IOException("Block is not in plan"));
        validateBlock(result, block, manifest);
        files.write(blockPath(result.blockId()), result);
    }

    public Optional<BlockResult> loadValidBlock(Block block, JobManifest expected) throws IOException {
        return loadValidBlock(block, expected, loadPlan());
    }

    public Optional<BlockResult> loadSavedBlock(Block block, JobManifest expected) throws IOException {
        return loadValidBlock(block, expected, loadSavedPlan());
    }

    private Optional<BlockResult> loadValidBlock(Block block, JobManifest expected, Optional<BlockPlan> plan) throws IOException {
        requireInitialized();
        if (plan.isEmpty() || !plan.get().blocks().contains(block)) return Optional.empty();
        Path path = blockPath(block.id());
        if (!Files.exists(path, NOFOLLOW_LINKS)) return Optional.empty();
        try {
            require(expected != null && expected.schemaVersion() == 1
                    && Objects.equals(expected.inputSha256(), manifest.inputSha256())
                    && Objects.equals(expected.configSha256(), manifest.configSha256()), "Wrong job identity");
            var result = read(path, BlockResult.class);
            validateBlock(result, block, expected);
            return Optional.of(result);
        } catch (UnsupportedSchemaException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            pending(path, e);
            return Optional.empty();
        }
    }

    private Path blockPath(int id) {
        return workspace.path().resolve("blocks").resolve(String.format(Locale.ROOT, "%06d.json", id));
    }

    private <T> T read(Path path, Class<T> type) throws IOException {
        require(Files.isRegularFile(path, NOFOLLOW_LINKS), "Not a regular checkpoint: " + path);
        try {
            String json = Files.readString(path);
            var node = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(json);
            require(node != null && node.isObject(), "Missing JSON object: " + path);
            var version = node.get("schemaVersion");
            require(version != null && version.isIntegralNumber() && version.canConvertToInt(),
                    "Missing schema version: " + path);
            schema(version.asInt());
            if (type == JobManifest.class && node.get("config") instanceof tools.jackson.databind.node.ObjectNode config) {
                // Original schema-1 manifests predate these optional hints. Keep all other fields strict.
                if (!config.has("initialPrompt")) config.putNull("initialPrompt");
                if (!config.has("hotwords")) config.putNull("hotwords");
                json = mapper.writeValueAsString(node);
            }
            return mapper.readerFor(type).with(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
                    DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                    DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(json);
        } catch (RuntimeException e) {
            throw new IOException("Invalid JSON: " + path, e);
        }
    }

    private static void validateManifest(JobManifest value) throws IOException {
        require(value != null, "Missing manifest");
        schema(value.schemaVersion());
        require(hash(value.inputSha256()) && hash(value.configSha256()) && value.config() != null,
                "Invalid manifest identity");
        try {
            require(hash(value.config().modelSha256())
                    && value.configSha256().equals(JobIdentity.configHash(value.config())), "Invalid config identity");
        } catch (RuntimeException e) {
            throw new IOException("Invalid manifest configuration", e);
        }
    }

    private static boolean hash(String value) { return value != null && value.matches("[0-9a-f]{64}"); }

    private static void validatePlan(BlockPlan plan) throws IOException {
        require(plan != null, "Missing plan");
        schema(plan.schemaVersion());
        require(Double.isFinite(plan.duration()) && plan.duration() >= 0 && plan.blocks() != null, "Invalid plan duration/blocks");
        if (plan.duration() == 0) {
            require(plan.blocks().isEmpty(), "Zero-duration plan must be empty");
            return;
        }
        require(!plan.blocks().isEmpty(), "Plan does not cover audio");
        double previousStart = -1;
        double previousEnd = 0;
        int id = 0;
        for (var block : plan.blocks()) {
            require(block != null && block.id() == id++, "Invalid block ID/order");
            interval(block.start(), block.end(), plan.duration());
            require(block.end() > block.start() && block.start() > previousStart
                    && block.end() > previousEnd && block.start() <= previousEnd, "Plan has gaps or unordered blocks");
            previousStart = block.start();
            previousEnd = block.end();
        }
        require(previousEnd == plan.duration(), "Plan does not cover audio end");
    }

    private static void validateBlock(BlockResult result, Block block, JobManifest manifest) throws IOException {
        require(result != null, "Missing block result");
        schema(result.schemaVersion());
        require(result.blockId() == block.id() && Objects.equals(result.inputSha256(), manifest.inputSha256())
                && Objects.equals(result.configSha256(), manifest.configSha256()), "Block identity mismatch");
        require(result.blockStart() == block.start() && result.blockEnd() == block.end(),
                "Block interval changed");
        require(result.segments() != null, "Missing segments");
        double length = block.end() - block.start();
        double previousEnd = 0;
        for (var segment : result.segments()) {
            require(segment != null && segment.text() != null && segment.words() != null, "Invalid segment");
            interval(segment.start(), segment.end(), length);
            require(segment.start() >= previousEnd, "Segments out of order");
            previousEnd = segment.end();
            double wordEnd = 0;
            for (var word : segment.words()) {
                require(word != null && word.text() != null, "Invalid word");
                interval(word.start(), word.end(), length);
                require(word.start() >= wordEnd, "Words out of order");
                wordEnd = word.end();
            }
        }
    }

    private static void interval(double start, double end, double length) throws IOException {
        require(Double.isFinite(start) && Double.isFinite(end) && start >= 0 && start <= end && end <= length,
                "Invalid checkpoint timestamps");
    }

    private void requireAudio() throws IOException {
        Path audio = workspace.path().resolve("audio.wav");
        require(Files.isRegularFile(audio, NOFOLLOW_LINKS) && Files.size(audio) > 0, "Missing completed audio.wav");
    }

    private void requireInitialized() throws IOException { require(manifest != null, "Store is not initialized"); }

    private static void pending(Path path, Exception e) { LOG.warn("Checkpoint pending: {} ({})", path, e.getMessage()); }

    private static void schema(int version) throws IOException {
        if (version != 1) throw new UnsupportedSchemaException(version);
    }

    private static void require(boolean condition, String message) throws IOException {
        if (!condition) throw new IOException(message);
    }

    private static final class UnsupportedSchemaException extends IOException {
        UnsupportedSchemaException(int version) { super("Unsupported schemaVersion: " + version); }
    }
}
