package cz.svamberk.transcriber.output;

import cz.svamberk.transcriber.job.Workspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class TranscriptWriterTest {
    @TempDir Path temp;
    final JsonMapper mapper = JsonMapper.builder().build();
    final TranscriptWriter writer = new TranscriptWriter(mapper);

    Transcript transcript(List<TranscriptSegment> segments) {
        return new Transcript(1, "input-hash", TranscriptAssemblerTest.CONFIG, segments,
                List.of(new MergeWarning(0, 1, "AMBIGUOUS_OVERLAP")));
    }

    @Test
    void writesUtf8AndEmptyTranscript() throws Exception {
        try (var workspace = Workspace.open(Optional.of(temp.resolve("český adresář")))) {
            var transcript = transcript(List.of(new TranscriptSegment(599, 600, " Příliš žluťoučký!", List.of(0, 1)),
                    new TranscriptSegment(600, 601, "  ", List.of(1)),
                    new TranscriptSegment(602, 603, "Kůň.", List.of(1))));
            writer.write(workspace, transcript);
            assertEquals(" Příliš žluťoučký!\nKůň.\n", Files.readString(workspace.path().resolve("transcript.txt")));
            assertEquals(transcript, mapper.readValue(workspace.path().resolve("transcript.json").toFile(), Transcript.class));
            writer.write(workspace, transcript(List.of()));
            assertEquals("", Files.readString(workspace.path().resolve("transcript.txt")));
            assertTrue(mapper.readTree(workspace.path().resolve("transcript.json").toFile()).get("segments").isEmpty());
            try (var paths = Files.list(workspace.path())) {
                assertTrue(paths.noneMatch(p -> p.toString().endsWith(".tmp")));
            }
        }
    }

    @Test
    void propagatesOutputFailureAndCleansTemporaryFiles() throws Exception {
        try (var workspace = Workspace.open(Optional.of(temp.resolve("work")))) {
            Files.createDirectory(workspace.path().resolve("transcript.json"));
            assertThrows(IOException.class, () -> writer.write(workspace, transcript(List.of())));
            Files.delete(workspace.path().resolve("transcript.json"));
            Files.deleteIfExists(workspace.path().resolve("transcript.txt"));
            Files.createDirectory(workspace.path().resolve("transcript.txt"));
            assertThrows(IOException.class, () -> writer.write(workspace, transcript(List.of())));
            try (var paths = Files.list(workspace.path())) {
                assertTrue(paths.noneMatch(p -> p.toString().endsWith(".tmp")));
            }
        }
    }
}
