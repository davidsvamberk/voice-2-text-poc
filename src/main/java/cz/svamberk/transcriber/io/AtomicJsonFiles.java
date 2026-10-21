package cz.svamberk.transcriber.io;

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;

public final class AtomicJsonFiles {
    private final ObjectMapper mapper;

    public AtomicJsonFiles(ObjectMapper mapper) { this.mapper = mapper; }

    public void write(Path target, Object value) throws IOException {
        Path absolute = target.toAbsolutePath();
        Files.createDirectories(absolute.getParent());
        Path temporary = Files.createTempFile(absolute.getParent(), absolute.getFileName() + ".", ".tmp");
        try {
            byte[] json;
            try { json = mapper.writeValueAsBytes(value); }
            catch (RuntimeException e) { throw new IOException("Cannot serialize JSON: " + target, e); }
            try (var channel = FileChannel.open(temporary, WRITE)) {
                var buffer = ByteBuffer.wrap(json);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            Files.move(temporary, absolute, ATOMIC_MOVE, REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
