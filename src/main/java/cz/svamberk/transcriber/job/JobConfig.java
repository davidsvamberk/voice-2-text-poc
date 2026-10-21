package cz.svamberk.transcriber.job;

import java.nio.file.Path;
import java.util.Map;

public record JobConfig(Path modelDir, String modelSha256, String device, String computeType, String language,
                        int beamSize, double temperature, boolean conditionOnPreviousText, boolean vadFilter,
                        boolean wordTimestamps, int targetSeconds, int searchSeconds, double overlapSeconds,
                        Map<String, String> engineVersions) {}
