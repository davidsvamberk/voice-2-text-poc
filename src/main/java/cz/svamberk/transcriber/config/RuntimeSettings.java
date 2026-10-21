package cz.svamberk.transcriber.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

@ConfigurationProperties("transcriber")
public record RuntimeSettings(Path python, Path workerDir, Path modelDir, Path ffmpeg, Path ffprobe) {
}
