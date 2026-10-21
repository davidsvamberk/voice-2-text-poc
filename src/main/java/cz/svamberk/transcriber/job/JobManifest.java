package cz.svamberk.transcriber.job;

public record JobManifest(int schemaVersion, String inputSha256, JobConfig config, String configSha256) {}
