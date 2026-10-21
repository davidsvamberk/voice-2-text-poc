package cz.svamberk.transcriber.cli;

import java.nio.file.Path;
import java.util.Optional;

public record CliArguments(Path input, Optional<Path> workDir, boolean help) {
    public static CliArguments parse(String[] args) {
        Path input = null;
        Path workDir = null;
        boolean help = false;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--help" -> {
                    if (help) {
                        throw new IllegalArgumentException("Repeated option: --help");
                    }
                    help = true;
                }
                case "--work-dir" -> {
                    if (workDir != null) {
                        throw new IllegalArgumentException("Repeated option: --work-dir");
                    }
                    if (++i == args.length || args[i].startsWith("-")) {
                        throw new IllegalArgumentException("--work-dir requires DIR");
                    }
                    workDir = path(args[i]);
                }
                default -> {
                    if (arg.startsWith("-")) {
                        throw new IllegalArgumentException("Unknown option: " + arg);
                    }
                    if (input != null) {
                        throw new IllegalArgumentException("Only one INPUT is allowed");
                    }
                    input = path(arg);
                }
            }
        }
        if (input == null && !help) {
            throw new IllegalArgumentException("INPUT is required");
        }
        return new CliArguments(input, Optional.ofNullable(workDir), help);
    }

    private static Path path(String value) {
        if (value.isBlank()) {
            throw new IllegalArgumentException("Path must not be empty");
        }
        return Path.of(value);
    }
}
