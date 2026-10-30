# Předání na desktop — 22. 10. 2026

## Aktuální rozhodnutí — 29. 10. 2026

Strukturovaný finální výstup nyní používá `transcript.jsonl` pro návazné zpracování: jeden řádek metadat, časově seřazené segmenty a nakonec varování, vše s rozlišením `type`. Zápis je stále finální a atomický; TXT a checkpointy zůstávají. Starý `transcript.json` se nemaže. Formát a příklad `jq` jsou v README. Ověřeno 110 Java testů včetně skutečného FFmpeg.

Celý běh `/home/david/transcriber-runs/260613_090426-20261029-60s-01` úspěšně dokončil 136 bloků (2:15:11 zvuku, přibližně 7:51 celkového běhu). Má 1 126 segmentů a 79 upozornění na nejednoznačný překryv; tento počet není měřítkem WER ani počtem všech chybných slov. Nový JAR nad hotovými checkpointy vytvořil `workspace/transcript.jsonl` se shodným obsahem jako původní JSON, bez Pythonu/FFmpeg/inference a beze změn checkpointů. Doklad je v `jsonl-export-verification.json` u běhu. Původní runtime JAR/worker a původní JSON jsou zachované.

Pro další práci byl zvolen faster-whisper `large-v3`, CUDA `float16`, s `condition_on_previous_text=False` a nápovědou (`initial_prompt`):

```text
Pedagogická intervence, speciálně pedagogické centrum, školské poradenské zařízení.
```

Ostatní zkoušené parametry: `language=cs`, `task=transcribe`, `beam_size=5`, `temperature=0.0`, `vad_filter=False`, `word_timestamps=True`. Na přesně minutovém vstupu měl tento Whisper 44 chyb ze 113 hodnocených slov (WER 38,94 %), výchozí Whisper 51 chyb (45,13 %) a nejlepší z 48 kombinací Parakeetu 61 chyb (53,98 %). Dvě uživatelem označená nejistá slova byla vynechána z hodnocení. Výsledek platí pro jediný ladicí vzorek, další části zatím nejsou ověřené.

Zvolené volby jsou nyní zapracované v hlavním CLI a workeru: výchozí cíl 60 s, hledání pauz ±1 s, překryv 2 s, vypnutý předchozí text a uvedená počáteční nápověda. `RuntimeSettings` umožňuje délku, pauzy, překryv, conditioning, `initial-prompt` a `hotwords` změnit přes Spring properties/prostředí. Nápověda i dělení jsou součástí identity checkpointů. `hotwords` nabízí opakování slovní nápovědy v každém interním okně, standardně je prázdné. Počáteční prompt platí pro první okno každého aplikačního bloku.

Porovnání se skutečným Java plánovačem a spojováním: cíl 20 s měl 49 chyb (43,36 %, dvě nejednoznačná spojení), cíl 30 s 56 chyb (49,56 %), cíl 60 s s počátečním promptem 48 chyb (42,48 %) a s opakovanými hotwords 50 chyb (44,25 %). Vstup je stejné PCM jako v pokusu s EQ níže. Dvacetisekundový výsledek Parakeetu tedy nelze přenést na Whisper. Minutový výchozí profil je průběžná volba, nikoli ověřený vítěz na celé nahrávce. Podrobnosti jsou v [experimentu dělení](../experiments/whisper-chunking/README.md).

Aktuální ověření: 109 Java testů včetně skutečného FFmpeg a 15 Python testů přes hlavní Pipenv. Skutečný JAR na GPU reprodukoval benchmark pro výchozí 60s i nastavený 20s profil, včetně slov, časů, skóre a varování; dokončené běhy se obnovily bez inference a změny checkpointů. Nezávislá kontrola odhalila striktní načítání starých manifestů bez nových polí nápovědy; opraveno s regresním testem přes produkční CheckpointStore. Nový profil vyžaduje nový workspace; starý lze načíst při explicitním obnovení všech původních voleb.

Navazující pokus s EQ a dynamikou je v [experiments/audio-enhancement](../experiments/audio-enhancement/README.md). Nová minutová kontrola ze zachovaného WAV měla 48 chyb (42,48 %), EQ také 48, dynamika 47 (41,59 %) a kombinace 48. Dynamika zvýšila RMS přibližně o 6,4 dB, významný přínos pro přepis se však neprokázal. Kontrolní opakování bylo totožné. Dřívějších 38,94 % používá jiné připravené PCM; tyto dvě série nelze přímo zaměňovat. Pro ASR zatím ponechat původní audio.

