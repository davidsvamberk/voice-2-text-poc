package cz.svamberk.transcriber.output;

import cz.svamberk.transcriber.job.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.stream.Collectors;

public final class TranscriptAssembler {
    // Allow only floating-point rounding at the specified time boundaries.
    private static final double EPSILON = 1e-9;

    public Transcript assemble(JobManifest manifest, BlockPlan plan, List<BlockResult> results) {
        var byId = new HashMap<Integer, BlockResult>();
        for (var result : results) byId.put(result.blockId(), result);
        var blocks = plan.blocks().stream()
                .sorted(Comparator.comparingDouble(Block::start).thenComparingInt(Block::id)).toList();
        var segments = new ArrayList<OutputSegment>();
        var byBlock = new HashMap<Integer, List<OutputSegment>>();
        for (var block : blocks) {
            var blockSegments = new ArrayList<OutputSegment>();
            var result = byId.get(block.id());
            if (result != null) {
                for (var segment : result.segments()) {
                    blockSegments.add(new OutputSegment(block, segment, blockSegments.size()));
                }
            }
            byBlock.put(block.id(), blockSegments);
            segments.addAll(blockSegments);
        }
        var warnings = new ArrayList<MergeWarning>();
        for (int i = 0; i < blocks.size(); i++) {
            var left = blocks.get(i);
            for (int j = i + 1; j < blocks.size(); j++) {
                var right = blocks.get(j);
                double start = Math.max(left.start(), right.start());
                double end = Math.min(left.end(), right.end());
                if (start >= end) continue;
                var leftSegments = byBlock.get(left.id());
                var rightSegments = byBlock.get(right.id());
                var leftWords = overlapWords(leftSegments, start, end);
                var rightWords = overlapWords(rightSegments, start, end);
                int matched = matchingLength(leftWords, rightWords);
                for (int k = 0; k < matched; k++) {
                    var retained = leftWords.get(leftWords.size() - matched + k);
                    var removed = rightWords.get(k);
                    removed.removed = true;
                    retained.owner.blockIds.addAll(removed.owner.blockIds);
                }
                boolean wordless = hasWordlessSpeech(leftSegments, start, end)
                        || hasWordlessSpeech(rightSegments, start, end);
                boolean leftSpeech = !leftWords.isEmpty() || hasWordlessSpeech(leftSegments, start, end);
                boolean rightSpeech = !rightWords.isEmpty() || hasWordlessSpeech(rightSegments, start, end);
                if (leftSpeech && rightSpeech && (matched == 0 || wordless)) {
                    warnings.add(new MergeWarning(left.id(), right.id(), "AMBIGUOUS_OVERLAP"));
                }
            }
        }
        var ordered = segments.stream().filter(OutputSegment::survives)
                .sorted(Comparator.comparingDouble((OutputSegment s) -> s.export().start())
                        .thenComparingInt(s -> s.blockId).thenComparingInt(s -> s.order))
                .map(OutputSegment::export).toList();
        return new Transcript(1, manifest.inputSha256(), manifest.config(), ordered, List.copyOf(warnings));
    }

    private static List<OutputWord> overlapWords(List<OutputSegment> segments, double start, double end) {
        return segments.stream().flatMap(s -> s.words.stream())
                .filter(w -> !w.removed && w.midpoint() >= start && w.midpoint() <= end).toList();
    }

    private static boolean hasWordlessSpeech(List<OutputSegment> segments, double start, double end) {
        return segments.stream().anyMatch(s -> s.words.isEmpty() && !s.text.isBlank()
                && s.start < end && s.end > start);
    }

    private static int matchingLength(List<OutputWord> left, List<OutputWord> right) {
        for (int size = Math.min(left.size(), right.size()); size > 0; size--) {
            boolean matches = true;
            for (int i = 0; i < size; i++) {
                var a = left.get(left.size() - size + i);
                var b = right.get(i);
                String normalized = normalize(a.text);
                if (normalized.isEmpty() || !normalized.equals(normalize(b.text))) {
                    matches = false;
                    break;
                }
                if (size == 1) {
                    double shorter = Math.min(a.end - a.start, b.end - b.start);
                    double overlap = Math.min(a.end, b.end) - Math.max(a.start, b.start);
                    matches = a.text.equals(b.text) && shorter > 0 && overlap + EPSILON >= shorter / 2;
                } else if (Math.abs(a.midpoint() - b.midpoint()) > 0.5 + EPSILON) {
                    matches = false;
                    break;
                }
            }
            if (matches) return size;
        }
        return 0;
    }

    private static String normalize(String text) {
        return text.trim().toLowerCase(Locale.ROOT).replaceAll("^[\\p{P}]+|[\\p{P}]+$", "");
    }

    private static final class OutputSegment {
        final double start, end;
        final String text;
        final int blockId, order;
        final TreeSet<Integer> blockIds = new TreeSet<>();
        final List<OutputWord> words;

        OutputSegment(Block block, Segment segment, int order) {
            this.start = block.start() + segment.start();
            this.end = block.start() + segment.end();
            this.text = segment.text();
            this.blockId = block.id();
            this.order = order;
            blockIds.add(block.id());
            words = segment.words().stream().map(w -> new OutputWord(this,
                    block.start() + w.start(), block.start() + w.end(), w.text())).toList();
        }

        boolean survives() {
            return words.isEmpty() || words.stream().anyMatch(w -> !w.removed);
        }

        TranscriptSegment export() {
            if (words.stream().noneMatch(w -> w.removed)) {
                return new TranscriptSegment(start, end, text, List.copyOf(blockIds));
            }
            var remaining = words.stream().filter(w -> !w.removed).toList();
            return new TranscriptSegment(remaining.stream().mapToDouble(w -> w.start).min().orElseThrow(),
                    remaining.stream().mapToDouble(w -> w.end).max().orElseThrow(),
                    remaining.stream().map(w -> w.text).collect(Collectors.joining()), List.copyOf(blockIds));
        }
    }

    private static final class OutputWord {
        final OutputSegment owner;
        final double start, end;
        final String text;
        boolean removed;

        OutputWord(OutputSegment owner, double start, double end, String text) {
            this.owner = owner;
            this.start = start;
            this.end = end;
            this.text = text;
        }

        double midpoint() { return start + (end - start) / 2; }
    }
}
