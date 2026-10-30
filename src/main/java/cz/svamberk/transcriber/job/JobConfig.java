package cz.svamberk.transcriber.job;

import java.nio.file.Path;
import java.util.Map;

public record JobConfig(Path modelDir, String modelSha256, String device, String computeType, String language,
                        int beamSize, double temperature, boolean conditionOnPreviousText, boolean vadFilter,
                        boolean wordTimestamps, int targetSeconds, int searchSeconds, double overlapSeconds,
                        Map<String, String> engineVersions, String initialPrompt, String hotwords) {
    /** Retains the original unprompted configuration for callers and legacy fixtures. */
    public JobConfig(Path modelDir, String modelSha256, String device, String computeType, String language,
                     int beamSize, double temperature, boolean conditionOnPreviousText, boolean vadFilter,
                     boolean wordTimestamps, int targetSeconds, int searchSeconds, double overlapSeconds,
                     Map<String, String> engineVersions) {
        this(modelDir, modelSha256, device, computeType, language, beamSize, temperature,
                conditionOnPreviousText, vadFilter, wordTimestamps, targetSeconds, searchSeconds,
                overlapSeconds, engineVersions, null, null);
    }
}
