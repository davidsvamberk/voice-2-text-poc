# Lokální přepis přednášek — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Vytvořit lokální Java CLI pro obnovitelný jednorázový přepis dlouhých českých přednášek přes Python ASR worker.

**Architecture:** Spring Boot bez webového serveru spravuje soubory, stav a procesy. Jeden Python worker načte lokální model na GPU a postupně zpracuje uložený plán bloků; Java sestaví TXT a JSON. Diskové výsledky bloků jsou zdrojem pravdy pro obnovení.

**Tech Stack:** Java 25, Spring Boot 4.1.1, Maven Wrapper, Jackson spravovaný Boot BOM, JUnit Jupiter, Python 3.12, faster-whisper/CTranslate2, FFmpeg a ffprobe. Python testy používají standardní `unittest`.

**Spec:** [Schválený návrh](../specs/2026-10-21-local-lecture-transcription-design.md).

## Global Constraints

- Java 25 a Spring Boot v režimu CLI bez webového serveru.
- Samostatný Python worker pro ASR na GPU.
- Prioritou je přesnost před rychlostí.
- Zpracování audia i textu probíhá výhradně lokálně; modely a závislosti musí být připravené předem.
- Bez odšumování, diarizace, LLM oprav, dalšího kvalitativního průchodu, databáze či webu.
- Výstupy `transcript.txt` a `transcript.json` i pracovní soubory zůstávají v pracovním adresáři.
- Bez `--work-dir` Java použije `Files.createTempDirectory`; adresář automaticky nemaže.
- Pouze základní automatizované testy; žádné referenční přepisy, benchmarky ani testy vyžadující GPU/model.
- Maven vždy přes `./mvnw`; konkrétní JDK přes `env JAVA_HOME=... ./mvnw ...`. Každý commit má Conventional Commits subject. Již staged `.gitignore` je uživatelská změna: necommitovat jej bez vlastní související úpravy a kontroly diffu.

## Review Focus

1. Cesty s mezerami a českými znaky musí fungovat bez shellového skládání příkazů — test úlohy 3.
2. Krátká/tichá nahrávka musí dát platný plán a případně prázdný přepis — testy úloh 3 a 6.
3. Poškozený výsledek bloku nebo neznámá verze stavu nesmí být považována za hotovou práci — test úlohy 2.
4. Worker s neplatným JSON nebo předčasným EOF nesmí způsobit falešný úspěch či uváznutí — test úlohy 4.
5. Dva procesy ve stejném adresáři a zrušení uživatelem nesmí poškodit uložené výsledky — testy úloh 2 a 6.

## Rozhodnutí společná všem úlohám

### Konfigurace a struktura souborů

Java package: `cz.svamberk.transcriber`; níže `J` znamená `src/main/java/cz/svamberk/transcriber`, `T` znamená `src/test/java/cz/svamberk/transcriber`. Každý uvedený typ má vlastní soubor `.java`. Python package je `worker/transcriber_worker`.

CLI: `java -jar target/transcriber.jar INPUT [--work-dir DIR]`. Další nastavení jsou Spring properties, nikoli další povinné argumenty: `transcriber.python=.venv/bin/python`, `transcriber.worker-dir=worker`, `transcriber.model-dir=models/large-v3`, `transcriber.ffmpeg=ffmpeg`, `transcriber.ffprobe=ffprobe`. Relativní cesty se vyhodnotí vůči startovnímu adresáři. `--help` nepotřebuje závislosti ani GPU. Kódy: 0 úspěch/help, 2 neplatné argumenty, 1 provozní chyba, 130 přerušení.

Pracovní adresář: `.lock`, `manifest.json`, `request.json`, `audio.wav`, `plan.json`, `blocks/000000.json`, `logs/java.log`, `logs/worker.log`, `transcript.txt`, `transcript.json`. Dočasné soubory mají příponu `.tmp`; nejsou výsledky. Všechny JSON mají `schemaVersion:1`, UTF-8, časy jako konečná čísla v sekundách. Neznámá verze se odmítá.

Pevná počáteční ASR konfigurace: lokální vícejazyčný large-v3, `device=cuda`, `compute_type=float16`, `language=cs`, `task=transcribe`, `beam_size=5`, `temperature=0.0`, `condition_on_previous_text=true`, `vad_filter=false`, `word_timestamps=true`, bez initial promptu. Jedna teplota vylučuje kvalitativní fallback přes jiné teploty. Word timestamps jsou interní pomůcka ke spojení, nikoli slib přesného zarovnání. Parametry i instalované verze knihoven se zapisují do identity úlohy.