Na přání uživatele byly odstraněny výstupy dosavadních pokusů v `~/transcriber-runs` včetně odvozeného audia, přepisů, checkpointů a logů. Zachované referenční soubory:

- `/home/david/transcriber-runs/260613_090426-20261023-01/samples/manual-reference-003500-003600.txt`
- `/home/david/transcriber-runs/260613_090426-20261023-01/samples/manual-reference-003500-003600.wav`

Text má SHA-256 `f99c6fb2b888bc0128746f25ce37d092c96a2c505477105b7414159ea160eac5`. Jde o úsek 35:00–36:00 původní nahrávky `/home/david/Downloads/260613_090426.m4a`. Původní nahrávky a lokální modely zůstávají k dispozici. Staré pracovní adresáře již nelze obnovit z checkpointů; případný další přepis má vytvořit nový běh. Níže následuje původní předání z 22. 10.

## Cíl a dohodnutý rozsah

Věrný přepis dlouhých českých přednášek s ruchy, výhradně lokálně. Přesnost má přednost před rychlostí. Java 25 řídí jednoduché CLI a jeden Python worker s faster-whisper, vícejazyčným modelem large-v3 a CUDA `float16`. Java je důležitá kvůli zkušenostem autora; Python zajišťuje rozpoznávání řeči. Ollama ani Spring AI nejsou pro současný přepis potřeba.

Cílový desktop: Ryzen 5 7500F, RTX 4070 SUPER 12 GB, 32 GB DDR5, NVMe SSD 2 TB. Skutečnou spotřebu VRAM a rychlost na tomto počítači zatím nemáme změřené. Pracovní notebook nepoužívat pro pokusy s inferencí.

Zatím nepřidávat druhý rozpoznávací průchod, LLM opravy, odšumování, diarizaci ani serverové rozhraní. Nejprve ověřit současný program na skutečné krátké nahrávce. Složitost držet nízkou, automatizované testy pouze základní.

## Stav při předání

Implementace i zapracované připomínky code review jsou na `main`, poslední implementační commit je `51e3b3d` (`fix: address transcription code review findings`). Návrh a implementační plán jsou v [specifikaci](superpowers/specs/2026-10-21-local-lecture-transcription-design.md) a [plánu](superpowers/plans/2026-10-21-local-lecture-transcription.md).

- Ověřeno: 100 Java testů, 12 Python testů a spuštění JAR s `--help`. Testy neověřují skutečný ASR model.
- Časová osa byla ověřena také skutečným FFmpeg na syntetických médiích, včetně počátečního posunu a mezer v audiu. Pomocná binárka z notebookového `/tmp` není součástí repozitáře.
- Neověřeno: načtení modelu na GPU, kompatibilita celého CUDA prostředí, skutečný přepis AAC, kvalita češtiny, rychlost a vícehodinový běh.
- Testovací `~/Downloads/test-rec.aac` na notebooku nebyl přepsán. Nahrávku přenést zvlášť; čistý klon ji neobsahuje. Model, venv ani výsledky rovněž nejsou součástí klonu.

## Postup po čistém klonu

Vše spouštět z kořene repozitáře. Podrobná instalace a skript pro stažení modelu jsou v [README](../README.md); zde je pořadí kroků.

1. Připravit Linux x86_64 s glibc alespoň 2.28, JDK 25, CPython 3.12, `ffmpeg` a `ffprobe`. Současný Python lock není přenositelný na Windows. Operační systém desktopu dosud nebyl potvrzen.
2. Připravit NVIDIA ovladač, cuBLAS pro CUDA 12 a cuDNN 9 pro CUDA 12 podle README. Ověřit `nvidia-smi`; úspěšný výpis sám o sobě nezaručuje dostupnost knihoven pro worker.
3. S aktivním JDK 25 sestavit projekt a spustit testy:

   ```bash
   java -version
   ./mvnw clean verify
   python3 -B -m unittest discover -s worker/tests -v
   java -jar target/transcriber.jar --help
   ```

   Pro explicitní volbu JDK použít `env JAVA_HOME=/cesta/k/jdk-25 ./mvnw clean verify`. Procesové testy potřebují `/usr/bin/python3`. Bez FFmpeg se některé testy časové osy přeskočí.

