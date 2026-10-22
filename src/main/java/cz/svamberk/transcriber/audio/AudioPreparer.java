package cz.svamberk.transcriber.audio;

import cz.svamberk.transcriber.job.Workspace;
import cz.svamberk.transcriber.process.ProcessResult;
import cz.svamberk.transcriber.process.ProcessRunner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

public final class AudioPreparer {
    // Fill/trim timestamp discontinuities, including sub-100ms gaps, without stretching speech.
    public static final String TIMELINE_FILTER =
            "aresample=16000:async=1:first_pts=0:min_comp=0:min_hard_comp=0";
    private static final Pattern SILENCE = Pattern.compile("silence_(start|end):\\s*([0-9.eE+\\-]+)");
    private final ProcessRunner runner;
    private final String ffmpeg;
    private final String ffprobe;

    public AudioPreparer(ProcessRunner runner, String ffmpeg, String ffprobe) {
        this.runner = Objects.requireNonNull(runner);
        this.ffmpeg = Objects.requireNonNull(ffmpeg);
        this.ffprobe = Objects.requireNonNull(ffprobe);
    }

    public PreparedAudio prepare(Path input, Workspace ws) throws IOException, InterruptedException {
        Path logs = ws.path().resolve("logs");
        Files.createDirectories(logs);
        checked(List.of(ffmpeg, "-version"), logs.resolve("ffmpeg-version.log"));
        checked(List.of(ffprobe, "-version"), logs.resolve("ffprobe-version.log"));
        Path temporary = ws.path().resolve("audio.wav.tmp");
        Path audio = ws.path().resolve("audio.wav");
        // WAV sample zero is the input format's playback start, not the first audio frame.
        checked(List.of(ffmpeg, "-nostdin", "-y", "-copyts", "-start_at_zero", "-i", input.toString(), "-map", "0:a:0", "-vn",
                "-ac", "1", "-ar", "16000", "-af", TIMELINE_FILTER, "-c:a", "pcm_s16le", "-rf64", "auto", "-f", "wav",
                temporary.toString()), logs.resolve("prepare.log"));
        if (!Files.isRegularFile(temporary) || Files.size(temporary) == 0) {
            throw new IOException("FFmpeg produced no audio: " + temporary);
        }
        Files.move(temporary, audio, ATOMIC_MOVE, REPLACE_EXISTING);
        var probe = checked(List.of(ffprobe, "-v", "error", "-show_entries", "format=duration", "-of",
                "default=noprint_wrappers=1:nokey=1", audio.toString()), logs.resolve("ffprobe.log"));
        double duration;
        try {
            duration = Double.parseDouble(probe.stdout().strip());
        } catch (NumberFormatException e) {
            throw new IOException("Invalid audio duration from ffprobe", e);
        }
        if (!Double.isFinite(duration) || duration <= 0) throw new IOException("Audio duration must be finite and positive");
        Path silenceLog = logs.resolve("silence.log");
        checked(List.of(ffmpeg, "-nostdin", "-i", audio.toString(), "-af", "silencedetect=noise=-35dB:d=0.5",
                "-f", "null", "-"), silenceLog);
        return new PreparedAudio(audio, duration, readSilences(silenceLog, duration));
    }

    private ProcessResult checked(List<String> command, Path log) throws IOException, InterruptedException {
        var result = runner.run(command, log);
        if (result.exitCode() != 0) {
            throw new IOException(command.getFirst() + " failed with exit code " + result.exitCode() + "; see " + log);
        }
        return result;
    }

    private static List<SilenceInterval> readSilences(Path log, double duration) throws IOException {
        var silences = new ArrayList<SilenceInterval>();
        Double start = null;
        // FFmpeg writes detection events to stderr. Only one log line is held at a time.
        try (var reader = Files.newBufferedReader(log)) {
            String line;
            while ((line = reader.readLine()) != null) {
                var matcher = SILENCE.matcher(line);
                while (matcher.find()) {
                    double time;
                    try { time = Double.parseDouble(matcher.group(2)); }
                    catch (NumberFormatException e) { throw new IOException("Invalid silence timestamp", e); }
                    if (!Double.isFinite(time) || time < 0) throw new IOException("Invalid silence timestamp");
                    time = Math.min(time, duration);
                    if (matcher.group(1).equals("start")) start = time;
                    else if (start != null) {
                        if (time < start) throw new IOException("Reversed silence timestamps");
                        silences.add(new SilenceInterval(start, time));
                        start = null;
                    }
                }
            }
        }
        if (start != null) silences.add(new SilenceInterval(start, duration));
        return silences;
    }
}
