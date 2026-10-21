package cz.svamberk.transcriber.cli;

import java.io.PrintStream;

public class TranscriberCommand {
    private static final String USAGE = "Usage: java -jar target/transcriber.jar INPUT [--work-dir DIR]";
    private final PrintStream out;
    private final PrintStream err;

    public TranscriberCommand() {
        this(System.out, System.err);
    }

    public TranscriberCommand(PrintStream out, PrintStream err) {
        this.out = out;
        this.err = err;
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
        // Task 6 connects orchestration here; context creation never starts work.
        out.printf("CLI configured for input: %s. Transcription is not connected yet.%n", arguments.input());
        return 0;
    }
}
