package cz.svamberk.transcriber.output;

import cz.svamberk.transcriber.job.Workspace;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.node.ObjectNode;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;

public final class TranscriptWriter {
    private final ObjectMapper mapper;
    private final ObjectWriter compactJson;

    public TranscriptWriter(ObjectMapper mapper) {
        this.mapper = mapper;
        compactJson = mapper.writer().without(SerializationFeature.INDENT_OUTPUT);
    }

    public void write(Workspace workspace, Transcript transcript) throws IOException {
        var text = new StringBuilder();
        for (var segment : transcript.segments()) {
            if (!segment.text().isBlank()) text.append(segment.text()).append('\n');
        }
        var target = workspace.path().resolve("transcript.txt");
        var temporary = Files.createTempFile(workspace.path(), "transcript.txt.", ".tmp");
        try {
            try (var channel = FileChannel.open(temporary, WRITE)) {
                var bytes = ByteBuffer.wrap(text.toString().getBytes(StandardCharsets.UTF_8));
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
            Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
        writeJsonLines(workspace, transcript);
    }

    private void writeJsonLines(Workspace workspace, Transcript transcript) throws IOException {
        var target = workspace.path().resolve("transcript.jsonl");
        var temporary = Files.createTempFile(workspace.path(), "transcript.jsonl.", ".tmp");
        try {
            try (var channel = FileChannel.open(temporary, WRITE);
                 var output = new BufferedWriter(Channels.newWriter(channel, StandardCharsets.UTF_8))) {
                var metadata = mapper.createObjectNode();
                metadata.put("schemaVersion", transcript.schemaVersion());
                metadata.put("inputSha256", transcript.inputSha256());
                metadata.set("config", mapper.valueToTree(transcript.config()));
                writeRecord(output, "metadata", metadata);
                for (var segment : transcript.segments()) {
                    writeRecord(output, "segment", mapper.valueToTree(segment));
                }
                for (var warning : transcript.warnings()) {
                    writeRecord(output, "warning", mapper.valueToTree(warning));
                }
                output.flush();
                channel.force(true);
            } catch (RuntimeException e) {
                throw new IOException("Cannot serialize JSON Lines: " + target, e);
            }
            Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void writeRecord(Writer output, String type, ObjectNode fields) throws IOException {
        var record = mapper.createObjectNode();
        record.put("type", type);
        record.setAll(fields);
        output.write(compactJson.writeValueAsString(record));
        output.write('\n');
    }
}
