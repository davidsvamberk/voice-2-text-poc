# Lokální přepis českých přednášek

Java CLI připraví audio přes FFmpeg, spustí jeden lokální Python ASR worker a průběžně ukládá výsledky jednotlivých bloků. Dokončené bloky při obnově znovu nerozpoznává. Samotný přepis ani sestavení výstupů nic nestahují.

Pro pokračování po čistém klonu viz [předání na desktop a první skutečný přepis](docs/desktop-handoff.md).

## Prostředí a build

Potřebujete Linux x86_64, JDK 25, CPython 3.12, systémové `ffmpeg` a `ffprobe`. Python lock je určen pro Linux x86_64 s glibc alespoň 2.28; není přenositelný na jiné platformy. Všechny příkazy spouštějte z kořene projektu.

```bash
env JAVA_HOME=/home/david/.sdkman/candidates/java/25.0.2-tem ./mvnw clean verify
pipenv run python -B -m unittest discover -s worker/tests -v
/home/david/.sdkman/candidates/java/25.0.2-tem/bin/java -jar target/transcriber.jar --help
```

Cestu k JDK přizpůsobte své instalaci. Maven spouštějte přes wrapper. Testy používají syntetická data a falešný worker, nevyžadují CUDA ani model; procesové testy potřebují `/usr/bin/python3`. Při prvním buildu může být nutný internet pro Maven závislosti.

Volitelné testy časové osy použijí skutečný FFmpeg z `PATH`; pokud není dostupný, přeskočí se pouze tyto scénáře. Vlastní binární soubor lze zvolit přes `./mvnw -Dtranscriber.test.ffmpeg=/cesta/ffmpeg -Dtest=AudioPreparerTest test`. Testují krátká bezeztrátová média a porovnávají připravené PCM včetně ticha; pro čtení délky WAV nahrazují pouze ffprobe pomocí JDK. Základní testy s falešným runnerem běží vždy.

Distribuce obsahuje `target/transcriber.jar`, celý adresář `worker/`, samostatný Python venv a lokální model. JAR Python, jeho knihovny ani model nezabaluje. Nahrávky, modely a venv necommitujte.

## Python a CUDA — jednorázová příprava

```bash
export PIPENV_VENV_IN_PROJECT=1
export PIPENV_DONT_LOAD_ENV=1
pipenv --python /cesta/k/python3.12
pipenv verify
pipenv sync
pipenv run python -B -m unittest discover -s worker/tests -v
```

`Pipfile.lock` připíná závislosti včetně hashů pro instalaci přes Pipenv. Zachovává všechny verze i hashe wheelů z původního `worker/requirements.lock`, včetně faster-whisper 1.2.1, CTranslate2 4.6.0, PyAV 16.0.1 a NumPy 2.3.4, a přidává NVIDIA runtime knihovny. Původní lock zůstává jako reference pro Linux x86_64. Neměňte verze při obnovování již založené úlohy. Instalace a stažení modelu potřebují internet; proveďte je před přepisem.

