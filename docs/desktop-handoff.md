# Předání na desktop — 22. 10. 2026

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

4. Vytvořit Python prostředí s připnutými závislostmi:

   ```bash
   python3.12 -m venv .venv
   .venv/bin/python -m pip install --require-hashes --only-binary=:all: -r worker/requirements.lock
   ```

5. Podle skriptu v README stáhnout `Systran/faster-whisper-large-v3`, revizi `edaa852ec7e145841d8ffdb056a99866b5f0a478`, do `models/large-v3`. Zachovat i vytvořený `provenance.json`. Příprava závislostí a modelu potřebuje internet, samotný přepis už nic nestahuje. Po založení úlohy obsah modelu neměnit.
6. Ověřit probe:

   ```bash
   PYTHONPATH=worker .venv/bin/python -m transcriber_worker --probe --model-dir "$PWD/models/large-v3"
   ```

   Probe kontroluje prostředí a lokální soubory; nenačítá váhy modelu a neprovádí přepis. Teprve další krok ověří skutečnou inferenci.

## První skutečný přepis

Přenést krátkou testovací nahrávku a spustit například:

```bash
java -jar target/transcriber.jar "$HOME/Downloads/test-rec.aac" \
  --work-dir "$HOME/transcriber-runs/test-rec-01"
```

Pracovní adresář má být nový nebo patřit stejné úloze. Držet jej mimo repozitář; vstup i model musí být mimo něj. Bez `--work-dir` Java vytvoří dočasný adresář a vypíše jeho cestu, ale pro pokračování mezi spuštěními je vhodnější vlastní trvalý adresář.

Po doběhnutí zkontrolovat návratový kód 0, `transcript.txt`, `transcript.json` a logy v pracovním adresáři. Porovnat text poslechem s originálem, zkontrolovat časové značky a případná varování. Zapsat délku nahrávky, dobu běhu a pozorovanou spotřebu VRAM jako první praktické měření. Úspěšné dokončení samo o sobě nepotvrzuje přesnost přepisu.

Zopakovat stejný příkaz: dokončené bloky se mají převzít z checkpointů bez nového rozpoznávání. Přerušení a obnovu uprostřed přepisu vyzkoušet až s delší nahrávkou. Poté přejít na delší a hlučnější vzorek; o dalších úpravách rozhodovat podle konkrétních chyb.

## Co si pohlídat při pokračování

- Výchozí cesty `.venv/bin/python`, `worker` a `models/large-v3` jsou relativní k adresáři spuštění. Při změně cest použít nastavení z README; proměnné se jmenují `TRANSCRIBER_WORKERDIR` a `TRANSCRIBER_MODELDIR` bez dalšího podtržítka.
- GPU chyba nebo nedostatek paměti ukončí úlohu; není automatický přechod na CPU ani menší model. Nejdřív přečíst logy, teprve potom měnit konfiguraci.
- Výsledky bloků obsahují `blockStart` a `blockEnd`. Starší checkpointy bez těchto údajů se znovu zpracují.
- `AMBIGUOUS_OVERLAP` znamená zachovaný nejednoznačný překryv, případně s opakováním textu. Podrobnosti jsou v JSON; TXT zůstává bez přidaných značek.
- Změna vstupu nebo modelu může vytvořit `invalidated.json`; potom je potřeba nový pracovní adresář. Pro obnovu zachovat také verze knihoven a konfiguraci.
- Atomické ukládání chrání před neúplnými checkpointy při přerušení procesu; nezaručuje odolnost proti výpadku napájení.

Další zadání pro pokračování může znít: „Přečti README a docs/desktop-handoff.md. Jsme na cílovém desktopu; ověř prostředí a spusť první skutečný přepis krátké nahrávky. Zatím nepřidávej nové funkce.“