4. Vytvořit Python prostředí přes Pipenv s připnutými závislostmi:

   ```bash
   export PIPENV_VENV_IN_PROJECT=1
   export PIPENV_DONT_LOAD_ENV=1
   pipenv --python /cesta/k/python3.12
   pipenv verify
   pipenv sync
   ```

   Pipenv instaluje také cuBLAS/cuDNN; před probe a přepisem nastavit `LD_LIBRARY_PATH` podle README. NVIDIA ovladač a FFmpeg jsou samostatné předpoklady.

5. Podle skriptu v README stáhnout `Systran/faster-whisper-large-v3`, revizi `edaa852ec7e145841d8ffdb056a99866b5f0a478`, do `models/large-v3`. Zachovat i vytvořený `provenance.json`. Příprava závislostí a modelu potřebuje internet, samotný přepis už nic nestahuje. Po založení úlohy obsah modelu neměnit.
6. Ověřit probe:

   ```bash
   PYTHONPATH=worker pipenv run python -m transcriber_worker --probe --model-dir "$PWD/models/large-v3"
   ```

   Probe kontroluje prostředí a lokální soubory; nenačítá váhy modelu a neprovádí přepis. Teprve další krok ověří skutečnou inferenci.

## První skutečný přepis

Přenést krátkou testovací nahrávku a spustit například:

```bash
java -jar target/transcriber.jar "$HOME/Downloads/test-rec.aac" \
  --work-dir "$HOME/transcriber-runs/test-rec-01"
```

Pracovní adresář má být nový nebo patřit stejné úloze. Držet jej mimo repozitář; vstup i model musí být mimo něj. Bez `--work-dir` Java vytvoří dočasný adresář a vypíše jeho cestu, ale pro pokračování mezi spuštěními je vhodnější vlastní trvalý adresář.

Po doběhnutí zkontrolovat návratový kód 0, `transcript.txt`, `transcript.jsonl` a logy v pracovním adresáři. Porovnat text poslechem s originálem, zkontrolovat časové značky a případná varování. Zapsat délku nahrávky, dobu běhu a pozorovanou spotřebu VRAM jako první praktické měření. Úspěšné dokončení samo o sobě nepotvrzuje přesnost přepisu.

Zopakovat stejný příkaz: dokončené bloky se mají převzít z checkpointů bez nového rozpoznávání. Přerušení a obnovu uprostřed přepisu vyzkoušet až s delší nahrávkou. Poté přejít na delší a hlučnější vzorek; o dalších úpravách rozhodovat podle konkrétních chyb.

## Co si pohlídat při pokračování

- Výchozí cesty `.venv/bin/python`, `worker` a `models/large-v3` jsou relativní k adresáři spuštění. Při změně cest použít nastavení z README; proměnné se jmenují `TRANSCRIBER_WORKERDIR` a `TRANSCRIBER_MODELDIR` bez dalšího podtržítka.
- GPU chyba nebo nedostatek paměti ukončí úlohu; není automatický přechod na CPU ani menší model. Nejdřív přečíst logy, teprve potom měnit konfiguraci.
- Výsledky bloků obsahují `blockStart` a `blockEnd`. Starší checkpointy bez těchto údajů se znovu zpracují.
- `AMBIGUOUS_OVERLAP` znamená zachovaný nejednoznačný překryv, případně s opakováním textu. Podrobnosti jsou v JSON; TXT zůstává bez přidaných značek.
- Změna vstupu nebo modelu může vytvořit `invalidated.json`; potom je potřeba nový pracovní adresář. Pro obnovu zachovat také verze knihoven a konfiguraci.
- Atomické ukládání chrání před neúplnými checkpointy při přerušení procesu; nezaručuje odolnost proti výpadku napájení.

Další zadání pro pokračování může znít: „Přečti README a docs/desktop-handoff.md. Jsme na cílovém desktopu; ověř prostředí a spusť první skutečný přepis krátké nahrávky. Zatím nepřidávej nové funkce.“
