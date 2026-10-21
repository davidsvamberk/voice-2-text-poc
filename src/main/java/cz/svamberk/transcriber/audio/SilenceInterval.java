package cz.svamberk.transcriber.audio;

public record SilenceInterval(double start, double end) {
    public SilenceInterval {
        if (!Double.isFinite(start) || !Double.isFinite(end) || start < 0 || end < start) {
            throw new IllegalArgumentException("Invalid silence interval");
        }
    }
}
