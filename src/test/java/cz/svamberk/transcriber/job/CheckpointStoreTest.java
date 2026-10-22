package cz.svamberk.transcriber.job;

import cz.svamberk.transcriber.io.AtomicJsonFiles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(OutputCaptureExtension.class)
class CheckpointStoreTest {
    @TempDir Path temp;
    final JsonMapper mapper = JsonMapper.builder().build();
    Path input;
    Path model;
    JobManifest manifest;
    final Block block = new Block(0, 0, 10);
    final BlockPlan plan = new BlockPlan(1, 10, List.of(block));

    @BeforeEach
    void identity() throws Exception {
        input = Files.writeString(temp.resolve("přednáška.mp3"), "abc");
        model = Files.createDirectory(temp.resolve("model"));
        Files.writeString(model.resolve("weights.bin"), "123");
        manifest = JobIdentity.identify(input, config(model, 5, Map.of("engine", "1")));
    }

    JobConfig config(Path path, int beam, Map<String, String> versions) {
        return new JobConfig(path, "untrusted", "cuda", "float16", "cs", beam, 0,
                true, false, true, 600, 30, 2, versions);
    }

    Workspace workspace() throws IOException {
        return Workspace.open(Optional.of(temp.resolve("work")));
    }

    CheckpointStore store(Workspace workspace) throws IOException {
        var store = new CheckpointStore(workspace, mapper);
        store.initializeOrValidate(workspace, manifest);
        return store;
    }

    void audio(Workspace workspace) throws IOException {
        Files.writeString(workspace.path().resolve("audio.wav"), "finished audio");
    }

    BlockResult result(List<Segment> segments) {
        return new BlockResult(1, manifest.inputSha256(), manifest.configSha256(), 0, block.start(), block.end(), segments);
    }

    @Test
    void rejectsCompletedResultWhenReplannedBlockHasDifferentOffset() throws Exception {
        try (var workspace = workspace()) {
            var store = store(workspace);
            audio(workspace);
            store.savePlan(new BlockPlan(1, 20, List.of(new Block(0, 0, 11), new Block(1, 9, 20))));
            store.saveBlock(new BlockResult(1, manifest.inputSha256(), manifest.configSha256(), 1, 9, 20,
                    List.of(new Segment(0, 1, "original interval", List.of()))));
            var shifted = new Block(1, 10, 20);
            store.savePlan(new BlockPlan(1, 20, List.of(new Block(0, 0, 12), shifted)));
            assertTrue(store.loadSavedBlock(shifted, manifest).isEmpty());
        }
    }

    @Test
    void refusesLegacyResultWithoutBlockInterval() throws Exception {
        try (var workspace = workspace()) {
            var store = store(workspace);
            audio(workspace);
            store.savePlan(plan);
            store.saveBlock(result(List.of()));
            Path path = workspace.path().resolve("blocks/000000.json");
            var json = (tools.jackson.databind.node.ObjectNode) mapper.readTree(Files.readString(path));
            json.remove("blockStart");
            json.remove("blockEnd");
            Files.writeString(path, mapper.writeValueAsString(json));
            assertTrue(store.loadSavedBlock(block, manifest).isEmpty());
        }
    }

    @Test
    void hashesContentAndCanonicalConfigWithoutModelLocation() throws Exception {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", manifest.inputSha256());
        var versions = new LinkedHashMap<String, String>();
        versions.put("z", "2"); versions.put("a", "1");
        var first = JobIdentity.identify(input, config(model, 5, versions));
        Path relocated = Files.createDirectory(temp.resolve("moved"));
        Files.copy(model.resolve("weights.bin"), relocated.resolve("weights.bin"));
        var second = JobIdentity.identify(input, config(relocated, 5, Map.of("a", "1", "z", "2")));
        assertEquals(first.configSha256(), second.configSha256());
        assertEquals(first.config().modelSha256(), second.config().modelSha256());
        Files.writeString(relocated.resolve("weights.bin"), "124");
        var changed = JobIdentity.identify(input, config(relocated, 5, versions));
        assertNotEquals(first.config().modelSha256(), changed.config().modelSha256());
        assertNotEquals(first.configSha256(), changed.configSha256());
        Files.move(model.resolve("weights.bin"), model.resolve("renamed.bin"));
        assertNotEquals(first.config().modelSha256(), JobIdentity.identify(input, config(model, 5, versions)).config().modelSha256());
        Files.createSymbolicLink(model.resolve("link"), input);
        assertThrows(IOException.class, () -> JobIdentity.identify(input, config(model, 5, versions)));
    }

