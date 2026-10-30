# Whisper chunking implementation plan

> **For agentic workers:** Use superpowers:executing-plans inline, with test-first changes and final independent review.

**Goal:** Compare 20/30/60-second application blocks on identical audio, then apply the measured configuration with reliable hints and checkpoint identity.

**Architecture:** Retain Java BlockPlanner/TranscriptAssembler and one persistent Python CUDA model. Configure block length/search/overlap, previous-text conditioning and two explicit hint mechanisms. Hotwords repeat in every decoder window; initialPrompt is first-window context. Preserve original audio and conservative overlap warnings.

**Tech Stack:** Java 25/Maven wrapper, Spring configuration properties, Python 3.12/Pipenv, faster-whisper 1.2.1, existing FFmpeg.

**Spec:** User-approved proposal in the current conversation: compare 20/30/60s, pass hints for each chunk, retain pause search/overlap/word merging, configure and hash effective settings.

## Global constraints

The no-commit constraints below describe the implementation phase. On 2026-10-30 the user explicitly authorized committing and pushing all relevant changes, including the later JSONL export.

- Preserve all pre-existing staged/unstaged edits; no commits or pushes requested.
- Work in the existing checkout as explicitly authorized by the user's continuation of this project; no branch reset or copying staged work.
- One immutable PCM/reference pair, 113 scored words/two masked words; no reference sentences in hints.
- CPU-only deterministic tests; real GPU experiment supplies separate accuracy evidence.
- No denoising, Ollama, GPU power-limit changes or automatic second passes.
- Direct wrapper commands with Java 25; no Maven output redirection.

## Review focus

- Legacy manifests with absent new hint fields deserialize without silently receiving new defaults.
- Modified text hints or conditioning invalidate reused checkpoints; old null-hint identity remains reproducible.
- Zero search radius, invalid overlap/negative/nonfinite settings fail predictably in both Java and Python.
- Every worker block receives configured hints; model loads once, word timestamps remain local to each block.
- Actual Java merge output and ambiguity warnings count in the experiment; duplicate boundary words are not silently discarded to improve WER.

## Tasks

### 1. Measured experiment

- [x] Add `experiments/whisper-chunking/Bridge.java` using actual BlockPlanner and TranscriptAssembler.
- [x] Add a Pipenv runner comparing target 20/30/60s × initial_prompt/hotwords, search 1s and overlap 2s, on the current original PCM minute. Save exact block plans, raw words, merged output, warnings, WER and hashes. Repeat the 60s baseline.
- [x] Read results and choose a conservative measured default; record one-minute limitations.

### 2. Configuration and protocol (TDD)

- [x] Add failing Python tests: configured false conditioning/hints reach each call; reject malformed hints; accept zero search and reject incompatible plan settings.
- [x] Extend `JobConfig` with nullable `initialPrompt` and `hotwords`, preserving its old constructor and old null-hint hash. Add failing Java tests for identity/legacy deserialization.
- [x] Extend `RuntimeSettings` with target/search/overlap/conditioning/hints and an old five-path constructor; add tests for Spring binding, blank hint disabling and invalid settings.
- [x] Implement settings propagation in TranscriptionJob, Engine and protocol validation. Preserve legacy explicitly configured behavior.
- [x] Test configured short-block checkpoint/resume and invalidation, retain merge/time-boundary tests. Existing long-duration fixtures explicitly select their old settings.

### 3. Delivery verification

- [x] Update README/configuration examples, handoff and experiment report with measured settings and limitations.
- [x] Run Python unittest suite through Pipenv and `env JAVA_HOME=/home/david/.sdkman/candidates/java/25.0.3-tem ./mvnw verify`, including real FFmpeg tests when available.
- [x] Perform real short CLI run with selected settings, inspect manifest, chunks, transcript and resume behavior.
- [x] Obtain independent review of just this change and fix important findings with regression tests. No commits.

## Progress ledger

- Pre-flight: tasks 1→2 share recommended configuration, tasks 2→3 share persisted identity/protocol. Defaults are selected only after task 1; legacy fixtures remain explicit.
- Ruling: skill commit/worktree wrappers are not used because user-owned staged changes and lack of commit authorization take precedence; retain this plan and direct verification evidence in the checkout.

- Measurement: 60s initial prompt 48 errors/42.48%; 20s 49/43.36% with two preserved ambiguous overlaps; 30s 56/49.56%. Hotwords did not improve the score. Final 60s baseline repeat matched all raw segments, words and times.
- Selection ruling: retain initial prompt rather than make repeated hotwords the default; use target 60/search 1/overlap 2 and false conditioning. The one-minute comparison does not measure 60s versus 600s on the long recording.
- TDD: Python tests failed before propagation/validation changes, Java new API tests failed compilation before implementation. Added production-reader legacy regression after review, observed missing-creator-property failure, then narrowly normalized only absent optional hint fields. Existing required fields remain strict and old manifest bytes are unchanged.
- Verification: Java 25 wrapper `verify` passed 109 tests with zero skips, including real FFmpeg; root Pipenv Python suite passed 15 tests. A sandbox-only Mockito JVM-agent attachment timeout was confirmed by its trace; the same unchanged process test passed outside the sandbox.
- Real JAR: default 60s and environment-configured 20s reproduced benchmark PCM/plans/raw words/times/text/warnings and 48/49 errors. Both completed resumes preserved checkpoint bytes/mtimes and worker/probe/FFmpeg logs. Verification helper initially included the expected append-only Java log in its unchanged-log assertion; corrected to exclude java.log and reused the completed inference. Original audio/reference hashes stayed unchanged.
- Independent review: one important finding (legacy strict manifest loading), fixed and covered through CheckpointStore.loadManifest and initializeOrValidate. No other actionable findings. No commits, index changes or model/dependency changes.
- Delivery verification (2026-10-30): current tree passed all 110 Java tests with real FFmpeg and zero skips, plus 15 Python tests through the root Pipenv. Root and archived Parakeet Pipenv locks both verified. Experiment sources parsed successfully; recordings, model weights, virtual environments and local run outputs remain outside Git.
