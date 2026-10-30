package cz.svamberk.transcriber.job;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class JobIdentityTest {
    private JobConfig config(String prompt, String hotwords) {
        return new JobConfig(Path.of("/model"), "model-hash", "cuda", "float16", "cs", 5, 0,
                false, false, true, 20, 1, 2, Map.of("engine", "1"), prompt, hotwords);
    }

    @Test void includesBothHintMechanismsInCheckpointIdentity() throws Exception {
        var noHints = JobIdentity.configHash(config(null, null));
        var first = JobIdentity.configHash(config("Kontext.", null));
        var repeated = JobIdentity.configHash(config(null, "první"));
        assertNotEquals(noHints, first);
        assertNotEquals(noHints, repeated);
        assertNotEquals(repeated, JobIdentity.configHash(config(null, "druhý")));
    }

    @Test void deserializesLegacyConfigWithoutAddingNewHints() throws Exception {
        var legacy = new JobConfig(Path.of("/model"), "model-hash", "cuda", "float16", "cs", 5, 0,
                true, false, true, 600, 30, 2, Map.of("engine", "1"));
        var mapper = JsonMapper.builder().build();
        var tree = mapper.valueToTree(legacy);
        ((tools.jackson.databind.node.ObjectNode) tree).remove("initialPrompt");
        ((tools.jackson.databind.node.ObjectNode) tree).remove("hotwords");
        var decoded = mapper.treeToValue(tree, JobConfig.class);
        assertNull(decoded.initialPrompt());
        assertNull(decoded.hotwords());
        assertTrue(decoded.conditionOnPreviousText());
        assertEquals(JobIdentity.configHash(legacy), JobIdentity.configHash(decoded));
        assertEquals("c70b1716293d0be95cada73718f94b8c9c105fe690f5aa49b7260d3d5e058ed8",
                JobIdentity.configHash(decoded));
    }
}