    @Test
    void rejectsManifestPreparedWithCollapsedTimeline() throws Exception {
        // Fingerprint produced by d176594 for the fixed input/model/config fixture above.
        var legacy = new JobManifest(1, manifest.inputSha256(), manifest.config(),
                "1bc502c3cebbfcf57649660ebe01d2a9cd78d9a3ab440dab02ec966e158661bd");
        try (var workspace = workspace()) {
            Path path = workspace.path().resolve("manifest.json");
            new AtomicJsonFiles(mapper).write(path, legacy);
            byte[] original = Files.readAllBytes(path);
            var store = new CheckpointStore(workspace, mapper);
            assertThrows(IOException.class, () -> store.initializeOrValidate(workspace, manifest));
            assertArrayEquals(original, Files.readAllBytes(path));
        }
    }

    @Test
    void rejectsChangedInputOrConfig() throws Exception {
        try (var workspace = workspace()) {
            var store = store(workspace);
            Files.writeString(input, "abd");
            var changedInput = JobIdentity.identify(input, config(model, 5, Map.of("engine", "1")));
            assertThrows(IOException.class, () -> store.initializeOrValidate(workspace, changedInput));
            Files.writeString(input, "abc");
            var changedConfig = JobIdentity.identify(input, config(model, 6, Map.of("engine", "1")));
            assertThrows(IOException.class, () -> store.initializeOrValidate(workspace, changedConfig));
            var changedVersions = JobIdentity.identify(input, config(model, 5, Map.of("engine", "2")));
            assertThrows(IOException.class, () -> store.initializeOrValidate(workspace, changedVersions));
            Files.writeString(model.resolve("weights.bin"), "124");
            var changedModel = JobIdentity.identify(input, config(model, 5, Map.of("engine", "1")));
            assertThrows(IOException.class, () -> store.initializeOrValidate(workspace, changedModel));
        }
    }

