package cz.svamberk.transcriber.output;

import java.util.List;

public record TranscriptSegment(double start, double end, String text, List<Integer> blockIds) {}
