package cz.svamberk.transcriber.audio;

import cz.svamberk.transcriber.job.Block;
import cz.svamberk.transcriber.job.BlockPlan;
import java.util.ArrayList;
import java.util.List;

public final class BlockPlanner {
    public static final int DEFAULT_TARGET_SECONDS = 600;
    public static final int DEFAULT_SEARCH_SECONDS = 30;
    public static final double DEFAULT_OVERLAP_SECONDS = 2;
    private final int targetSeconds;
    private final int searchSeconds;
    private final double halfOverlap;

    public BlockPlanner() {
        this(DEFAULT_TARGET_SECONDS, DEFAULT_SEARCH_SECONDS, DEFAULT_OVERLAP_SECONDS);
    }

    public BlockPlanner(int targetSeconds, int searchSeconds, double overlapSeconds) {
        if (targetSeconds <= 0 || searchSeconds < 0 || searchSeconds >= targetSeconds
                || !Double.isFinite(overlapSeconds) || overlapSeconds < 0
                || overlapSeconds / 2 >= targetSeconds - searchSeconds) {
            throw new IllegalArgumentException("Invalid block planning settings");
        }
        this.targetSeconds = targetSeconds;
        this.searchSeconds = searchSeconds;
        this.halfOverlap = overlapSeconds / 2;
    }

    public BlockPlan plan(double duration, List<SilenceInterval> silences) {
        if (!Double.isFinite(duration) || duration <= 0) {
            throw new IllegalArgumentException("Audio duration must be finite and positive");
        }
        var blocks = new ArrayList<Block>();
        double base = 0;
        while (duration - base > targetSeconds) {
            double target = base + targetSeconds;
            double boundary = target;
            double bestDistance = Double.POSITIVE_INFINITY;
            for (var silence : silences) {
                double midpoint = silence.start() + (silence.end() - silence.start()) / 2;
                double distance = Math.abs(midpoint - target);
                if (distance <= searchSeconds && (distance < bestDistance
                        || (distance == bestDistance && midpoint < boundary))) {
                    boundary = midpoint;
                    bestDistance = distance;
                }
            }
            boundary = Math.min(boundary, duration);
            // An overlap already covering EOF must not create a redundant, unpersistable final block.
            if (boundary + halfOverlap >= duration) boundary = duration;
            blocks.add(new Block(blocks.size(), Math.max(0, base - halfOverlap), Math.min(duration, boundary + halfOverlap)));
            base = boundary;
        }
        if (base < duration) {
            blocks.add(new Block(blocks.size(), Math.max(0, base - halfOverlap), duration));
        }
        return new BlockPlan(1, duration, List.copyOf(blocks));
    }
}
