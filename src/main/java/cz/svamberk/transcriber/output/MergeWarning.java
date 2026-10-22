package cz.svamberk.transcriber.output;

public record MergeWarning(int leftBlockId, int rightBlockId, String code) {}
