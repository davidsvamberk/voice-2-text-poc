package cz.svamberk.transcriber.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import cz.svamberk.transcriber.audio.BlockPlanner;

import java.nio.file.Path;

@ConfigurationProperties("transcriber")
public record RuntimeSettings(Path python, Path workerDir, Path modelDir, Path ffmpeg, Path ffprobe,
                              Integer targetSeconds, Integer searchSeconds, Double overlapSeconds,
                              Boolean conditionOnPreviousText, String initialPrompt, String hotwords) {
    public static final String DEFAULT_INITIAL_PROMPT =
            "Pedagogická intervence, speciálně pedagogické centrum, školské poradenské zařízení.";

    @ConstructorBinding
    public RuntimeSettings {
        targetSeconds = targetSeconds == null ? BlockPlanner.DEFAULT_TARGET_SECONDS : targetSeconds;
        searchSeconds = searchSeconds == null ? BlockPlanner.DEFAULT_SEARCH_SECONDS : searchSeconds;
        overlapSeconds = overlapSeconds == null ? BlockPlanner.DEFAULT_OVERLAP_SECONDS : overlapSeconds;
        conditionOnPreviousText = conditionOnPreviousText == null ? false : conditionOnPreviousText;
        initialPrompt = initialPrompt == null ? DEFAULT_INITIAL_PROMPT : blankToNull(initialPrompt);
        hotwords = blankToNull(hotwords);
        new BlockPlanner(targetSeconds, searchSeconds, overlapSeconds);
    }

    public RuntimeSettings(Path python, Path workerDir, Path modelDir, Path ffmpeg, Path ffprobe) {
        this(python, workerDir, modelDir, ffmpeg, ffprobe, null, null, null, null, null, null);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
