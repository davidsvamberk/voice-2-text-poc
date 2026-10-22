# Lokální přepis českých přednášek

Java CLI připraví audio přes FFmpeg, spustí jeden lokální Python ASR worker a bezpečně ukládá výsledky jednotlivých bloků. Dokončené bloky při obnově znovu nerozpoznává. Samotný přepis ani sestavení výstupů nic nestahují.

## Prostředí a build

Potřebujete Linux x86_64, JDK 25, CPython 3.12, systémové `ffmpeg` a `ffprobe`. Python lock je určen pro Linux x86_64 s glibc alespoň 2.28; není přenositelný na jiné platformy. Všechny příkazy spouštějte z kořene projektu.

```bash
env JAVA_HOME=/home/david/.sdkman/candidates/java/25.0.2-tem ./mvnw clean verify
python3 -m unittest discover -s worker/tests -v
/home/david/.sdkman/candidates/java/25.0.2-tem/bin/java -jar target/transcriber.jar --help
```

Cestu k JDK přizpůsobte své instalaci. Maven spouštějte přes wrapper. Testy používají syntetická data a falešný worker, nevyžadují CUDA ani model; procesové testy potřebují `/usr/bin/python3`. Při prvním buildu může být nutný internet pro Maven závislosti.

Distribuce obsahuje `target/transcriber.jar`, celý adresář `worker/`, samostatný Python venv a lokální model. JAR Python, jeho knihovny ani model nezabaluje. Nahrávky, modely a venv necommitujte.

## Python a CUDA — jednorázová příprava

```bash
python3.12 -m venv .venv
.venv/bin/python -m pip install --require-hashes --only-binary=:all: -r worker/requirements.lock
```

Lock připíná úplnou sadu inference závislostí i SHA-256 konkrétních wheelů, včetně faster-whisper 1.2.1, CTranslate2 4.6.0, PyAV 16.0.1 a NumPy 2.3.4. Neměňte verze při obnovování již založené úlohy. Instalace a stažení modelu mohou potřebovat internet; proveďte je před přepisem.

