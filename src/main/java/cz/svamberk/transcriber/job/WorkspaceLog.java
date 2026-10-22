package cz.svamberk.transcriber.job;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.core.FileAppender;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Job diagnostics go to disk; the command prints concise progress separately. */
final class WorkspaceLog implements AutoCloseable {
    private final Logger logger;
    private final Level previousLevel;
    private final boolean previousAdditive;
    private final FileAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender;
    private final PatternLayoutEncoder encoder;

    WorkspaceLog(Path workspace) throws IOException {
        Files.createDirectories(workspace.resolve("logs"));
        var context = (LoggerContext) LoggerFactory.getILoggerFactory();
        logger = context.getLogger("cz.svamberk.transcriber");
        previousLevel = logger.getLevel();
        previousAdditive = logger.isAdditive();
        encoder = new PatternLayoutEncoder();
        encoder.setContext(context);
        encoder.setPattern("%date %-5level %logger - %msg%n%ex");
        encoder.start();
        appender = new FileAppender<>();
        appender.setContext(context);
        appender.setName("transcription-job");
        appender.setFile(workspace.resolve("logs/java.log").toString());
        appender.setAppend(true);
        appender.setEncoder(encoder);
        appender.start();
        if (!appender.isStarted()) {
            encoder.stop();
            throw new IOException("Cannot open workspace Java log");
        }
        logger.setLevel(Level.INFO);
        logger.setAdditive(false);
        logger.addAppender(appender);
    }

    @Override public void close() {
        logger.detachAppender(appender);
        appender.stop();
        encoder.stop();
        logger.setLevel(previousLevel);
        logger.setAdditive(previousAdditive);
    }
}
