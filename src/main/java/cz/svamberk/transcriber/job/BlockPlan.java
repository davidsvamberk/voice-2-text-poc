package cz.svamberk.transcriber.job;

import java.util.List;

public record BlockPlan(int schemaVersion, double duration, List<Block> blocks) {}