    @Test
    void keepsCompletedBlocksAndForeignFilesAcrossResume() throws Exception {
        var complete = result(List.of(new Segment(0, 10, "řeč", List.of(new Word(0, 1, "řeč")))));
        try (var workspace = workspace()) {
            var store = store(workspace);
            audio(workspace);
            store.savePlan(plan);
            store.saveBlock(complete);
            Files.writeString(workspace.path().resolve("keep.txt"), "untouched");
        }
        try (var workspace = workspace()) {
            var store = store(workspace);
            assertEquals(plan, store.loadPlan().orElseThrow());
            assertEquals(complete, store.loadValidBlock(block, manifest).orElseThrow());
            assertEquals("untouched", Files.readString(workspace.path().resolve("keep.txt")));
            store.saveBlock(result(List.of()));
            assertTrue(store.loadValidBlock(block, manifest).orElseThrow().segments().isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"manifest.json", "plan.json", "blocks/000000.json"})
    void rejectsUnknownSchema(String name) throws Exception {
        try (var workspace = workspace()) {
            var store = store(workspace);
            audio(workspace);
            store.savePlan(plan);
            Path path = workspace.path().resolve(name);
            Files.createDirectories(path.getParent());
            Files.writeString(path, "{\"schemaVersion\":2}");
            assertThrows(IOException.class, () -> {
                if (name.equals("manifest.json")) store.initializeOrValidate(workspace, manifest);
                else if (name.equals("plan.json")) store.loadPlan();
                else store.loadValidBlock(block, manifest);
            });
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "{broken", "{}"})
    void corruptManifestIsFatal(String json) throws Exception {
        try (var workspace = workspace()) {
            var store = store(workspace);
            Files.writeString(workspace.path().resolve("manifest.json"), json);
            assertThrows(IOException.class, () -> store.initializeOrValidate(workspace, manifest));
        }
    }

    @Test
    void rejectsTamperedManifestConfig() throws Exception {
        try (var workspace = workspace()) {
            var store = store(workspace);
            var tampered = new JobManifest(1, manifest.inputSha256(), config(model, 99, Map.of()), manifest.configSha256());
            new AtomicJsonFiles(mapper).write(workspace.path().resolve("manifest.json"), tampered);
            assertThrows(IOException.class, () -> store.initializeOrValidate(workspace, manifest));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "{broken", "{}"})
    void ignoresTmpAndRejectsCorruptBlockAsCompleted(String json, CapturedOutput output) throws Exception {
        try (var workspace = workspace()) {
            var store = store(workspace);
            audio(workspace);
            store.savePlan(plan);
            Path blocks = Files.createDirectories(workspace.path().resolve("blocks"));
            new AtomicJsonFiles(mapper).write(blocks.resolve("000000.json.tmp"), result(List.of()));
            assertTrue(store.loadValidBlock(block, manifest).isEmpty());
            Files.writeString(blocks.resolve("000000.json"), json);
            assertTrue(store.loadValidBlock(block, manifest).isEmpty());
            assertTrue(output.getAll().contains("000000.json"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.9", "\"0\"", "false"})
    void malformedIdsRemainPending(String id) throws Exception {
        try (var workspace = workspace()) {
            var store = store(workspace);
            audio(workspace);
            store.savePlan(plan);
            Path file = workspace.path().resolve("blocks/000000.json");
            Files.createDirectories(file.getParent());
            String json = mapper.writeValueAsString(result(List.of())).replace("\"blockId\":0", "\"blockId\":" + id);
            Files.writeString(file, json);
            boolean blockPending = store.loadValidBlock(block, manifest).isEmpty();
            Files.writeString(workspace.path().resolve("plan.json"),
                    "{\"schemaVersion\":1,\"duration\":10,\"blocks\":[{\"id\":" + id + ",\"start\":0,\"end\":10}]}");
            assertAll(() -> assertTrue(blockPending, "Invalid blockId: " + id),
                    () -> assertTrue(store.loadPlan().isEmpty(), "Invalid plan ID: " + id));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.9", "\"1\"", "true"})
    void malformedSchemasAreNeverAccepted(String version) throws Exception {
        try (var workspace = workspace()) {
            var store = store(workspace);
            audio(workspace);
            store.savePlan(plan);
            Path file = workspace.path().resolve("blocks/000000.json");
            Files.createDirectories(file.getParent());
            Files.writeString(file, mapper.writeValueAsString(result(List.of()))
                    .replace("\"schemaVersion\":1", "\"schemaVersion\":" + version));
            assertTrue(store.loadValidBlock(block, manifest).isEmpty());
            Files.writeString(workspace.path().resolve("plan.json"), mapper.writeValueAsString(plan)
                    .replace("\"schemaVersion\":1", "\"schemaVersion\":" + version));
            assertTrue(store.loadPlan().isEmpty());
            Files.writeString(workspace.path().resolve("manifest.json"), mapper.writeValueAsString(manifest)
                    .replace("\"schemaVersion\":1", "\"schemaVersion\":" + version));
            assertThrows(IOException.class, () -> store.initializeOrValidate(workspace, manifest));
        }
    }

    @Test
    void rejectsScalarCoercionAtCheckpointBoundary() throws Exception {
        try (var workspace = workspace()) {
            var store = store(workspace);
            audio(workspace);
            store.savePlan(plan);
            store.saveBlock(result(List.of(new Segment(0, 10, "text", List.of()))));
            Path file = workspace.path().resolve("blocks/000000.json");
            String valid = Files.readString(file);
            for (String malformed : List.of(valid.replace("\"start\":0.0", "\"start\":\"0\""),
                    valid.replace("\"text\":\"text\"", "\"text\":true"),
                    valid.replace("\"text\":\"text\"", "\"text\":123"))) {
                Files.writeString(file, malformed);
                assertTrue(store.loadValidBlock(block, manifest).isEmpty(), malformed);
            }
            String validManifest = Files.readString(workspace.path().resolve("manifest.json"));
            for (String replacement : List.of("\"true\"", "1")) {
                Files.writeString(workspace.path().resolve("manifest.json"), validManifest
                        .replace("\"wordTimestamps\":true", "\"wordTimestamps\":" + replacement));
                assertThrows(IOException.class, () -> store.initializeOrValidate(workspace, manifest));
            }
        }
    }

    @Test
    void validatesIdentityIdsSegmentsAndWords() throws Exception {
        try (var workspace = workspace()) {
            var store = store(workspace);
            audio(workspace);
            store.savePlan(plan);
            var valid = new Segment(0, 10, "text", List.of());
            var invalid = List.of(
                    new BlockResult(1, "wrong", manifest.configSha256(), 0, 0, 10, List.of()),
                    new BlockResult(1, manifest.inputSha256(), "wrong", 0, 0, 10, List.of()),
                    new BlockResult(1, manifest.inputSha256(), manifest.configSha256(), 1, 0, 10, List.of()),
                    result(null), result(java.util.Arrays.asList((Segment) null)),
                    result(List.of(new Segment(-1, 1, "", List.of()))),
                    result(List.of(new Segment(2, 1, "", List.of()))),
                    result(List.of(new Segment(0, 11, "", List.of()))),
                    result(List.of(new Segment(0, Double.NaN, "", List.of()))),
                    result(List.of(new Segment(0, Double.POSITIVE_INFINITY, "", List.of()))),
                    result(List.of(new Segment(5, 6, "", List.of()), new Segment(1, 2, "", List.of()))),
                    result(List.of(new Segment(0, 10, "", null))),
                    result(List.of(new Segment(0, 10, "", List.of(new Word(-1, 1, ""))))),
                    result(List.of(new Segment(0, 10, "", List.of(new Word(2, 1, ""))))),
                    result(List.of(new Segment(0, 10, "", List.of(new Word(0, 11, ""))))),
                    result(List.of(new Segment(0, 10, "", List.of(new Word(0, Double.NaN, ""))))),
                    result(List.of(new Segment(0, 10, "", List.of(new Word(5, 6, ""), new Word(1, 2, ""))))));
            Path file = workspace.path().resolve("blocks/000000.json");
            for (var value : invalid) {
                new AtomicJsonFiles(mapper).write(file, value);
                assertTrue(store.loadValidBlock(block, manifest).isEmpty(), value.toString());
            }
            store.saveBlock(result(List.of(valid)));
            assertTrue(store.loadValidBlock(new Block(0, 0, 20), manifest).isEmpty());
            assertTrue(store.loadValidBlock(block, new JobManifest(1, "wrong", manifest.config(), manifest.configSha256())).isEmpty());
        }
    }

    @Test
    void requiresCompletedAudioAndCoveringPlanBeforeAcceptingBlocks(CapturedOutput output) throws Exception {
        try (var workspace = workspace()) {
            var store = store(workspace);
            Files.writeString(workspace.path().resolve("audio.wav.tmp"), "unfinished");
            assertThrows(IOException.class, () -> store.savePlan(plan));
            audio(workspace);
            var invalid = List.of(new BlockPlan(1, 10, List.of()),
                    new BlockPlan(1, Double.NaN, List.of(block)),
                    new BlockPlan(1, 10, null),
                    new BlockPlan(1, 10, List.of(new Block(0, 1, 10))),
                    new BlockPlan(1, 10, List.of(new Block(0, 0, 9))),
                    new BlockPlan(1, 10, List.of(new Block(0, 0, 4), new Block(1, 5, 10))),
                    new BlockPlan(1, 10, List.of(block, block)),
                    new BlockPlan(1, 10, List.of(new Block(1, 0, 10))),
                    new BlockPlan(1, 10, List.of(new Block(0, 0, 11))),
                    new BlockPlan(1, 10, List.of(new Block(0, 0, Double.POSITIVE_INFINITY))));
            for (var value : invalid) {
                assertThrows(IOException.class, () -> store.savePlan(value));
                new AtomicJsonFiles(mapper).write(workspace.path().resolve("plan.json"), value);
                assertTrue(store.loadPlan().isEmpty(), value.toString());
            }
            Files.writeString(workspace.path().resolve("plan.json"), "null");
            assertTrue(store.loadPlan().isEmpty());
            assertTrue(output.getAll().contains("plan.json"));
            assertThrows(IOException.class, () -> store.saveBlock(result(List.of())));
            new AtomicJsonFiles(mapper).write(workspace.path().resolve("blocks/000000.json"), result(List.of()));
            assertTrue(store.loadValidBlock(block, manifest).isEmpty());
            var overlap = new BlockPlan(1, 10, List.of(new Block(0, 0, 6), new Block(1, 4, 10)));
            store.savePlan(overlap);
            assertEquals(overlap, store.loadPlan().orElseThrow());
            Files.delete(workspace.path().resolve("audio.wav"));
            assertTrue(store.loadPlan().isEmpty());
            audio(workspace);
            store.savePlan(new BlockPlan(1, 0, List.of()));
            assertTrue(store.loadPlan().isPresent());
        }
    }

    @Test
    void atomicJsonPreservesPreviousValueOnFailure() throws Exception {
        Path target = temp.resolve("state.json");
        var files = new AtomicJsonFiles(mapper);
        files.write(target, Map.of("text", "původní"));
        String previous = Files.readString(target);
        assertThrows(IOException.class, () -> files.write(target, new BrokenValue("bad")));
        assertEquals(previous, Files.readString(target));
        files.write(target, Map.of("text", "nový"));
        assertEquals("nový", mapper.readTree(Files.readString(target)).get("text").asString());
        try (var entries = Files.list(temp)) {
            assertTrue(entries.noneMatch(path -> path.toString().endsWith(".tmp")));
        }
    }

    public record BrokenValue(String text) {
        @Override public String text() { throw new IllegalStateException("cannot serialize"); }
    }
}
