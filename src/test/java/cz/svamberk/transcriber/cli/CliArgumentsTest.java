package cz.svamberk.transcriber.cli;

import cz.svamberk.transcriber.TranscriberApplication;
import cz.svamberk.transcriber.config.RuntimeSettings;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CliArgumentsTest {
    @Test
    void parsesInputAndOptionalWorkDir() {
        var args = CliArguments.parse(new String[]{"přednáška 1.mp3", "--work-dir", "práce"});
        assertEquals(Path.of("přednáška 1.mp3"), args.input());
        assertEquals(Path.of("práce"), args.workDir().orElseThrow());
        assertFalse(args.help());
        assertTrue(CliArguments.parse(new String[]{"lecture.mp3"}).workDir().isEmpty());
        assertEquals(Path.of("práce"), CliArguments.parse(
                new String[]{"--work-dir", "práce", "přednáška 1.mp3"}).workDir().orElseThrow());
    }

    @Test
    void rejectsMissingInputAndUnknownOption() {
        assertThrows(IllegalArgumentException.class, () -> CliArguments.parse(new String[]{}));
        assertThrows(IllegalArgumentException.class, () -> CliArguments.parse(new String[]{"--unknown"}));
        assertThrows(IllegalArgumentException.class, () -> CliArguments.parse(
                new String[]{"lecture.mp3", "--transcriber.python=python"}));
    }

    @Test
    void rejectsRepeatedOptionsExtraInputsAndMissingValues() {
        String[][] invalid = {
                {"lecture.mp3", "second.mp3"},
                {"lecture.mp3", "--work-dir"},
                {"lecture.mp3", "--work-dir", "--help"},
                {"lecture.mp3", "--work-dir", "one", "--work-dir", "two"},
                {"--help", "--help"},
                {"--help", "--unknown"},
                {""},
                {"lecture.mp3", "--work-dir", ""},
                {"bad\0path.mp3"}
        };
        for (String[] args : invalid) {
            assertThrows(IllegalArgumentException.class, () -> CliArguments.parse(args),
                    () -> java.util.Arrays.toString(args));
        }
    }

    @Test
    void helpNeedsNoInput() {
        var args = CliArguments.parse(new String[]{"--help"});
        assertTrue(args.help());
        assertTrue(args.workDir().isEmpty());
    }

    @Test
    void commandReturnsHelpAndArgumentExitCodes() {
        var output = new ByteArrayOutputStream();
        var errors = new ByteArrayOutputStream();
        var command = new TranscriberCommand(new PrintStream(output, true, StandardCharsets.UTF_8),
                new PrintStream(errors, true, StandardCharsets.UTF_8));
        assertEquals(0, command.execute(new String[]{"--help"}));
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("INPUT [--work-dir DIR]"));
        assertEquals("", errors.toString(StandardCharsets.UTF_8));
        assertEquals(2, command.execute(new String[]{}));
        assertTrue(errors.toString(StandardCharsets.UTF_8).contains("INPUT"));
        assertEquals(2, command.execute(new String[]{"--unknown"}));
        output.reset();
        assertEquals(1, command.execute(new String[]{"přednáška 1.mp3"}));
    }

    @Test
    void contextBindsSettingsWithoutStartingTranscription() {
        try (var context = new SpringApplicationBuilder(TranscriberApplication.class)
                .web(WebApplicationType.NONE).run()) {
            var settings = context.getBean(RuntimeSettings.class);
            assertEquals(Path.of(".venv/bin/python"), settings.python());
            assertEquals(Path.of("worker"), settings.workerDir());
            assertEquals(Path.of("models/large-v3"), settings.modelDir());
            assertEquals(Path.of("ffmpeg"), settings.ffmpeg());
            assertEquals(Path.of("ffprobe"), settings.ffprobe());
        }
        try (var context = new SpringApplicationBuilder(TranscriberApplication.class)
                .web(WebApplicationType.NONE).run("--transcriber.model-dir=modely/velký model")) {
            assertEquals(Path.of("modely/velký model"), context.getBean(RuntimeSettings.class).modelDir());
        }
    }
}