Identita modelu = SHA-256 stromu všech běžných souborů v lokálním model-dir: relativní názvy v lexikografickém pořadí, délky a hash obsahu; odmítnout symlinky. Hashovat streamovaně, bez načtení modelu do Java paměti. Identita vstupu = SHA-256 jeho obsahu. Konfigurace se serializuje kanonicky; absolutní cesty k programu a umístění modelu jsou metadata, nikoli součást identity obsahu. Změna obsahu vstupu/modelu nebo účinné konfigurace odmítne obnovu.

### Datové kontrakty

`JobConfig(Path modelDir, String modelSha256, String device, String computeType, String language, int beamSize, double temperature, boolean conditionOnPreviousText, boolean vadFilter, boolean wordTimestamps, int targetSeconds, int searchSeconds, double overlapSeconds, Map<String,String> engineVersions)`.

`JobManifest(int schemaVersion, String inputSha256, JobConfig config, String configSha256)`; stav dokončení se odvozuje z validních souborů, nikoli z počítadla v manifestu. `Block(int id, double start, double end)` a `BlockPlan(int schemaVersion, double duration, List<Block> blocks)`.

`Word(double start, double end, String text)`; `Segment(double start, double end, String text, List<Word> words)` s časy relativními k bloku. `BlockResult(int schemaVersion, String inputSha256, String configSha256, int blockId, List<Segment> segments)`.

`WorkerRequest(int schemaVersion, Path workDir, Path modelDir, String inputSha256, String configSha256, JobConfig config, BlockPlan plan, List<Integer> pendingBlockIds)`.

Události JSONL mají `schemaVersion:1`, `type` a podle typu: `ready` obsahuje `engineVersions`; `progress` obsahuje `blockId` a `secondsProcessed`; `block_done` obsahuje `blockId`; `error` obsahuje `code` a `message`. Umístění výsledku odvozuje Java z ID, nikoli z cesty dodané workerem. Worker volá `--probe --model-dir PATH` pro ověření lokálních souborů, importů a dostupnosti CUDA a vrátí jediný JSON s `schemaVersion` a `engineVersions`, bez načtení modelu. Reálné načtení modelu proběhne jednou při `--request PATH`, před `ready`; chyba načtení nikdy nevede k fallbacku.

`Transcript(int schemaVersion, String inputSha256, JobConfig config, List<TranscriptSegment> segments, List<MergeWarning> warnings)`; `TranscriptSegment(double start, double end, String text, List<Integer> blockIds)` má globální časy. `MergeWarning(int leftBlockId, int rightBlockId, String code)` používá `AMBIGUOUS_OVERLAP`.

### Závislosti a zdroje

