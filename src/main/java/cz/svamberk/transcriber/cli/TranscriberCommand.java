package cz.svamberk.transcriber.cli;

import cz.svamberk.transcriber.config.RuntimeSettings;
import cz.svamberk.transcriber.job.TranscriptionJob;

import java.io.IOException;
import java.io.PrintStream;

public class TranscriberCommand {
    private static final String USAGE = "Usage: java -jar target/transcriber.jar INPUT [--work-dir DIR]";
    private final PrintStream out;
    private final PrintStream err;
    private final TranscriptionJob job;
    private final RuntimeSettings settings;

    public TranscriberCommand() {
        this(System.out, System.err);
    }

    public TranscriberCommand(PrintStream out, PrintStream err) {
        this(out, err, null, null);
    }

    public TranscriberCommand(PrintStream out, PrintStream err, TranscriptionJob job, RuntimeSettings settings) {
        this.out = out;
        this.err = err;
        this.job = job;
        this.settings = settings;
    }

    public int execute(String[] args) {
        final CliArguments arguments;
        try {
            arguments = CliArguments.parse(args);
        } catch (IllegalArgumentException e) {
            err.println(e.getMessage());
            err.println(USAGE);
            return 2;
        }
        if (arguments.help()) {
            out.println(USAGE);
            out.println("  --work-dir DIR  Use this working directory");
            out.println("  --help          Show this help without runtime dependencies");
            return 0;
        }
        if (job == null || settings == null) {
            err.println("Transcription runtime is not configured");
            return 1;
        }
        try {
            var directory = job.run(arguments, settings);
            out.println("Saved transcript.txt and transcript.jsonl in: " + directory);
            return 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            err.println("Transcription interrupted");
            resume(arguments);
            return 130;
        } catch (IOException | RuntimeException e) {
            err.println("Transcription failed: " + e.getMessage());
            resume(arguments);
            return 1;
        }
    }

    private void resume(CliArguments args) {
        job.workDirectory().ifPresent(path -> {
            err.println("Workspace preserved: " + path);
            err.println("Resume: java -jar target/transcriber.jar " + quote(args.input().toString()) + " --work-dir " + quote(path.toString()));
        });
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }
}