GPU musí podporovat CUDA a výpočty `float16`. Připravte kompatibilní NVIDIA ovladač; cuBLAS pro CUDA 12 a cuDNN 9 pro CUDA 12 instaluje Pipenv podle [dokumentace faster-whisper](https://github.com/SYSTRAN/faster-whisper#gpu). Před spuštěním probe nebo Javy zpřístupněte jejich knihovny linkeru:

```bash
export LD_LIBRARY_PATH="$PWD/.venv/lib/python3.12/site-packages/nvidia/cublas/lib:$PWD/.venv/lib/python3.12/site-packages/nvidia/cudnn/lib:$PWD/.venv/lib/python3.12/site-packages/nvidia/cuda_nvrtc/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
```

Ovladač ani systémový FFmpeg nejsou součástí Python locku. Při selhání GPU nebo nedostatku paměti aplikace skončí; automaticky nemění model, přesnost ani zařízení. Automatizované testy neověřují skutečnou GPU inferenci.

## Model stáhnout zvlášť, s připnutou revizí

Používáme vícejazyčný large-v3 převedený do CTranslate2: [Systran/faster-whisper-large-v3](https://huggingface.co/Systran/faster-whisper-large-v3), revize [edaa852ec7e145841d8ffdb056a99866b5f0a478](https://huggingface.co/Systran/faster-whisper-large-v3/commit/edaa852ec7e145841d8ffdb056a99866b5f0a478). Následující příprava používá `huggingface_hub` z locku; stahování není součástí běhu CLI. [Dokumentace stahování s revizí](https://huggingface.co/docs/huggingface_hub/guides/download) popisuje použité API.

```bash
pipenv run python - <<'PYMODEL'
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
PYTHONPATH=worker pipenv run python -m transcriber_worker --probe --model-dir "$PWD/models/large-v3"
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
| `transcriber.target-seconds` | `TRANSCRIBER_TARGETSECONDS` | `60` |
| `transcriber.search-seconds` | `TRANSCRIBER_SEARCHSECONDS` | `1` |
| `transcriber.overlap-seconds` | `TRANSCRIBER_OVERLAPSECONDS` | `2` |
| `transcriber.condition-on-previous-text` | `TRANSCRIBER_CONDITIONONPREVIOUSTEXT` | `false` |
| `transcriber.initial-prompt` | `TRANSCRIBER_INITIALPROMPT` | pedagogická nápověda uvedená níže |
| `transcriber.hotwords` | `TRANSCRIBER_HOTWORDS` | prázdné |

```bash
export TRANSCRIBER_PYTHON="$PWD/.venv/bin/python"
export TRANSCRIBER_WORKERDIR="$PWD/worker"
export TRANSCRIBER_MODELDIR="$PWD/models/large-v3"

java -jar target/transcriber.jar "/data/přednáška 1.mp3"
java -jar target/transcriber.jar "/data/přednáška 1.mp3" --work-dir "./rozpracováno"
```

`java` musí být z JDK 25. CLI přijímá pouze INPUT, `--work-dir DIR` a `--help`; vlastnosti nedávejte jako další CLI přepínače. `--help` funguje bez Spring kontextu, Pythonu, FFmpeg, modelu i GPU.

Bez `--work-dir` vznikne systémový dočasný adresář; jeho absolutní cestu aplikace vypíše. Sama jej nemaže, ale operační systém může dočasné adresáře později uklidit. Pro trvalé uchování zvolte vlastní adresář. Vstup a model musejí být mimo workspace; model a workspace se nesmějí vnořovat. Workspace odmítá symlinky a soubory odkazující na originální vstup. Originál se nepřepisuje ani nemaže.

Příprava: první audio stream, mono PCM s16le 16 kHz, WAV/RF64 podle velikosti, bez odšumování. Výchozí cílová délka bloku je 60 s před přidáním překryvu. Hranice hledá nejbližší pauzu v rozsahu ±1 s (`silencedetect` −35 dB, 0,5 s). Překryv má celkem 2 s, tedy po sekundě na každou stranu hranice; vnitřní bloky tak obvykle mají 62 s, nejvýše 63 s při posunu do pauzy. Poslední blok může být kratší. Délku, hledání pauz a překryv lze změnit tabulkou výše. Cíl musí být kladný, hledání nezáporné a menší než cíl a polovina překryvu menší než rozdíl cíle a hledání.

ASR používá `cuda`, `float16`, `language=cs`, `task=transcribe`, `beam_size=5`, `temperature=0.0`, `vad_filter=false`, `word_timestamps=true`. Předchozí rozpoznaný text se standardně nepřenáší do dalšího dekódovacího okna. Každé volání pro aplikační blok dostane počáteční nápovědu:

```text
Pedagogická intervence, speciálně pedagogické centrum, školské poradenské zařízení.
```

Nápověda je doménová a lze ji změnit; pro jiné téma ji upravte nebo vypněte nastavením prázdné hodnoty. `initial-prompt` se při vypnutém předchozím textu vztahuje k prvnímu internímu oknu daného volání, nikoli automaticky ke všem oknům Whisperu. Volitelný seznam `hotwords` se předává každému internímu oknu. Tyto dvě možnosti jsou nezávislé; opakovaná nápověda v našem krátkém pokusu nepřinesla zlepšení, proto není výchozí. Všechny uvedené volby i verze inference knihoven tvoří identitu úlohy: změna nastavení vyžaduje nový workspace, nesmí převzít výsledky jiné konfigurace.

Příklad externího `application.properties` pro kratší bloky a opakovanou slovní nápovědu:

```properties
transcriber.target-seconds=20
transcriber.search-seconds=1
transcriber.overlap-seconds=2
transcriber.condition-on-previous-text=false
transcriber.initial-prompt=
transcriber.hotwords=Pedagogická intervence, speciálně pedagogické centrum, školské poradenské zařízení.
```

Výchozí minutový profil vychází z omezeného [porovnání dělení](experiments/whisper-chunking/README.md): na stejné ruční referenci nebyly bloky 20/30 s přesnější. Není to důkaz optimální délky pro celou nahrávku; před plošným závěrem je potřeba další ruční reference.

Při převodu výsledku modelu worker srovná malé odchylky časů do rozsahu bloku a vzestupného pořadí. Každý čas smí posunout nejvýše o 0,05 s; text ani pořadí slov nemění a úpravu zaznamená do logu. Větší chyby a nekonečné/NaN hodnoty odmítá. Validace uložených checkpointů zůstává striktní.

Časy přepisu odpovídají přehrávání originálu: nula je začátek kontejneru (`format.start_time`), nikoli první audio vzorek ani absolutní čas uložených PTS. Příprava použije `-copyts -start_at_zero` a `aresample=16000:async=1:first_pts=0:min_comp=0:min_hard_comp=0`. Pozdější začátek audia i mezery mezi audio rámci vyplní tichem, záporné vzorky před počátkem ořízne. `async=1` dovoluje pouze doplnění/ořez, bez změny rychlosti řeči; nulové prahy zachovají i krátké mezery. Viz [FFmpeg časové volby](https://ffmpeg.org/ffmpeg.html#Advanced-options) a [resampler](https://ffmpeg.org/ffmpeg-resampler.html#Resampler-Options). Vazba vychází z časových značek vstupu; ztracené nebo chybné značky nedokáže obnovit.

Tyto volby jsou součástí hashe konfigurace. Workspace vytvořený starší přípravou, která mezery zkracovala, se odmítne i při dokončených blocích; pro novou přípravu použijte nový prázdný workspace.

## Přerušení, chyba a obnovení

```bash
java -jar target/transcriber.jar "/data/přednáška 1.mp3" --work-dir "/absolutní/cesta/rozpracováno"
```

Stejný příkaz slouží i k obnově, bez potvrzování. Java zamkne workspace, ověří hash vstupu, modelu a účinné konfigurace a přijme pouze validní checkpointy. Neznámá verze schématu či poškozený manifest je chyba; neúplné/poškozené výsledky bloků zůstávají pending. Před novým rozpoznáváním probe ověří shodu aktuálních verzí knihoven s původním manifestem, který se při obnově nepřepisuje. Při nesouladu obnovte původní prostředí nebo zvolte nový prázdný workspace.

Starší manifesty bez polí `initialPrompt`/`hotwords` se čtou jako běhy bez nápovědy. Jejich obnovení vyžaduje explicitně původní nastavení: cíl 600 s, hledání 30 s, překryv 2 s, předchozí text zapnutý a obě nápovědy prázdné. Nový výchozí profil patří do nového workspace.

Výsledek každého bloku obsahuje také `blockStart` a `blockEnd`, které musejí přesně odpovídat uloženému plánu. Přegenerování plánu tak nemůže přiřadit starému textu jiný časový posun. Starší checkpointy bez těchto hranic se nepovažují za dokončené a příslušné bloky se zpracují znovu.

Pokud jsou všechny bloky hotové, CLI jen znovu sestaví TXT/JSONL bez Pythonu, GPU a FFmpeg, i když připravené audio chybí. Originál a totožný lokální model jsou stále potřebné pro ověření identity. Pokud bloky chybějí a WAV je poškozený, chybí nebo nesouhlasí formát/délka, vznikne z originálu nové audio při zachování platného plánu a hotových bloků. Hotové bloky se znovu nezpracují; rozpracovaný blok může být po pádu zopakován.

Při Ctrl+C/interruptu se ukončí řízené procesy, zachovají checkpointy a uvolní lock až po pokusu o zastavení procesů. Ukončování potomků je best effort podle možností Java ProcessHandle. Kódy: 0 úspěch/help, 2 chyba argumentů, 1 provozní chyba, 130 přerušení. Před úspěchem se znovu ověří hash vstupu/modelu. Při neshodě se atomicky uloží `invalidated.json`; tento workspace už nelze obnovit ani po vrácení původního vstupu/modelu. Výsledky zůstanou zachované pro kontrolu, pro další přepis použijte nový workspace. Pokud selže úvodní probe ještě před vznikem manifestu, po opravě prostředí lze použít stejný workspace obsahující pouze vlastní `logs/java.log` a `logs/probe.log`. Nesouvisející neprázdné adresáře se odmítají.

## Výstupy a omezení

Workspace obsahuje `.lock`, `manifest.json`, `request.json`, `audio.wav`, `plan.json`, `blocks/000000.json` atd., `logs/java.log`, `logs/worker.log` a diagnostiku FFmpeg/probe. JSON mají `schemaVersion: 1`, kódování UTF-8 a konečné časy v sekundách. Soubory `.tmp` nejsou hotové výsledky. Checkpointy i výstupy se publikují atomickým přejmenováním; filesystem musí atomický přesun podporovat.

Atomický zápis chrání před neúplným checkpointem při přerušení procesu. Bez synchronizace adresáře nezaručuje zachování posledního přejmenování při výpadku napájení; ztracený checkpoint může vyžadovat zopakování bloku.

`transcript.txt` obsahuje rozpoznaný text; při prázdném rozpoznání je prázdný. `transcript.jsonl` uchovává identitu vstupu, konfiguraci, globální časové intervaly, vazbu na bloky a varování `AMBIGUOUS_OVERLAP`. Při nejednoznačném překryvu se potenciálně odlišná řeč zachová a počet takových míst se vypíše na konzoli s odkazem na JSON. Do TXT se nevkládají dodatečné značky. Časové značky nejsou slibem přesného zarovnání slov. Pokud zápis druhého výstupu selže, úloha není úspěšná; opakované spuštění oba výstupy obnoví z checkpointů.

JSONL je UTF-8 bez BOM, s jedním kompletním JSON objektem na fyzickém řádku a koncovým LF. První záznam má `type: "metadata"`, `schemaVersion`, `inputSha256` a `config`. Následují záznamy `type: "segment"` v časovém pořadí s poli `start`, `end`, `text`, `blockIds`; nakonec záznamy `type: "warning"` s poli `leftBlockId`, `rightBlockId`, `code`. Časy jsou globální sekundy od počátku originálu a ID bloků začínají nulou. Odřádkování uvnitř textu je escapované jako `\n`, nerozděluje záznam. I prázdný přepis obsahuje metadata.

```jsonl
{"type":"metadata","schemaVersion":1,"inputSha256":"…","config":{}}
{"type":"segment","start":0.0,"end":4.2,"text":"Dobrý den.","blockIds":[0]}
{"type":"warning","leftBlockId":0,"rightBlockId":1,"code":"AMBIGUOUS_OVERLAP"}
```

Ukázka má zkrácený hash a konfiguraci. Soubor vzniká až po dokončení všech bloků, přes dočasný soubor a atomické přejmenování; nejde o průběžný event log. Checkpointy zůstávají jednotlivé JSON soubory. Starší `transcript.json` se nepřepisuje ani nemaže; opakované spuštění nad dokončenými checkpointy vytvoří nový JSONL bez inference. Pro další zpracování používejte JSONL, například:

```bash
jq -c 'select(.type == "segment") | {start, end, text}' /cesta/workspace/transcript.jsonl
```

Neprobíhají LLM opravy, shrnutí, diarizace ani další kvalitativní průchod. Ticho, šum a překrývající se hlasy mohou vést k halucinacím nebo věrohodnému chybnému textu. Kvalita byla měřena na jediné ručně přepsané minutě; širší přesnost a výkon na dlouhých nahrávkách nejsou těmito pokusy ověřené.