Boot 4.1.1 podporuje Java 25 podle [oficiálních požadavků](https://docs.spring.io/spring-boot/system-requirements.html). Použít Maven Wrapper s Maven 3.9.11 a distribučním SHA-256. Python přímé závislosti připnout při implementaci na konkrétní vydání a zapsat kompletní lock `worker/requirements.lock`; výběr musí respektovat Python 3.12 a kompatibilitu CTranslate2/CUDA. Nepoužívat plovoucí instalaci při každém běhu. [Dokumentace faster-whisper](https://github.com/SYSTRAN/faster-whisper) popisuje lokální model, GPU float16, word timestamps a požadavek CUDA 12/cuDNN 9 pro současnou větev. Konkrétní GPU kombinace tímto plánem není ověřena.

---

### Task 1: Spustitelné CLI a konfigurace

**Files:** Create `pom.xml`, `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties`, `src/main/resources/application.properties`; `J/TranscriberApplication.java`, `J/cli/CliArguments.java`, `J/cli/TranscriberCommand.java`, `J/config/RuntimeSettings.java`; test `T/cli/CliArgumentsTest.java`.

**Interfaces:** `CliArguments.parse(String[] args) -> CliArguments(Path input, Optional<Path> workDir, boolean help)`; `TranscriberCommand.execute(String[] args) -> int`; `RuntimeSettings` obsahuje pět cest definovaných výše. Pozdější orchestrace se připojí v úloze 6.

- [ ] **1. Napsat základní test:** `parsesInputAndOptionalWorkDir` ověří cestu `přednáška 1.mp3` a `--work-dir práce`; `rejectsMissingInputAndUnknownOption` ověří chybu pro prázdné argumenty a `--unknown`; `helpNeedsNoInput` ověří `help=true`.
- [ ] **2. Připravit minimální Maven konfiguraci a wrapper, spustit červený test:** `env JAVA_HOME=/home/david/.sdkman/candidates/java/25.0.2-tem ./mvnw -Dtest=CliArgumentsTest test`; očekávat chybějící CLI implementaci. Boot starter bez webu, test starter, kompilace release 25, finální název JAR `transcriber`.
- [ ] **3. Implementovat kontrakty CLI:** jeden povinný poziční argument, jediný nepovinný `--work-dir`, odmítat opakované/neznámé volby; Spring properties ponechat v properties/env, ne v CLI parseru. `WebApplicationType.NONE`; nespouštět orchestrace při vytváření Spring contextu. Help a chyby argumentů vrací příslušný kód.
- [ ] **4. Spustit stejný test:** očekávat PASS, bez Pythonu a GPU.
- [ ] **5. Commit:** pouze soubory úlohy; `feat(cli): add Java 25 transcription command`.

### Task 2: Pracovní adresář a bezpečné checkpointy

**Files:** Create `J/job/{JobConfig,JobManifest,Block,BlockPlan,Word,Segment,BlockResult}.java`, `J/job/JobIdentity.java`, `J/job/Workspace.java`, `J/job/CheckpointStore.java`, `J/io/AtomicJsonFiles.java`; tests `T/job/WorkspaceTest.java`, `T/job/CheckpointStoreTest.java`.

**Interfaces:** `Workspace.open(Optional<Path> requested) -> Workspace implements AutoCloseable` s `path()`; `JobIdentity.identify(Path input, JobConfig config) -> JobManifest`; `CheckpointStore.initializeOrValidate(Workspace, JobManifest) -> void`, `savePlan(BlockPlan) -> void`, `loadPlan() -> Optional<BlockPlan>`, `saveBlock(BlockResult) -> void`, `loadValidBlock(Block, JobManifest) -> Optional<BlockResult>`. Store vzniká s Workspace a sdíleným JSON mapperem. `AtomicJsonFiles.write(Path target, Object value) -> void`.

- [ ] **1. Napsat základní testy:** `createsAndKeepsTemporaryWorkspace`, `rejectsUnrelatedDirectory`, `rejectsConcurrentOpen`, `rejectsChangedInputOrConfig`, `rejectsUnknownSchema`, `ignoresTmpAndRejectsCorruptBlockAsCompleted`. Ověřit zachování cizího souboru a hotového bloku po zavření; změna bajtu modelu mění jeho hash. Poškozený blok vrátí empty a diagnostiku, poškozený manifest chybu.
- [ ] **2. Spustit:** `env JAVA_HOME=/home/david/.sdkman/candidates/java/25.0.2-tem ./mvnw -Dtest=WorkspaceTest,CheckpointStoreTest test`; očekávat FAIL před implementací.
- [ ] **3. Implementovat kontrakty:** `.lock` držet přes `FileChannel.tryLock` do konce úlohy. Po získání locku kontrolovat obsah; vlastní lock nesmí způsobit odmítnutí prázdného adresáře. Stav zapisovat do stejného adresáře přes force/close a `ATOMIC_MOVE`; nepodporovanou operaci propagovat. Kontrolovat hash identity, ID bloku, verzi, konečné časy `0 <= start <= end <= délka bloku` a pořadí segmentů. Obdobná kontrola slov; prázdný seznam je platný. `.tmp` nikdy neotevírat jako checkpoint. Před checkpointováním plánu musí existovat dokončené audio; bez platného plánu znovu připravit audio.
- [ ] **4. Spustit stejné testy:** očekávat PASS; nepoužívat velké soubory ani GPU.
- [ ] **5. Commit:** `feat(job): persist resumable transcription checkpoints`.

### Task 3: Příprava audia a deterministický plán bloků

**Files:** Create `J/process/ProcessRunner.java`, `J/process/ProcessResult.java`, `J/audio/{AudioPreparer,BlockPlanner,SilenceInterval}.java`; tests `T/audio/AudioPreparerTest.java`, `T/audio/BlockPlannerTest.java`.

**Interfaces:** `ProcessRunner.run(List<String> command, Path stderrLog) -> ProcessResult(int exitCode, String stdout)`; stdout omezen na 8 MiB, stderr streamovat do logu. `AudioPreparer.prepare(Path input, Workspace ws) -> PreparedAudio(Path path, double duration, List<SilenceInterval> silences)`; `BlockPlanner.plan(double duration, List<SilenceInterval> silences) -> BlockPlan`. `PreparedAudio` je vlastní record v `J/audio/PreparedAudio.java`; `SilenceInterval(double start, double end)` nese časy pauzy. `ProcessResult` je vlastní record v `J/process/ProcessResult.java`.

- [ ] **1. Napsat testy:** `passesUnicodeAndSpacesAsSingleArguments` přes náhradu runneru; `failsWhenFfmpegFails`; `plansShortAudio` očekává `[0,12]` pro 12 s; `plansSilenceNearBoundary` pro 1200 s a pauzu `[598,602]` očekává bloky `[0,601]` a `[599,1200]`; `fallsBackWithoutSilence` pro 1210 s očekává `[0,601]`, `[599,1201]`, `[1199,1210]`. Odmítnout nulovou či neplatnou délku média.
- [ ] **2. Spustit:** `env JAVA_HOME=/home/david/.sdkman/candidates/java/25.0.2-tem ./mvnw -Dtest=AudioPreparerTest,BlockPlannerTest test`; očekávat FAIL.
- [ ] **3. Implementovat kontrakty:** použít `ProcessBuilder(List<String>)`, nikdy shell. FFmpeg `-nostdin -i INPUT -map 0:a:0 -vn -ac 1 -ar 16000 -c:a pcm_s16le -f wav audio.wav.tmp`; RF64 podporu zapnout `-rf64 auto`. Hotové audio atomicky přejmenovat. ffprobe zjistí dobu; FFmpeg `silencedetect=noise=-35dB:d=0.5` nad pracovním audiem dodá pauzy, žádné změny zvuku. Nečíst celý soubor do RAM. Hledat střed pauzy nejblíž 600 s od poslední základní hranice, v okně ±30 s; shodu rozhodne dřívější pauza. Bez pauzy hranice přesně +600 s. Zbývajících nejvýše 600 s už nerozdělovat. Pokud vybraná pozdní pauza leží za koncem audia, použít konec audia. Každý blok rozšířit o 1 s na obě strany, oříznout na rozsah audia: sousední bloky se překrývají 2 s. ID od 0; uložit plán před workerem. Pro příkazové procesy průběžně odčerpávat oba streamy, při přerušení ukončit proces i potomky.
- [ ] **4. Spustit stejné testy:** očekávat PASS. Testy nevyžadují instalovaný FFmpeg.
- [ ] **5. Commit:** `feat(audio): prepare local audio and plan overlapping blocks`.

### Task 4: Python worker a verzovaný protokol

**Files:** Create `worker/pyproject.toml`, `worker/requirements.lock`, `worker/transcriber_worker/{__init__,__main__,protocol,engine}.py`, `worker/tests/test_worker.py`; `J/worker/{WorkerRequest,WorkerEvent,WorkerClient}.java`; `T/worker/WorkerClientTest.java`, `src/test/resources/fake_worker.py`.

**Interfaces:** Python `run_request(request: dict, engine_factory, emit) -> int`, `probe(model_dir: Path) -> dict`, `Engine.transcribe(audio_path: Path, block: dict) -> list[dict]`. Java `WorkerClient.probe(RuntimeSettings) -> Map<String,String>`, `WorkerClient.run(WorkerRequest request, Consumer<WorkerEvent> events) -> void`. Client zapisuje request atomicky a sleduje stdout i stderr souběžně.

- [ ] **1. Napsat testy:** Python `test_loads_engine_once_for_pending_blocks`, `test_forces_local_cuda_and_czech`, `test_writes_result_before_done`, `test_failure_emits_error_without_fallback`. Náhrada enginu vrací český segment a počítá volání; ověřit přesné parametry konfigurace. Java fake worker otestuje `rejectsMalformedJson`, `rejectsEarlyEof`, `propagatesWorkerFailure`, `acceptsDoneOnlyWithValidFile`. Ověřit odmítnutí `block_done` pro neočekávané nebo opakované ID.
- [ ] **2. Spustit červené testy:** `python3 -m unittest discover -s worker/tests -v` a `env JAVA_HOME=/home/david/.sdkman/candidates/java/25.0.2-tem ./mvnw -Dtest=WorkerClientTest test`; očekávat FAIL před implementací.
- [ ] **3. Implementovat worker a klienta podle společných kontraktů:** probe neprovádí inference; model načíst jen při neprázdném seznamu pending bloků přes absolutní lokální cestu a `local_files_only=True`. Nastavit `HF_HUB_OFFLINE=1`, vypnout telemetrii; nepoužívat jméno vzdáleného modelu. Lazy import ASR oddělí testy od GPU závislostí. Zvuk bloku číst seekem z PCM WAV, nikoli opakovaným dekódováním celé nahrávky; engine drží jen blok. Iterátor segmentů plně spotřebovat a převést na JSON. Výsledek fsync + `os.replace` ve stejném adresáři, potom `block_done` s flush. Logy jen stderr. Počítat s prázdným výsledkem pro ticho. Neplatné zprávy, neznámá verze, chyba či EOF bez všech dokončení ukončí Java klienta chybou; ověřit také nulový exit procesu. Při zrušení worker ukončit, po 5 s násilně i potomky; neaplikovat krátký timeout na inference.
- [ ] **4. Spustit stejné testy:** očekávat PASS, pouze systémový Python se standardní knihovnou pro fake worker. Závislosti ASR uzamknout v lock souboru pro předem provedenou instalaci, nikoli v běhu aplikace.
- [ ] **5. Commit:** `feat(worker): add offline GPU transcription process protocol`.

### Task 5: Spojení překryvů a export

**Files:** Create `J/output/{Transcript,TranscriptSegment,MergeWarning,TranscriptAssembler,TranscriptWriter}.java`; test `T/output/TranscriptAssemblerTest.java`, `T/output/TranscriptWriterTest.java`.

**Interfaces:** `TranscriptAssembler.assemble(JobManifest manifest, BlockPlan plan, List<BlockResult> results) -> Transcript`; `TranscriptWriter.write(Workspace workspace, Transcript transcript) -> void`. Použít `AtomicJsonFiles` z úlohy 2.

- [ ] **1. Napsat testy:** `addsBlockOffsetToTimes`, `deduplicatesMatchingWordsOnlyInsideOverlap`, `preservesRealRepetitionOutsideOverlap`, `preservesAmbiguousOverlapWithWarning`, `writesUtf8AndEmptyTranscript`. Příklad bloků `[0,601]`, `[599,1200]`: shodná slova s globálními časy 599.3 a 600.1 zůstanou jednou; dvě „ano“ v 590 a 592 zůstanou obě. Odlišné texty ve společné oblasti zůstanou oba a vznikne `AMBIGUOUS_OVERLAP`.
- [ ] **2. Spustit:** `env JAVA_HOME=/home/david/.sdkman/candidates/java/25.0.2-tem ./mvnw -Dtest=TranscriptAssemblerTest,TranscriptWriterTest test`; očekávat FAIL.
- [ ] **3. Implementovat konzervativní spojení:** časy slov a segmentů převést na globální čas. V překryvu porovnat nejdelší suffix slov levého a prefix pravého bloku; pro porovnání trim + lowercase a odstranění okrajové interpunkce, původní text neměnit. Mazat pravou kopii jen při nejméně dvou shodných slovech a rozdílu středů odpovídajících slov nejvýše 0.5 s; u jediného slova deduplikovat jen při přesné textové shodě a překrytí intervalů alespoň 50 % kratšího intervalu. Kandidáti musí ležet středy ve společném časovém rozsahu bloků. Pokud jsou v překryvu řečové výstupy obou bloků a není jednoznačná shoda, uchovat obě verze a warning. Po odstranění části segmentu složit zbývající původní word texty bez stylistických změn a upravit rozsah; segment bez slov ponechat konzervativně. Segmenty stabilně seřadit dle začátku, zdrojového ID a původního pořadí; nevynucovat nepřekrývání časů. TXT: jeden neprázdný segment na řádek, JSON nese provenance i varování. Oba výstupy zapisovat atomicky jednotlivě; úspěch teprve po obou.
- [ ] **4. Spustit stejné testy:** očekávat PASS, bez ASR. Deduplikace je heuristika, ne garance správného spojení každé nahrávky.
- [ ] **5. Commit:** `feat(output): merge timestamped blocks and export transcripts`.

### Task 6: Propojení, obnova a návod spuštění

**Files:** Create `J/job/TranscriptionJob.java`, `J/process/ProcessSupervisor.java`, `T/job/TranscriptionJobTest.java`, `README.md`; Modify `J/cli/TranscriberCommand.java`, `J/process/ProcessRunner.java`, `J/worker/WorkerClient.java`, `.gitignore` (pouze doplnění ignorování `.venv/`, Python cache a `models/`, zachovat uživatelův obsah).

**Interfaces:** `TranscriptionJob.run(CliArguments args, RuntimeSettings settings) -> Path` vrací pracovní adresář; `ProcessSupervisor implements AutoCloseable` eviduje všechny procesy a při zavření ukončí aktivní procesy. Runner i klient používají tentýž supervisor. `TranscriberCommand.execute` mapuje výsledek a chyby na dohodnuté návratové kódy, tiskne cestu i návod obnovení. Logování Java směrovat do pracovního adresáře po jeho otevření, konzole nese stručný průběh.

- [ ] **1. Napsat malé scénářové testy s náhradou přípravy a workeru:** `runsToTxtAndJson`, `resumesOnlyPendingBlocks`, `reassemblesWithoutWorkerWhenAllBlocksExist`, `emptyRecognitionProducesValidOutputs`, `failureKeepsCompletedBlocksAndReportsDirectory`, `interruptionStopsChildAndKeepsCheckpoint`. Ověřit, že znovuspuštění nevytvoří další ASR průchod a po úplném checkpointu nevyžaduje GPU. Jedno malé spuštění fake procesu ověří ukončení subprocessu; nezavádět testovací infrastrukturu GPU ani širokou integrační sadu.
- [ ] **2. Spustit:** `env JAVA_HOME=/home/david/.sdkman/candidates/java/25.0.2-tem ./mvnw -Dtest=TranscriptionJobTest test`; očekávat FAIL.
- [ ] **3. Implementovat orchestraci:** validace vstupu → otevření/lock workspace → identita vstupu/modelu → validace manifestu a existujících výsledků → při chybějícím plánu probe runtime + příprava a uložení audia/plánu → při pending blocích probe/verze a shoda konfigurace + worker → validace všech výsledků → sestavení výstupů. Při nové úloze manifest zapsat až po zjištění verzí z probe, před přípravou audia. Při obnově hotových bloků lze používat uložené verze bez spouštění Pythonu, pokud jsou všechny bloky hotové; pro nové bloky musí probe potvrdit shodu verzí. Probe ani změna runtime nesmí přepsat původní manifest. Pokud je připravené audio poškozené, ověřit WAV vlastnosti/délku a znovu jej připravit z originálu při zachování platného plánu. Vstupní soubor nesmí být přepisován výstupy v workspace. Hook přerušení spolupracuje se supervisorem; lock uvolnit až po zastavení procesů. Po úspěchu znovu ověřit neměnnost vstupu/modelu hashem; při změně neoznámit úspěch a vyžadovat novou úlohu.
- [ ] **4. Doplnit README:** build, Python venv a instalace locku, CUDA/cuDNN předpoklady, samostatné předběžné stažení modelu s připnutou revizí a záznamem původu, lokální cesty a env nastavení Spring properties, oba CLI příklady a obnovení, výstupy a jejich omezení. Příprava může vyžadovat internet, samotný přepis nikdy. Distribuce je JAR + adresář `worker` + venv/model, nikoli samostatný JAR se zabaleným Pythonem. Modely/audio necommitovat. Uvést, že ticho/šum mohou vést k halucinaci a že kvalita na reálných nahrávkách nebyla měřena.
- [ ] **5. Ověřit celý dohodnutý rozsah:** `env JAVA_HOME=/home/david/.sdkman/candidates/java/25.0.2-tem ./mvnw clean verify`, `python3 -m unittest discover -s worker/tests -v`, `java -jar target/transcriber.jar --help`, `git diff --check`; očekávat zelené testy, help s kódem 0 a žádné whitespace chyby. Nespouštět nevyžádaný benchmark ani tvrdit ověřenou GPU inference. Pokud prostředí nedovolí některý příkaz, uvést přesnou neověřenou část.
- [ ] **6. Commit:** vyjmenovat pouze soubory úlohy a zkontrolovat staged diff včetně existujícího `.gitignore`; `feat(cli): orchestrate resumable local transcription`.

## Kontrola pokrytí a předání

CLI/konfigurace: úloha 1; identita, workspace a atomické checkpointy: 2; FFmpeg a plán: 3; lokální ASR a protokol: 4; věrný export a překryvy: 5; propojení, přerušení, obnovení a návod: 6. Základní testy pokrývají uvedené Review Focus. Žádný test netvrdí ověření přesnosti ASR ani kompatibility cílové GPU.

Plán byl zkontrolován proti schválenému návrhu. Výběr přesných Python patch verzí a instalačního locku patří do úlohy 4; jejich kompatibilita vyžaduje ověření při instalaci. Implementace začne až po kontrole tohoto plánu a volbě způsobu provedení uživatelem.
