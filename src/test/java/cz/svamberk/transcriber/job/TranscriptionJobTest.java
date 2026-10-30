package cz.svamberk.transcriber.job;

import cz.svamberk.transcriber.cli.*;
import cz.svamberk.transcriber.config.RuntimeSettings;
import cz.svamberk.transcriber.process.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.json.JsonMapper;

import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class TranscriptionJobTest {
    @TempDir Path temp;
    final JsonMapper mapper = JsonMapper.builder().build();
    Path input, model, work, script;
    RuntimeSettings settings;
    String mode = "valid", version = "1";
    int probes, preparations, workers;
    boolean forbidProcesses;
    boolean failProbe;
    final AtomicReference<Process> child = new AtomicReference<>();

    @BeforeEach void setup() throws Exception {
        input = Files.writeString(temp.resolve("přednáška 1.mp3"), "original lecture");
        model = Files.createDirectory(temp.resolve("model"));
        Files.writeString(model.resolve("model.bin"), "weights");
        work = temp.resolve("rozpracováno");
        script = temp.resolve("fake.py");
        Files.writeString(script, """
                import json, os, sys, time
                from pathlib import Path
                r = json.loads(Path(sys.argv[1]).read_text())
                mode = sys.argv[2]
                def emit(**event):
                    print(json.dumps(dict(schemaVersion=1, **event)), flush=True)
                emit(type='ready', engineVersions=r['config']['engineVersions'])
                for i in r['pendingBlockIds']:
                    if mode == 'fail' and i == 1:
                        emit(type='error', code='TEST', message='failed second block')
                        sys.exit(1)
                    if mode == 'wait' and i == 1:
                        Path(r['workDir'], 'waiting').write_text(str(os.getpid()))
                        time.sleep(60)
                    segments = [] if mode == 'empty' else [dict(start=0, end=1, text='Ahoj.', words=[])]
                    if mode == 'ambiguous':
                        segments = [dict(start=599.2 if i == 0 else 0.2,
                            end=599.8 if i == 0 else 0.8, text=f'text-{i}', words=[])]
                    p = Path(r['workDir'], 'blocks', f'{i:06d}.json')
                    p.parent.mkdir(exist_ok=True)
                    p.write_text(json.dumps(dict(schemaVersion=1, inputSha256=r['inputSha256'],
                        configSha256=r['configSha256'], blockId=i,
                        blockStart=r['plan']['blocks'][i]['start'], blockEnd=r['plan']['blocks'][i]['end'],
                        segments=segments)))
                    emit(type='block_done', blockId=i)
                """);
        settings = new RuntimeSettings(Path.of("missing-python"), Path.of("missing-worker"), model,
                Path.of("fake-ffmpeg"), Path.of("fake-ffprobe"), 600, 30, 2.0, true, "", "");
    }

    class FakeRunner extends ProcessRunner {
        @Override public ProcessResult run(List<String> command, Path log) throws IOException {
            assertFalse(forbidProcesses, "completed resume must not launch any process");
            Files.createDirectories(log.toAbsolutePath().getParent());
            Files.writeString(log, "");
            if (command.contains("--probe")) {
                probes++;
                if (failProbe) throw new IOException("Probe unavailable");
                return new ProcessResult(0, "{\"schemaVersion\":1,\"engineVersions\":{\"fake\":\""+version+"\"}}");
            }
            if (command.contains("pcm_s16le")) {
                preparations++;
                wav(Path.of(command.getLast()), 602);
            }
            return new ProcessResult(0, command.contains("format=duration") ? "602" : "fake version");
        }
        @Override public Process start(List<String> command, Path log) throws IOException {
            assertFalse(forbidProcesses);
            workers++;
            if (mode.equals("mutate")) Files.writeString(input, "changed during inference");
            if (mode.equals("mutate-model")) Files.writeString(model.resolve("model.bin"), "changed weights");
            var process = super.start(List.of("/usr/bin/python3", script.toString(), command.getLast(), mode), log);
            child.set(process);
            return process;
        }
    }

    TranscriptionJob job() { return new TranscriptionJob(mapper, new FakeRunner()); }
    CliArguments args() { return new CliArguments(input, Optional.of(work), false); }
    Path run() throws Exception { return job().run(args(), settings); }
    Path block0() { return work.resolve("blocks/000000.json"); }

    @Test void configuredShortBlocksPersistHintsResumeAndRejectChangedHints() throws Exception {
        settings = new RuntimeSettings(settings.python(), settings.workerDir(), settings.modelDir(),
                settings.ffmpeg(), settings.ffprobe(), 20, 1, 2.0, false, "Oborový kontext.", "pedagogika");
        run();
        var manifest = mapper.readValue(work.resolve("manifest.json").toFile(), JobManifest.class);
        var plan = mapper.readValue(work.resolve("plan.json").toFile(), BlockPlan.class);
        assertEquals(20, manifest.config().targetSeconds());
        assertFalse(manifest.config().conditionOnPreviousText());
        assertEquals("Oborový kontext.", manifest.config().initialPrompt());
        assertEquals("pedagogika", manifest.config().hotwords());
        assertTrue(plan.blocks().size() > 25);
        assertTrue(plan.blocks().stream().allMatch(b -> b.end() - b.start() <= 23));
        var completed = Files.readAllBytes(block0());
        forbidProcesses = true;
        run();
        assertArrayEquals(completed, Files.readAllBytes(block0()));
        settings = new RuntimeSettings(settings.python(), settings.workerDir(), settings.modelDir(),
                settings.ffmpeg(), settings.ffprobe(), 20, 1, 2.0, false, "Jiný kontext.", "pedagogika");
        assertThrows(IOException.class, this::run);
    }
    void incomplete() throws Exception {
        mode = "fail";
        assertThrows(IOException.class, this::run);
        assertTrue(Files.exists(block0()));
        mode = "valid";
    }

    @Test void runsToTxtAndJsonLines() throws Exception {
        assertEquals(work.toAbsolutePath(), run());
        assertEquals("Ahoj.\nAhoj.\n", Files.readString(work.resolve("transcript.txt")));
        var records = Files.readAllLines(work.resolve("transcript.jsonl")).stream().map(mapper::readTree).toList();
        assertEquals(List.of("metadata", "segment", "segment"), records.stream().map(r -> r.get("type").asString()).toList());
        assertEquals(599, records.get(2).get("start").asDouble());
        assertEquals("original lecture", Files.readString(input));
        assertTrue(Files.size(work.resolve("logs/java.log")) > 0);
        assertEquals(1, probes);
        assertEquals(1, workers);
    }

    @Test void successfulCommandDoesNotSuggestResuming() throws Exception {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        var command = new TranscriberCommand(new PrintStream(out), new PrintStream(err), job(), settings);
        assertEquals(0, command.execute(new String[]{input.toString(), "--work-dir", work.toString()}));
        assertTrue(out.toString().contains("Saved transcript.txt and transcript.jsonl"));
        assertEquals("", err.toString());
    }

    @Test void retriesFailedPreflightInSameWorkspace() throws Exception {
        failProbe = true;
        assertThrows(IOException.class, this::run);
        assertFalse(Files.exists(work.resolve("manifest.json")));
        failProbe = false;
        assertEquals(work.toAbsolutePath(), run());
        assertEquals(1, workers);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void reportsAmbiguousOverlapsWithoutChangingTranscriptText(CapturedOutput output) throws Exception {
        mode = "ambiguous";
        run();
        assertEquals("text-0\ntext-1\n", Files.readString(work.resolve("transcript.txt")));
        assertTrue(output.getErr().contains("Ambiguous overlaps: 1"));
    }

    @Test void shutdownDoesNotWaitForOwnerSystemExit() throws Exception {
        Path root = Files.createDirectory(temp.resolve("shutdown-job"));
        Path log = temp.resolve("shutdown.log");
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), ShutdownProbe.class.getName(), root.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        Path waiting = root.resolve("rozpracováno/waiting");
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!Files.exists(waiting) && process.isAlive() && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(Files.exists(waiting), Files.readString(log));
            // SIGTERM runs the same JVM shutdown hooks as Ctrl+C, without depending on shell signal handling.
            process.destroy();
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Shutdown waited for owner System.exit: " + Files.readString(log));
            assertTrue(Files.exists(root.resolve("rozpracováno/blocks/000000.json")));
            try (var ignored = Workspace.open(Optional.of(root.resolve("rozpracováno")))) { /* lock released */ }
        } finally {
            process.destroyForcibly();
            if (Files.exists(waiting)) {
                ProcessHandle.of(Long.parseLong(Files.readString(waiting).strip()))
                        .ifPresent(handle -> { if (handle.isAlive()) handle.destroyForcibly(); });
            }
        }
    }

    public static class ShutdownProbe {
        public static void main(String[] args) throws Exception {
            var fixture = new TranscriptionJobTest();
            fixture.temp = Path.of(args[0]);
            fixture.setup();
            fixture.mode = "wait";
            int code = new TranscriberCommand(System.out, System.err, fixture.job(), fixture.settings).execute(
                    new String[]{fixture.input.toString(), "--work-dir", fixture.work.toString()});
            System.exit(code);
        }
    }

    @Test void resumesOnlyPendingBlocks() throws Exception {
        incomplete();
        byte[] completed = Files.readAllBytes(block0());
        byte[] manifest = Files.readAllBytes(work.resolve("manifest.json"));
        run();
        assertEquals(List.of(1), mapper.readTree(Files.readString(work.resolve("request.json")))
                .get("pendingBlockIds").values().stream().map(n -> n.asInt()).toList());
        assertArrayEquals(completed, Files.readAllBytes(block0()));
        assertArrayEquals(manifest, Files.readAllBytes(work.resolve("manifest.json")));
        assertEquals(1, preparations);
        assertEquals(2, probes);
    }

    @Test void reassemblesWithoutWorkerWhenAllBlocksExist() throws Exception {
        run();
        Files.delete(work.resolve("audio.wav"));
        Files.delete(work.resolve("transcript.txt"));
        Files.delete(work.resolve("transcript.jsonl"));
        forbidProcesses = true;
        run();
        assertEquals("Ahoj.\nAhoj.\n", Files.readString(work.resolve("transcript.txt")));
        assertEquals(3, Files.readAllLines(work.resolve("transcript.jsonl")).size());
        assertEquals(1, workers);
        assertEquals(1, probes);
    }

    @Test void emptyRecognitionProducesValidOutputs() throws Exception {
        mode = "empty";
        run();
        assertEquals("", Files.readString(work.resolve("transcript.txt")));
        var records = Files.readAllLines(work.resolve("transcript.jsonl"));
        assertEquals(1, records.size());
        assertEquals("metadata", mapper.readTree(records.getFirst()).get("type").asString());
    }

    @Test void failureKeepsCompletedBlocksAndReportsDirectory() throws Exception {
        mode = "fail";
        var bytes = new ByteArrayOutputStream();
        var output = new PrintStream(bytes);
        assertEquals(1, new TranscriberCommand(output, output, job(), settings).execute(
                new String[]{input.toString(), "--work-dir", work.toString()}));
        assertTrue(Files.exists(block0()));
        assertFalse(Files.exists(work.resolve("transcript.txt")));
        assertTrue(bytes.toString().contains(work.toString()));
        assertTrue(bytes.toString().contains("--work-dir"));
        assertFalse(child.get().isAlive());
        try (var ignored = Workspace.open(Optional.of(work))) { /* lock released */ }
    }

    @Test void interruptionStopsChildAndKeepsCheckpoint() throws Exception {
        mode = "wait";
        var bytes = new ByteArrayOutputStream();
        var output = new PrintStream(bytes);
        var code = new AtomicReference<Integer>();
        var thread = Thread.ofPlatform().start(() -> code.set(new TranscriberCommand(output, output, job(), settings)
                .execute(new String[]{input.toString(), "--work-dir", work.toString()})));
        try {
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!Files.exists(work.resolve("waiting")) && System.nanoTime() < end) Thread.sleep(10);
            assertTrue(Files.exists(work.resolve("waiting")));
            thread.interrupt();
            thread.join(8000);
            assertFalse(thread.isAlive());
            assertEquals(130, code.get());
            assertFalse(child.get().isAlive());
            assertTrue(Files.exists(block0()));
            try (var ignored = Workspace.open(Optional.of(work))) { /* cleanup precedes unlock */ }
        } finally { thread.interrupt(); }
    }

    @Test void changedRuntimeRejectsResumeWithoutReplacingManifest() throws Exception {
        incomplete();
        var previous = Files.readAllBytes(work.resolve("manifest.json"));
        version = "2";
        assertThrows(IOException.class, this::run);
        assertArrayEquals(previous, Files.readAllBytes(work.resolve("manifest.json")));
        assertEquals(1, workers);
    }

    @Test void recoversDamagedAudioWithoutReplacingPlanOrCompletedBlocks() throws Exception {
        incomplete();
        var plan = Files.readAllBytes(work.resolve("plan.json"));
        var completed = Files.readAllBytes(block0());
        Files.writeString(work.resolve("audio.wav"), "broken WAV");
        run();
        assertEquals(2, preparations);
        assertArrayEquals(plan, Files.readAllBytes(work.resolve("plan.json")));
        assertArrayEquals(completed, Files.readAllBytes(block0()));
    }

    @Test void recoversMissingAudioAndRejectsWrongDuration() throws Exception {
        incomplete();
        Files.delete(work.resolve("audio.wav"));
        run();
        assertEquals(2, preparations);
        Files.delete(work.resolve("blocks/000001.json"));
        wav(work.resolve("audio.wav"), 1);
        run();
        assertEquals(3, preparations);
    }

    @Test void rejectsOriginalInsideWorkspaceBeforeWriting() throws Exception {
        run();
        input = work.resolve("audio.wav");
        var original = Files.readAllBytes(input);
        assertThrows(IOException.class, this::run);
        assertArrayEquals(original, Files.readAllBytes(input));
    }

    @Test void rejectsOutputHardlinkToOriginal() throws Exception {
        incomplete();
        Files.createLink(work.resolve("transcript.txt"), input);
        assertThrows(IOException.class, this::run);
        assertEquals("original lecture", Files.readString(input));
    }

    @ParameterizedTest
    @ValueSource(strings = {"mutate", "mutate-model"})
    void rejectsResumeAfterDetectedMutationEvenWhenOriginalIsRestored(String mutation) throws Exception {
        mode = mutation;
        var failure = assertThrows(IOException.class, this::run);
        assertTrue(failure.getMessage().contains("use a new workspace"));
        var artifacts = new LinkedHashMap<Path, byte[]>();
        for (String name : List.of("manifest.json", "plan.json", "blocks/000000.json",
                "blocks/000001.json", "transcript.txt", "transcript.jsonl")) {
            var path = work.resolve(name);
            artifacts.put(path, Files.readAllBytes(path));
        }
        Files.writeString(input, "original lecture");
        Files.writeString(model.resolve("model.bin"), "weights");
        mode = "valid";
        forbidProcesses = true;
        var resumeFailure = assertThrows(IOException.class, this::run);
        assertTrue(resumeFailure.getMessage().contains("invalidated"));
        assertTrue(resumeFailure.getMessage().contains("use a new workspace"));
        var marker = mapper.readTree(Files.readString(work.resolve("invalidated.json")));
        assertEquals(1, marker.get("schemaVersion").asInt());
        assertEquals("INPUT_OR_MODEL_CHANGED", marker.get("reason").asString());
        for (var artifact : artifacts.entrySet()) {
            assertArrayEquals(artifact.getValue(), Files.readAllBytes(artifact.getKey()));
        }
    }

    @Test void corruptStoredManifestFailsBeforeProbe() throws Exception {
        run();
        Files.writeString(work.resolve("manifest.json"), "{\"schemaVersion\":2}");
        forbidProcesses = true;
        assertThrows(IOException.class, this::run);
    }

    @Test void rejectsBrokenRiffSizeWhenCheckingPreparedAudio() throws Exception {
        var audio = temp.resolve("audio.wav");
        wav(audio, 1);
        assertTrue(cz.svamberk.transcriber.audio.PreparedWav.matches(audio, 1));
        try (var file = new RandomAccessFile(audio.toFile(), "rw")) {
            file.seek(4);
            file.writeInt(Integer.reverseBytes(36));
        }
        assertFalse(cz.svamberk.transcriber.audio.PreparedWav.matches(audio, 1));
    }

    @Test void validatesRf64WithoutExternalTools() throws Exception {
        var audio = temp.resolve("rf64.wav");
        var h = ByteBuffer.allocate(80).order(ByteOrder.LITTLE_ENDIAN);
        h.put("RF64".getBytes()).putInt(-1).put("WAVEds64".getBytes()).putInt(28)
                .putLong(32072).putLong(32000).putLong(16000).putInt(0)
                .put("fmt ".getBytes()).putInt(16).putShort((short)1).putShort((short)1)
                .putInt(16000).putInt(32000).putShort((short)2).putShort((short)16)
                .put("data".getBytes()).putInt(-1);
        try (var file = new RandomAccessFile(audio.toFile(), "rw")) {
            file.setLength(32080);
            file.write(h.array());
        }
        assertTrue(cz.svamberk.transcriber.audio.PreparedWav.matches(audio, 1));
        try (var file = new RandomAccessFile(audio.toFile(), "rw")) { file.setLength(100); }
        assertFalse(cz.svamberk.transcriber.audio.PreparedWav.matches(audio, 1));
    }

    @Test void supervisorClosesRunningProcess() throws Exception {
        Process process;
        try (var supervisor = new ProcessSupervisor()) {
            process = new ProcessRunner(supervisor).start(List.of("/usr/bin/python3", "-c",
                    "import time; time.sleep(60)"), temp.resolve("child.log"));
            assertTrue(process.isAlive());
        }
        assertFalse(process.isAlive());
    }

    static void wav(Path path, int seconds) throws IOException {
        long length = seconds * 32000L;
        var h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        h.put("RIFF".getBytes()).putInt((int)length+36).put("WAVEfmt ".getBytes()).putInt(16)
                .putShort((short)1).putShort((short)1).putInt(16000).putInt(32000)
                .putShort((short)2).putShort((short)16).put("data".getBytes()).putInt((int)length);
        try (var file = new RandomAccessFile(path.toFile(), "rw")) {
            file.setLength(length+44);
            file.write(h.array());
        }
    }
}
