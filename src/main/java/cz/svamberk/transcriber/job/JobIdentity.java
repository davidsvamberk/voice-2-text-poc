package cz.svamberk.transcriber.job;

import tools.jackson.databind.json.JsonMapper;
import cz.svamberk.transcriber.audio.AudioPreparer;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.TreeMap;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

public final class JobIdentity {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private JobIdentity() {}

    public static JobManifest identify(Path input, JobConfig config) throws IOException {
        Path model = config.modelDir().toAbsolutePath().normalize();
        var effective = new JobConfig(model, modelHash(model), config.device(), config.computeType(),
                config.language(), config.beamSize(), config.temperature(), config.conditionOnPreviousText(),
                config.vadFilter(), config.wordTimestamps(), config.targetSeconds(), config.searchSeconds(),
                config.overlapSeconds(), new TreeMap<>(config.engineVersions()));
        return new JobManifest(1, hex(fileHash(input)), effective, configHash(effective));
    }

    static String configHash(JobConfig config) throws IOException {
        // Explicit effective fields: model location is persisted only as metadata.
        var fields = new TreeMap<String, Object>();
        fields.put("modelSha256", config.modelSha256());
        fields.put("task", "transcribe");
        fields.put("initialPrompt", null);
        fields.put("audioStream", "0:a:0");
        fields.put("audioCodec", "pcm_s16le");
        fields.put("audioChannels", 1);
        fields.put("audioSampleRate", 16000);
        fields.put("audioContainer", "wav/rf64-auto");
        fields.put("audioCopyTimestamps", true);
        fields.put("audioStartAtZero", true);
        fields.put("audioResampleFilter", AudioPreparer.TIMELINE_FILTER);
        fields.put("silenceNoiseDb", -35);
        fields.put("silenceDurationSeconds", 0.5);
        fields.put("device", config.device());
        fields.put("computeType", config.computeType());
        fields.put("language", config.language());
        fields.put("beamSize", config.beamSize());
        fields.put("temperature", config.temperature());
        fields.put("conditionOnPreviousText", config.conditionOnPreviousText());
        fields.put("vadFilter", config.vadFilter());
        fields.put("wordTimestamps", config.wordTimestamps());
        fields.put("targetSeconds", config.targetSeconds());
        fields.put("searchSeconds", config.searchSeconds());
        fields.put("overlapSeconds", config.overlapSeconds());
        fields.put("engineVersions", new TreeMap<>(config.engineVersions()));
        try {
            return hex(sha256().digest(JSON.writeValueAsBytes(fields)));
        } catch (RuntimeException e) {
            throw new IOException("Cannot hash effective configuration", e);
        }
    }

    private static String modelHash(Path root) throws IOException {
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, NOFOLLOW_LINKS)) {
            throw new IOException("Model must be a directory without symlinks: " + root);
        }
        var digest = sha256();
        try (var paths = Files.walk(root);
             var out = new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(), digest))) {
            var entries = paths.sorted(Comparator.comparing(p -> relativeName(root, p))).toList();
            for (Path entry : entries) {
                if (Files.isSymbolicLink(entry)) throw new IOException("Model symlink is not allowed: " + entry);
                if (!Files.isRegularFile(entry, NOFOLLOW_LINKS)) continue;
                byte[] name = relativeName(root, entry).getBytes(StandardCharsets.UTF_8);
                out.writeInt(name.length);
                out.write(name);
                out.writeLong(Files.size(entry));
                out.write(fileHash(entry));
            }
        }
        return hex(digest.digest());
    }

    private static String relativeName(Path root, Path path) {
        return root.relativize(path).toString().replace(java.io.File.separatorChar, '/');
    }

    private static byte[] fileHash(Path path) throws IOException {
        var digest = sha256();
        try (var in = new DigestInputStream(Files.newInputStream(path), digest)) {
            in.transferTo(OutputStream.nullOutputStream());
        }
        return digest.digest();
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static String hex(byte[] bytes) { return HexFormat.of().formatHex(bytes); }
}
