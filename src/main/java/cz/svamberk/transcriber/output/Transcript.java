package cz.svamberk.transcriber.output;

import cz.svamberk.transcriber.job.JobConfig;
import java.util.List;

public record Transcript(int schemaVersion, String inputSha256, JobConfig config,
                         List<TranscriptSegment> segments, List<MergeWarning> warnings) {}
