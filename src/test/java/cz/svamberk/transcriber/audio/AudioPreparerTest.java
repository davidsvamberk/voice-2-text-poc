package cz.svamberk.transcriber.audio;

import cz.svamberk.transcriber.job.Workspace;
import cz.svamberk.transcriber.process.ProcessResult;
import cz.svamberk.transcriber.process.ProcessRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import javax.sound.sampled.AudioSystem;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
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
            assertTrue(conversion.containsAll(List.of("-nostdin", "-y", "-vn", "-rf64", "auto", "-copyts", "-start_at_zero")));
            assertEquals("0:a:0", conversion.get(conversion.indexOf("-map") + 1));
            assertEquals("1", conversion.get(conversion.indexOf("-ac") + 1));
            assertEquals("16000", conversion.get(conversion.indexOf("-ar") + 1));
            assertEquals("wav", conversion.get(conversion.indexOf("-f") + 1));
            assertEquals("aresample=16000:async=1:first_pts=0:min_comp=0:min_hard_comp=0",
                    conversion.get(conversion.indexOf("-af") + 1));
            assertTrue(conversion.indexOf("-copyts") < conversion.indexOf("-i"));
            assertTrue(conversion.indexOf("-start_at_zero") < conversion.indexOf("-i"));
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
    // Lossless, one-second media: compare the actual prepared PCM, including every gap.
    @ParameterizedTest
    @CsvSource({"mp4,0.4,0,0", "mkv,0,0.4,0", "mkv,0,0.05,0", "mkv,0.4,0.05,5", "mkv,0,0,0"})
    void preservesPlaybackTimelineWithRealFfmpeg(String container, double delay, double gap,
                                                double formatStart) throws Exception {
        String ffmpeg = System.getProperty("transcriber.test.ffmpeg", "ffmpeg");
        var runner = new TimelineRunner();
        try {
            assumeTrue(runner.run(List.of(ffmpeg, "-version"), temp.resolve("version.log")).exitCode() == 0,
                    "Optional timeline regression requires FFmpeg");
        } catch (IOException e) {
            assumeTrue(false, "Optional timeline regression requires FFmpeg: " + e.getMessage());
        }
        Path source = temp.resolve("tone.wav");
        assertEquals(0, runner.run(List.of(ffmpeg, "-nostdin", "-y", "-f", "lavfi", "-i",
                "sine=frequency=1000:sample_rate=16000:duration=0.4", "-c:a", "pcm_s16le",
                source.toString()), temp.resolve("tone.log")).exitCode());
        byte[] tone = pcm(source);
        assertEquals(12800, tone.length);
        Path input = temp.resolve("original." + container);
        String timestamps = "[1:a]asetnsamples=n=160,asetpts='PTS+" + delay
                + "/TB+gte(T,0.2)*" + gap + "/TB'[a]";
        assertEquals(0, runner.run(List.of(ffmpeg, "-nostdin", "-y", "-f", "lavfi", "-i",
                "color=c=black:s=16x16:r=10:d=1", "-i", source.toString(), "-filter_complex", timestamps,
                "-map", "0:v", "-map", "[a]", "-c:v", "mpeg4", "-c:a",
                container.equals("mp4") ? "alac" : "pcm_s16le", "-output_ts_offset",
                Double.toString(formatStart), input.toString()), temp.resolve("fixture.log")).exitCode());

        try (var ws = Workspace.open(Optional.of(temp.resolve("work")))) {
            var audio = new AudioPreparer(runner, ffmpeg, "fixture-ffprobe").prepare(input, ws);
            byte[] expected = new byte[(int) Math.round((delay + gap + 0.4) * 32000)];
            System.arraycopy(tone, 0, expected, (int) Math.round(delay * 32000), 6400);
            System.arraycopy(tone, 6400, expected, (int) Math.round((delay + gap + 0.2) * 32000), 6400);
            assertEquals(delay + gap + 0.4, audio.duration(), 1.0 / 16000);
            assertArrayEquals(expected, pcm(audio.path()),
                    "Playback-relative padding/gaps and the original tone samples must be preserved");
        }
    }

    private static byte[] pcm(Path path) throws Exception {
        try (var audio = AudioSystem.getAudioInputStream(path.toFile())) {
            assertEquals(16000, audio.getFormat().getSampleRate());
            assertEquals(1, audio.getFormat().getChannels());
            assertEquals(16, audio.getFormat().getSampleSizeInBits());
            return audio.readAllBytes();
        }
    }

    /** Only ffprobe is substituted: conversion and silence detection use the real executable. */
    private static class TimelineRunner extends ProcessRunner {
        @Override public ProcessResult run(List<String> command, Path log) throws IOException, InterruptedException {
            if (!command.getFirst().equals("fixture-ffprobe")) return super.run(command, log);
            if (command.contains("-version")) return new ProcessResult(0, "JDK WAV duration fixture");
            try (var audio = AudioSystem.getAudioInputStream(Path.of(command.getLast()).toFile())) {
                return new ProcessResult(0, Double.toString((double) audio.getFrameLength() / audio.getFormat().getFrameRate()));
            } catch (javax.sound.sampled.UnsupportedAudioFileException e) {
                throw new IOException(e);
            }
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
