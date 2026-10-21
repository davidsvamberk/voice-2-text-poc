package cz.svamberk.transcriber.job;

import java.util.List;

public record Segment(double start, double end, String text, List<Word> words) {}
