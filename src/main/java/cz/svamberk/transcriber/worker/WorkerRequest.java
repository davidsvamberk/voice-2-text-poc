package cz.svamberk.transcriber.worker;

import cz.svamberk.transcriber.job.BlockPlan;
import cz.svamberk.transcriber.job.JobConfig;
import java.nio.file.Path;
import java.util.List;

public record WorkerRequest(int schemaVersion, Path workDir, Path modelDir, String inputSha256,
                            String configSha256, JobConfig config, BlockPlan plan, List<Integer> pendingBlockIds) {}
