package cz.svamberk.transcriber.audio;

import cz.svamberk.transcriber.job.Block;
import cz.svamberk.transcriber.job.BlockPlan;
import java.util.ArrayList;
import java.util.List;

public final class BlockPlanner {
    public BlockPlan plan(double duration, List<SilenceInterval> silences) {
        if (!Double.isFinite(duration) || duration <= 0) {
            throw new IllegalArgumentException("Audio duration must be finite and positive");
        }
        var blocks = new ArrayList<Block>();
        double base = 0;
        while (duration - base > 600) {
            double target = base + 600;
            double boundary = target;
            double bestDistance = Double.POSITIVE_INFINITY;
            for (var silence : silences) {
                double midpoint = silence.start() + (silence.end() - silence.start()) / 2;
                double distance = Math.abs(midpoint - target);
                if (distance <= 30 && (distance < bestDistance
                        || (distance == bestDistance && midpoint < boundary))) {
                    boundary = midpoint;
                    bestDistance = distance;
                }
            }
            boundary = Math.min(boundary, duration);
            // An overlap already covering EOF must not create a redundant, unpersistable final block.
            if (boundary + 1 >= duration) boundary = duration;
            blocks.add(new Block(blocks.size(), Math.max(0, base - 1), Math.min(duration, boundary + 1)));
            base = boundary;
        }
        if (base < duration) {
            blocks.add(new Block(blocks.size(), Math.max(0, base - 1), duration));
        }
        return new BlockPlan(1, duration, List.copyOf(blocks));
    }
}
