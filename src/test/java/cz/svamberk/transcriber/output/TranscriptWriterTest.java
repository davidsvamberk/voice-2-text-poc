package cz.svamberk.transcriber.output;

import cz.svamberk.transcriber.job.Workspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.SerializationFeature;

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
            var lines = Files.readAllLines(workspace.path().resolve("transcript.jsonl"));
            assertEquals(5, lines.size());
            var metadata = mapper.readTree(lines.getFirst());
            assertEquals("metadata", metadata.get("type").asString());
            assertEquals(1, metadata.get("schemaVersion").asInt());
            assertEquals("input-hash", metadata.get("inputSha256").asString());
            assertEquals(mapper.valueToTree(TranscriptAssemblerTest.CONFIG), metadata.get("config"));
            var first = mapper.readTree(lines.get(1));
            assertEquals(mapper.readTree("""
                    {"type":"segment","start":599.0,"end":600.0,"text":" Příliš žluťoučký!","blockIds":[0,1]}
                    """), first);
            assertEquals("  ", mapper.readTree(lines.get(2)).get("text").asString());
            assertEquals("Kůň.", mapper.readTree(lines.get(3)).get("text").asString());
            assertEquals(mapper.readTree("""
                    {"type":"warning","leftBlockId":0,"rightBlockId":1,"code":"AMBIGUOUS_OVERLAP"}
                    """), mapper.readTree(lines.get(4)));
            assertTrue(Files.readString(workspace.path().resolve("transcript.jsonl")).endsWith("\n"));
            assertFalse(Files.exists(workspace.path().resolve("transcript.json")));
            writer.write(workspace, transcript(List.of()));
            assertEquals("", Files.readString(workspace.path().resolve("transcript.txt")));
            assertEquals(List.of("metadata", "warning"), Files.readAllLines(workspace.path().resolve("transcript.jsonl"))
                    .stream().map(line -> mapper.readTree(line).get("type").asString()).toList());
            try (var paths = Files.list(workspace.path())) {
                assertTrue(paths.noneMatch(p -> p.toString().endsWith(".tmp")));
            }
        }
    }

    @Test
    void keepsEachRecordOnOneLineEvenWithPrettyMapperAndEmbeddedNewlines() throws Exception {
        var pretty = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
        try (var workspace = Workspace.open(Optional.of(temp.resolve("work")))) {
            new TranscriptWriter(pretty).write(workspace,
                    transcript(List.of(new TranscriptSegment(0, 1, "Řekl: \"ahoj\".\nDalší řádek.\\", List.of(0)))));
            var lines = Files.readAllLines(workspace.path().resolve("transcript.jsonl"));
            assertEquals(3, lines.size());
            assertEquals("Řekl: \"ahoj\".\nDalší řádek.\\", mapper.readTree(lines.get(1)).get("text").asString());
            new TranscriptWriter(pretty).write(workspace,
                    new Transcript(1, "input-hash", TranscriptAssemblerTest.CONFIG, List.of(), List.of()));
            assertEquals(1, Files.readAllLines(workspace.path().resolve("transcript.jsonl")).size());
        }
    }

    @Test
    void propagatesOutputFailureAndCleansTemporaryFiles() throws Exception {
        try (var workspace = Workspace.open(Optional.of(temp.resolve("work")))) {
            Files.createDirectory(workspace.path().resolve("transcript.jsonl"));
            assertThrows(IOException.class, () -> writer.write(workspace, transcript(List.of())));
            Files.delete(workspace.path().resolve("transcript.jsonl"));
            Files.deleteIfExists(workspace.path().resolve("transcript.txt"));
            Files.createDirectory(workspace.path().resolve("transcript.txt"));
            assertThrows(IOException.class, () -> writer.write(workspace, transcript(List.of())));
            try (var paths = Files.list(workspace.path())) {
                assertTrue(paths.noneMatch(p -> p.toString().endsWith(".tmp")));
            }
        }
    }
}
