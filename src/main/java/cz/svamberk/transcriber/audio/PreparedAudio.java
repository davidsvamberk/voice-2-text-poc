package cz.svamberk.transcriber.audio;

import java.nio.file.Path;
import java.util.List;

public record PreparedAudio(Path path, double duration, List<SilenceInterval> silences) {
    public PreparedAudio { silences = List.copyOf(silences); }
}
