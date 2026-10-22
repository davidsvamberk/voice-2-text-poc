package cz.svamberk.transcriber;

import cz.svamberk.transcriber.cli.CliArguments;
import cz.svamberk.transcriber.cli.TranscriberCommand;
import cz.svamberk.transcriber.config.RuntimeSettings;
import cz.svamberk.transcriber.job.TranscriptionJob;
import tools.jackson.databind.ObjectMapper;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@EnableConfigurationProperties(RuntimeSettings.class)
public class TranscriberApplication {
    public static void main(String[] args) {
        var command = new TranscriberCommand();
        final CliArguments arguments;
        try {
            arguments = CliArguments.parse(args);
        } catch (IllegalArgumentException e) {
            System.exit(command.execute(args));
            return;
        }
        if (arguments.help()) {
            System.exit(command.execute(args));
            return;
        }
        int exitCode;
        try (var context = new SpringApplicationBuilder(TranscriberApplication.class)
                .web(WebApplicationType.NONE).run()) {
            exitCode = context.getBean(TranscriberCommand.class).execute(args);
        } catch (RuntimeException e) {
            System.err.println("Cannot run transcriber: " + e.getMessage());
            exitCode = 1;
        }
        System.exit(exitCode);
    }

    @Bean
    TranscriberCommand transcriberCommand(ObjectMapper mapper, RuntimeSettings settings) {
        return new TranscriberCommand(System.out, System.err, new TranscriptionJob(mapper), settings);
    }
}
