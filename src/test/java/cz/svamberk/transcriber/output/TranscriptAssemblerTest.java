package cz.svamberk.transcriber.output;

import cz.svamberk.transcriber.job.*;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TranscriptAssemblerTest {
    static final JobConfig CONFIG = new JobConfig(Path.of("model").toAbsolutePath(), "model-hash", "cuda", "float16", "cs",
            5, 0, true, false, true, 600, 30, 2, Map.of("engine", "1"));
    static final JobManifest MANIFEST = new JobManifest(1, "input-hash", CONFIG, "config-hash");
    final TranscriptAssembler assembler = new TranscriptAssembler();
    final BlockPlan plan = new BlockPlan(1, 1200, List.of(new Block(0, 0, 601), new Block(1, 599, 1200)));

    static Segment segment(String text, Word... words) {
        return new Segment(words[0].start(), words[words.length - 1].end(), text, List.of(words));
    }

    static BlockResult result(int id, Segment... segments) {
        return new BlockResult(1, "input-hash", "config-hash", id,
                id == 0 ? 0 : 599, id == 0 ? 601 : 1200, List.of(segments));
    }

    Transcript assemble(Segment left, Segment right) {
        return assembler.assemble(MANIFEST, plan, List.of(result(1, right), result(0, left)));
    }

    @Test
    void addsBlockOffsetToTimes() {
        var transcript = assembler.assemble(MANIFEST, plan,
                List.of(result(0), result(1, segment(" Původní!", new Word(3, 4, " Původní!")))));
        assertEquals(List.of(new TranscriptSegment(602, 603, " Původní!", List.of(1))), transcript.segments());
        assertEquals(1, transcript.schemaVersion());
        assertEquals("input-hash", transcript.inputSha256());
        assertEquals(CONFIG, transcript.config());
        assertTrue(transcript.warnings().isEmpty());
    }

    @Test
    void deduplicatesMatchingWordsOnlyInsideOverlap() {
        var transcript = assemble(
                segment(" Úvod. AHOJ, světe!", new Word(598, 598.5, " Úvod."),
                        new Word(599.2, 599.4, " AHOJ,"), new Word(600, 600.2, " světe!")),
                segment(" ahoj světe pokračování.", new Word(.3, .5, " ahoj"),
                        new Word(1.1, 1.3, " světe"), new Word(3, 4, " pokračování.")));
        assertEquals(List.of(new TranscriptSegment(598, 600.2, " Úvod. AHOJ, světe!", List.of(0, 1)),
                new TranscriptSegment(602, 603, " pokračování.", List.of(1))), transcript.segments());
        assertTrue(transcript.warnings().isEmpty());
    }

    @Test
    void preservesRealRepetitionOutsideOverlap() {
        var transcript = assemble(segment("ano ano", new Word(590, 590.2, "ano"), new Word(592, 592.2, " ano")),
                segment("ano ano", new Word(3, 3.2, "ano"), new Word(5, 5.2, " ano")));
        assertEquals(List.of("ano ano", "ano ano"), transcript.segments().stream().map(TranscriptSegment::text).toList());
        assertTrue(transcript.warnings().isEmpty());
    }

    @Test
    void preservesAmbiguousOverlapWithWarning() {
        var transcript = assemble(segment(" první", new Word(599.2, 599.6, " první")),
                segment(" druhý", new Word(.2, .6, " druhý")));
        assertEquals(List.of(" první", " druhý"), transcript.segments().stream().map(TranscriptSegment::text).toList());
        assertEquals(List.of(new MergeWarning(0, 1, "AMBIGUOUS_OVERLAP")), transcript.warnings());
    }

    @Test
    void warnsForIntersectingSpeechWithMidpointOutsideOverlap() {
        for (String rightText : List.of(" druhý", " první")) {
            var transcript = assemble(segment(" první", new Word(598.6, 599.2, " první")),
                    segment(rightText, new Word(0, .2, rightText)));
            assertEquals(List.of(" první", rightText),
                    transcript.segments().stream().map(TranscriptSegment::text).toList());
            assertEquals(List.of(new MergeWarning(0, 1, "AMBIGUOUS_OVERLAP")), transcript.warnings());
        }
    }

    @Test
    void singleWordRequiresExactTextAndHalfOfShorterInterval() {
        var left = segment(" Ano!", new Word(599.2, 599.6, " Ano!"));
        var exact = assemble(left, segment(" Ano!", new Word(.4, .8, " Ano!")));
        assertEquals(1, exact.segments().size());
        assertEquals(List.of(0, 1), exact.segments().getFirst().blockIds());
        assertTrue(exact.warnings().isEmpty());
        for (var right : List.of(segment(" ano", new Word(.2, .6, " ano")),
                segment(" Ano!", new Word(.5, .9, " Ano!")))) {
            var ambiguous = assemble(left, right);
            assertEquals(2, ambiguous.segments().size());
            assertEquals(1, ambiguous.warnings().size());
        }
    }

    @Test
    void matchingWordsBeyondMidpointToleranceRemain() {
        var transcript = assemble(segment(" a b", new Word(599, 599.1, " a"), new Word(599.2, 599.3, " b")),
                segment(" a b", new Word(.6, .7, " a"), new Word(.8, .9, " b")));
        assertEquals(2, transcript.segments().size());
        assertEquals(1, transcript.warnings().size());
    }

    @Test
    void preservesWordlessSegmentsAndStableOrder() {
        var transcript = assemble(new Segment(599.2, 600, "Bez slov vlevo", List.of()),
                new Segment(.2, 1, "Bez slov vpravo", List.of()));
        assertEquals(List.of(new TranscriptSegment(599.2, 600, "Bez slov vlevo", List.of(0)),
                new TranscriptSegment(599.2, 600, "Bez slov vpravo", List.of(1))), transcript.segments());
        assertEquals(1, transcript.warnings().size());
    }

    @Test
    void supportsEmptyTranscripts() {
        assertTrue(assembler.assemble(MANIFEST, plan, List.of(result(0), result(1))).segments().isEmpty());
        assertTrue(assembler.assemble(MANIFEST, new BlockPlan(1, 0, List.of()), List.of()).warnings().isEmpty());
    }
}
