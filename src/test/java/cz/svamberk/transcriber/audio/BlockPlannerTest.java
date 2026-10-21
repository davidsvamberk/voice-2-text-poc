package cz.svamberk.transcriber.audio;

import cz.svamberk.transcriber.job.Block;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class BlockPlannerTest {
    private final BlockPlanner planner = new BlockPlanner();

    @Test void plansShortAudio() {
        var plan = planner.plan(12, List.of());
        assertEquals(1, plan.schemaVersion());
        assertEquals(12, plan.duration());
        assertEquals(List.of(new Block(0, 0, 12)), plan.blocks());
    }
    @Test void plansSilenceNearBoundary() {
        assertEquals(List.of(new Block(0, 0, 601), new Block(1, 599, 1200)),
                planner.plan(1200, List.of(new SilenceInterval(598, 602))).blocks());
    }
    @Test void fallsBackWithoutSilence() {
        assertEquals(List.of(new Block(0, 0, 601), new Block(1, 599, 1201), new Block(2, 1199, 1210)),
                planner.plan(1210, List.of()).blocks());
    }
    @Test void choosesEarlierTieAndUsesLastBaseBoundary() {
        assertEquals(List.of(new Block(0, 0, 591), new Block(1, 589, 1191), new Block(2, 1189, 1210)),
                planner.plan(1210, List.of(new SilenceInterval(609, 611), new SilenceInterval(589, 591))).blocks());
    }
    @Test void ignoresSilenceOutsideSearchAndClampsLateBoundary() {
        assertEquals(List.of(new Block(0, 0, 601), new Block(1, 599, 1210)),
                planner.plan(1210, List.of(new SilenceInterval(1208, 1210), new SilenceInterval(649, 651))).blocks());
        assertEquals(List.of(new Block(0, 0, 610)),
                planner.plan(610, List.of(new SilenceInterval(619, 621))).blocks());
    }
    @Test void rejectsInvalidDuration() {
        for (double duration : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> planner.plan(duration, List.of()));
        }
    }
}
