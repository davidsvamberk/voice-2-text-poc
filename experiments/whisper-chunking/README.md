# Dělení pro Whisper — 29. 10. 2026

Porovnání cílových délek 20/30/60 sekund × počáteční nápověda / opakovaná slovní nápověda. Používá totožné PCM16/16kHz z `audio-enhancement-20261029-01/01-original/audio-16k.wav` a nezměněnou minutovou ruční referenci (113 hodnocených slov, dvě nejistá slova maskovaná). Nápovědu tvoří dříve zvolené tři oborové fráze, žádné věty z reference. Předchozí text je vypnutý, ostatní ASR volby jsou stejné jako při měření EQ.

`Bridge.java` volá skutečný produkční `BlockPlanner` a `TranscriptAssembler`; nejde o jejich přepis v Pythonu. Pauzy získává skutečný FFmpeg s −35 dB / 0,5 s; hledání do ±1 s, překryv 2 s. Python používá produkční čtečku bloku i normalizaci časových značek v Engine. Jediná přepsaná část je předání explicitních ASR voleb modelu. Výsledné skóre se počítá z reálně spojeného textu včetně zachovaných nejednoznačných překryvů.

| Cílová délka | Délky skutečných vstupů | Initial prompt WER | Hotwords WER | Nejednoznačné překryvy |
| --- | --- | ---: | ---: | ---: |
| 20 s | 21 / 21,527 / 21,473 s | 43,36 % (49 chyb) | 43,36 % (49 chyb) | 2 |
| 30 s | 31 / 31 s | 49,56 % (56 chyb) | 49,56 % (56 chyb) | 0 |
| 60 s | 60 s | 42,48 % (48 chyb) | 44,25 % (50 chyb) | 0 |

Kontrola 60 s / initial prompt byla zopakována na konci: bloky, slova, časy i výsledný text se shodovaly. Čtyřicet osm chyb odpovídá kontrolnímu přepisu z experimentu s EQ. Zkracování tedy v této podobě nepomohlo. Výchozí profil aplikace používá cíl 60 s a počáteční prompt; hotwords zůstávají volitelné. Na dlouhém vstupu může cíl 60 s vést kvůli hledání pauz a překryvu k blokům dlouhým až 63 s. Nejde o záruku jediného interního okna Whisperu.

Výsledek je omezený na jedinou ladicí minutu. Test neporovnává 60s a 600s vstup na dlouhé nahrávce ani neprokazuje, že 60 s je obecné optimum. Ruční kontrola dalších úseků a delší běh jsou další ověřovací krok. Konzervativní spojování nepřiřadí nesouhlasící slova automaticky jedné straně; při kratších blocích tak přibývá příležitostí pro nejednoznačnost.

Z kořene repozitáře (Java třídy předem sestavit přes wrapper, Python přes hlavní Pipenv):

```bash
pipenv run python experiments/whisper-chunking/run.py --check-only
pipenv run python experiments/whisper-chunking/run.py
```

Skript má připnuté cesty modelu, lokálních knihoven a výstupní kořen; pro nový pokus zvolit nový adresář. Skutečná inference potřebuje CUDA knihovny v `LD_LIBRARY_PATH` podle hlavního README. Běží offline, model načítá jednou a mezi variantami čeká deset sekund. Cesty k lokálním Jackson JARům a JDK pro experiment jsou ve skriptu. Není to distribuovaný benchmark pro jiné stroje.

Výsledky jsou v `/home/david/transcriber-runs/260613_090426-20261023-01/samples/whisper-chunking-20261029-01`: společný `comparison.txt`/`comparison.json`, přesné plány, silencedetect log, ASR volby, raw výsledky bloků, vstup pro skutečný assembler, jeho výstup a zarovnání chyb.

Následné ověření hotového `target/transcriber.jar` provedl `verify_cli.py`. Výchozí 60s profil i konfigurace přes `TRANSCRIBER_TARGETSECONDS=20` vytvořily totožné PCM, plány, raw segmenty/slova/časy a spojený text jako benchmark. Skóre bylo znovu 48 a 49 chyb; dvacetisekundová varianta zachovala dvě varování. Opakované spuštění obou dokončených úloh nezměnilo checkpointy ani worker/FFmpeg/probe logy a nespustilo další inferenci. Záznam je v `cli-verification/verification.json`, včetně hashe testovaného JARu. Kontrolní skript lze při přerušené kontrole obnovit pomocí `--resume-verification` bez opakování již hotové inference. Ruční reference a vstupní WAV zůstaly beze změn.