GPU musí podporovat CUDA a výpočty `float16`. Připravte kompatibilní NVIDIA ovladač, cuBLAS pro CUDA 12 a cuDNN 9 pro CUDA 12 podle [dokumentace faster-whisper](https://github.com/SYSTRAN/faster-whisper#gpu) a NVIDIA. Systémový dynamický linker musí knihovny vidět, případně nastavte `LD_LIBRARY_PATH` na jejich adresáře před spuštěním Javy. CUDA knihovny a ovladač nejsou součástí Python locku. Při selhání GPU nebo nedostatku paměti aplikace skončí; automaticky nemění model, přesnost ani zařízení. Konkrétní GPU kombinace a skutečná inference nebyly touto sadou testů ověřeny.

## Model stáhnout zvlášť, s připnutou revizí

Používáme vícejazyčný large-v3 převedený do CTranslate2: [Systran/faster-whisper-large-v3](https://huggingface.co/Systran/faster-whisper-large-v3), revize [edaa852ec7e145841d8ffdb056a99866b5f0a478](https://huggingface.co/Systran/faster-whisper-large-v3/commit/edaa852ec7e145841d8ffdb056a99866b5f0a478). Následující příprava používá `huggingface_hub` z locku; stahování není součástí běhu CLI. [Dokumentace stahování s revizí](https://huggingface.co/docs/huggingface_hub/guides/download) popisuje použité API.

```bash
.venv/bin/python - <<'PYMODEL'
from datetime import datetime, timezone
from pathlib import Path
import json
import shutil
from huggingface_hub import snapshot_download

repo = "Systran/faster-whisper-large-v3"
revision = "edaa852ec7e145841d8ffdb056a99866b5f0a478"
names = ["config.json", "model.bin", "preprocessor_config.json", "tokenizer.json", "vocabulary.json"]
snapshot = Path(snapshot_download(repo_id=repo, revision=revision, allow_patterns=names))
target = Path("models/large-v3")
target.mkdir(parents=True, exist_ok=False)
for name in names:
    # Cache může obsahovat symlinky; do model-dir kopírujeme skutečné soubory.
    shutil.copyfile(snapshot / name, target / name)
(target / "provenance.json").write_text(json.dumps({
    "repo": repo, "revision": revision, "files": names,
    "downloadedAt": datetime.now(timezone.utc).isoformat(),
}, ensure_ascii=False, indent=2), encoding="utf-8")
PYMODEL
```

Model-dir musí obsahovat běžné soubory, žádné symlinky. Java streamovaně hashuje celý strom včetně záznamu původu; po založení úlohy neměňte ani tento záznam. Umístění modelu lze změnit při zachování totožného obsahu. Před přepisem lze ručně zkontrolovat importy, modelové soubory a CUDA bez načtení modelu:

```bash
PYTHONPATH=worker .venv/bin/python -m transcriber_worker --probe --model-dir "$(pwd)/models/large-v3"
```

## Nastavení a spuštění

Spring properties se načítají z prostředí nebo například z externího `application.properties`. Relativní cesty se vyhodnocují vůči adresáři spuštění; jednoduché názvy `ffmpeg` a `ffprobe` se hledají v `PATH`.

| Property | Proměnná prostředí | Výchozí hodnota |
| --- | --- | --- |
| `transcriber.python` | `TRANSCRIBER_PYTHON` | `.venv/bin/python` |
| `transcriber.worker-dir` | `TRANSCRIBER_WORKERDIR` | `worker` |
| `transcriber.model-dir` | `TRANSCRIBER_MODELDIR` | `models/large-v3` |
| `transcriber.ffmpeg` | `TRANSCRIBER_FFMPEG` | `ffmpeg` |
| `transcriber.ffprobe` | `TRANSCRIBER_FFPROBE` | `ffprobe` |

```bash
export TRANSCRIBER_PYTHON="$PWD/.venv/bin/python"
export TRANSCRIBER_WORKERDIR="$PWD/worker"
export TRANSCRIBER_MODELDIR="$PWD/models/large-v3"

java -jar target/transcriber.jar "/data/přednáška 1.mp3"
java -jar target/transcriber.jar "/data/přednáška 1.mp3" --work-dir "./rozpracováno"
```

`java` musí být z JDK 25. CLI přijímá pouze INPUT, `--work-dir DIR` a `--help`; vlastnosti nedávejte jako další CLI přepínače. `--help` funguje bez Spring kontextu, Pythonu, FFmpeg, modelu i GPU.

Bez `--work-dir` vznikne systémový dočasný adresář; jeho absolutní cestu aplikace vypíše. Sama jej nemaže, ale operační systém může dočasné adresáře později uklidit. Pro trvalé uchování zvolte vlastní adresář. Vstup a model musejí být mimo workspace; model a workspace se nesmějí vnořovat. Workspace odmítá symlinky a soubory odkazující na originální vstup. Originál se nepřepisuje ani nemaže.

Pevná konfigurace: první audio stream, mono PCM s16le 16 kHz, WAV/RF64 podle velikosti, bez odšumování; orientačně 600s bloky, hledání pauz ±30 s (`silencedetect` −35 dB, 0,5 s), překryv 2 s. ASR: `cuda`, `float16`, `language=cs`, `task=transcribe`, `beam_size=5`, `temperature=0.0`, `condition_on_previous_text=true`, `vad_filter=false`, `word_timestamps=true`, bez initial promptu. Konfigurace a verze inference knihoven tvoří identitu úlohy.

## Přerušení, chyba a obnovení

```bash
java -jar target/transcriber.jar "/data/přednáška 1.mp3" --work-dir "/absolutní/cesta/rozpracováno"
```

Stejný příkaz slouží i k obnově, bez potvrzování. Java zamkne workspace, ověří hash vstupu, modelu a účinné konfigurace a přijme pouze validní checkpointy. Neznámá verze schématu či poškozený manifest je chyba; neúplné/poškozené výsledky bloků zůstávají pending. Před novým rozpoznáváním probe ověří shodu aktuálních verzí knihoven s původním manifestem, který se při obnově nepřepisuje. Při nesouladu obnovte původní prostředí nebo zvolte nový prázdný workspace.

Pokud jsou všechny bloky hotové, CLI jen znovu sestaví TXT/JSON bez Pythonu, GPU a FFmpeg, i když připravené audio chybí. Originál a totožný lokální model jsou stále potřebné pro ověření identity. Pokud bloky chybějí a WAV je poškozený, chybí nebo nesouhlasí formát/délka, vznikne z originálu nové audio při zachování platného plánu a hotových bloků. Hotové bloky se znovu nezpracují; rozpracovaný blok může být po pádu zopakován.

Při Ctrl+C/interruptu se ukončí řízené procesy, zachovají checkpointy a uvolní lock až po pokusu o zastavení procesů. Ukončování potomků je best effort podle možností Java ProcessHandle. Kódy: 0 úspěch/help, 2 chyba argumentů, 1 provozní chyba, 130 přerušení. Před úspěchem se znovu ověří hash vstupu/modelu. Pokud selže úvodní probe ještě před vznikem manifestu, diagnostika zůstane zachovaná; po opravě prostředí použijte nový prázdný workspace. Nesouvisející neprázdné adresáře se odmítají.

## Výstupy a omezení

Workspace obsahuje `.lock`, `manifest.json`, `request.json`, `audio.wav`, `plan.json`, `blocks/000000.json` atd., `logs/java.log`, `logs/worker.log` a diagnostiku FFmpeg/probe. JSON mají `schemaVersion: 1`, kódování UTF-8 a konečné časy v sekundách. Soubory `.tmp` nejsou hotové výsledky. Checkpointy i výstupy se publikují atomickým přejmenováním; filesystem musí atomický přesun podporovat.

`transcript.txt` obsahuje rozpoznaný text; při prázdném rozpoznání je prázdný. `transcript.json` uchovává identitu vstupu, konfiguraci, globální časové intervaly, vazbu na bloky a varování `AMBIGUOUS_OVERLAP`. Při nejednoznačném překryvu se potenciálně odlišná řeč zachová. Časové značky nejsou slibem přesného zarovnání slov. Pokud zápis druhého výstupu selže, úloha není úspěšná; opakované spuštění oba výstupy obnoví z checkpointů.

Neprobíhají LLM opravy, shrnutí, diarizace ani další kvalitativní průchod. Ticho, šum a překrývající se hlasy mohou vést k halucinacím nebo věrohodnému chybnému textu. Kvalita na reálných nahrávkách nebyla měřena, GPU inference ani výkon nebyly benchmarkovány.
