package cz.svamberk.transcriber.output;

import cz.svamberk.transcriber.io.AtomicJsonFiles;
import cz.svamberk.transcriber.job.Workspace;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;

public final class TranscriptWriter {
    private final AtomicJsonFiles jsonFiles;

    public TranscriptWriter(ObjectMapper mapper) {
        jsonFiles = new AtomicJsonFiles(mapper);
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
        jsonFiles.write(workspace.path().resolve("transcript.json"), transcript);
    }
}
