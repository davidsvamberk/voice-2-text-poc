package cz.svamberk.transcriber.config;

import cz.svamberk.transcriber.TranscriberApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeSettingsTest {
    private RuntimeSettings settings(int target, int search, double overlap, String prompt, String hotwords) {
        var p = Path.of("path");
        return new RuntimeSettings(p, p, p, p, p, target, search, overlap, false, prompt, hotwords);
    }

    @Test void bindsChunkingAndHintsFromSpringProperties() {
        try (var context = new SpringApplicationBuilder(TranscriberApplication.class)
                .web(WebApplicationType.NONE).run("--transcriber.target-seconds=20",
                        "--transcriber.search-seconds=0", "--transcriber.overlap-seconds=1.5",
                        "--transcriber.condition-on-previous-text=false", "--transcriber.initial-prompt=Kontext.",
                        "--transcriber.hotwords=pedagogická intervence")) {
            var s = context.getBean(RuntimeSettings.class);
            assertEquals(20, s.targetSeconds());
            assertEquals(0, s.searchSeconds());
            assertEquals(1.5, s.overlapSeconds());
            assertFalse(s.conditionOnPreviousText());
            assertEquals("Kontext.", s.initialPrompt());
            assertEquals("pedagogická intervence", s.hotwords());
        }
    }

    @Test void defaultsToMeasuredMinuteProfileAndAllowsDisablingHints() {
        try (var context = new SpringApplicationBuilder(TranscriberApplication.class)
                .web(WebApplicationType.NONE).run()) {
            var s = context.getBean(RuntimeSettings.class);
            assertEquals(60, s.targetSeconds());
            assertEquals(1, s.searchSeconds());
            assertEquals(2.0, s.overlapSeconds());
            assertFalse(s.conditionOnPreviousText());
            assertEquals(RuntimeSettings.DEFAULT_INITIAL_PROMPT, s.initialPrompt());
            assertNull(s.hotwords());
        }
        try (var context = new SpringApplicationBuilder(TranscriberApplication.class)
                .web(WebApplicationType.NONE).run("--transcriber.initial-prompt=", "--transcriber.hotwords=")) {
            assertNull(context.getBean(RuntimeSettings.class).initialPrompt());
            assertNull(context.getBean(RuntimeSettings.class).hotwords());
        }
    }

    @Test void blankHintsCanBeDisabledAndNonblankHintsAreTrimmed() {
        assertNull(settings(20, 1, 2, "  ", "").initialPrompt());
        assertNull(settings(20, 1, 2, "  ", "").hotwords());
        assertEquals("kontext", settings(20, 1, 2, " kontext ", "slovo").initialPrompt());
    }

    @Test void rejectsInvalidChunkSettingsBeforeStartingAJob() {
        assertThrows(IllegalArgumentException.class, () -> settings(0, 0, 2, null, null));
        assertThrows(IllegalArgumentException.class, () -> settings(20, -1, 2, null, null));
        assertThrows(IllegalArgumentException.class, () -> settings(20, 20, 2, null, null));
        assertThrows(IllegalArgumentException.class, () -> settings(20, 1, -1, null, null));
        assertThrows(IllegalArgumentException.class, () -> settings(20, 1, 38, null, null));
        assertThrows(IllegalArgumentException.class, () -> settings(20, 1, Double.NaN, null, null));
    }
}
