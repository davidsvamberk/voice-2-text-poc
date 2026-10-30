import cz.svamberk.transcriber.audio.BlockPlanner;
import cz.svamberk.transcriber.audio.SilenceInterval;
import cz.svamberk.transcriber.job.*;
import cz.svamberk.transcriber.output.TranscriptAssembler;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.Path;
import java.util.List;

/** Experiment bridge: reuse production planning and merging instead of approximating them in Python. */
public class Bridge {
    public record Input(JobManifest manifest, BlockPlan plan, List<BlockResult> results) {}
    public static void main(String[] args) throws Exception {
        var mapper = JsonMapper.builder().build();
        if (args[0].equals("plan")) {
            var silences = mapper.readValue(Path.of(args[1]).toFile(), SilenceInterval[].class);
            var plan = new BlockPlanner(Integer.parseInt(args[3]), 1, 2)
                    .plan(Double.parseDouble(args[2]), List.of(silences));
            mapper.writeValue(Path.of(args[4]).toFile(), plan);
        } else {
            var input = mapper.readValue(Path.of(args[1]).toFile(), Input.class);
            mapper.writeValue(Path.of(args[2]).toFile(),
                    new TranscriptAssembler().assemble(input.manifest(), input.plan(), input.results()));
        }
    }
}
