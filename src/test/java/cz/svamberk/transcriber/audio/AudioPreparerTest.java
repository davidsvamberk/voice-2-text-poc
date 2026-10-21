package cz.svamberk.transcriber.audio;

import cz.svamberk.transcriber.job.Workspace;
import cz.svamberk.transcriber.process.ProcessResult;
import cz.svamberk.transcriber.process.ProcessRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class AudioPreparerTest {
    @TempDir Path temp;

    @Test void passesUnicodeAndSpacesAsSingleArguments() throws Exception {
        var runner = new FakeRunner();
        Path input = temp.resolve("přednáška s mezerou.mp4");
        try (var ws = Workspace.open(Optional.of(temp.resolve("pracovní adresář")))) {
            Files.writeString(ws.path().resolve("audio.wav.tmp"), "stale partial");
            var audio = new AudioPreparer(runner, "ffmpeg", "ffprobe").prepare(input, ws);
            assertEquals(ws.path().resolve("audio.wav"), audio.path());
            assertEquals("converted audio", Files.readString(audio.path()));
            assertFalse(Files.exists(ws.path().resolve("audio.wav.tmp")));
            assertEquals(1200, audio.duration());
            assertEquals(List.of(new SilenceInterval(598, 602), new SilenceInterval(1198, 1200)), audio.silences());
            var conversion = runner.commands.stream().filter(c -> c.contains("pcm_s16le")).findFirst().orElseThrow();
            assertEquals(input.toString(), conversion.get(conversion.indexOf("-i") + 1));
            assertTrue(conversion.containsAll(List.of("-nostdin", "-y", "-vn", "-rf64", "auto")));
            assertEquals("0:a:0", conversion.get(conversion.indexOf("-map") + 1));
            assertEquals("1", conversion.get(conversion.indexOf("-ac") + 1));
            assertEquals("16000", conversion.get(conversion.indexOf("-ar") + 1));
            assertEquals("wav", conversion.get(conversion.indexOf("-f") + 1));
        }
    }
    @Test void failsWhenFfmpegFails() throws Exception {
        var runner = new FakeRunner(); runner.conversionExit = 1;
        try (var ws = Workspace.open(Optional.of(temp.resolve("work")))) {
            assertThrows(IOException.class, () -> new AudioPreparer(runner, "ffmpeg", "ffprobe").prepare(temp.resolve("in"), ws));
            assertFalse(Files.exists(ws.path().resolve("audio.wav")));
        }
    }
    @Test void rejectsInvalidProbedDuration() throws Exception {
        for (String duration : List.of("0", "-1", "NaN", "Infinity", "N/A")) {
            var runner = new FakeRunner(); runner.duration = duration;
            try (var ws = Workspace.open(Optional.of(temp.resolve("work" + duration)))) {
                assertThrows(IOException.class, () -> new AudioPreparer(runner, "ffmpeg", "ffprobe").prepare(temp.resolve("in"), ws));
            }
        }
    }
    @Test void failsBeforeConversionWhenDependencyProbeFails() throws Exception {
        var runner = new FakeRunner(); runner.probeExit = 1;
        try (var ws = Workspace.open(Optional.of(temp.resolve("work")))) {
            assertThrows(IOException.class, () -> new AudioPreparer(runner, "ffmpeg", "ffprobe").prepare(temp.resolve("in"), ws));
            assertTrue(runner.commands.stream().noneMatch(c -> c.contains("pcm_s16le")));
        }
    }
    @Test void failsWhenSilenceDetectionFails() throws Exception {
        var runner = new FakeRunner(); runner.silenceExit = 1;
        try (var ws = Workspace.open(Optional.of(temp.resolve("work")))) {
            assertThrows(IOException.class, () -> new AudioPreparer(runner, "ffmpeg", "ffprobe").prepare(temp.resolve("in"), ws));
        }
    }
    private static class FakeRunner extends ProcessRunner {
        final List<List<String>> commands = new ArrayList<>();
        int conversionExit, probeExit, silenceExit;
        String duration = "1200";
        @Override public ProcessResult run(List<String> command, Path stderrLog) throws IOException {
            commands.add(List.copyOf(command));
            Files.createDirectories(stderrLog.getParent());
            if (command.contains("-version")) return new ProcessResult(probeExit, "version fixture");
            if (command.contains("pcm_s16le")) {
                Files.writeString(Path.of(command.getLast()), "converted audio");
                return new ProcessResult(conversionExit, "");
            }
            if (command.contains("-show_entries")) return new ProcessResult(0, duration + "\n");
            assertTrue(command.contains("silencedetect=noise=-35dB:d=0.5"));
            assertEquals("-", command.getLast());
            Files.writeString(stderrLog, "[silencedetect @ 0] silence_start: 598\n"
                    + "[silencedetect @ 0] silence_end: 602 | silence_duration: 4\n"
                    + "unrelated diagnostic\n[silencedetect @ 0] silence_start: 1198\n");
            return new ProcessResult(silenceExit, "");
        }
    }
}
