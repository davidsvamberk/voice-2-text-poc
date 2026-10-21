package cz.svamberk.transcriber.job;

import java.util.List;

public record BlockResult(int schemaVersion, String inputSha256, String configSha256, int blockId,
                          List<Segment> segments) {}
