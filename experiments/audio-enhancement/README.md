# EQ a úprava dynamiky — 29. 10. 2026

Samostatný pokus na zachovaném minutovém WAV `manual-reference-003500-003600.wav`, tedy 35:00–36:00 původní nahrávky. Referenční text ani jeho audio se nepřepisují. Zpracování probíhá ve FFmpegu na 48 kHz s plovoucí řádovou čárkou. Pro poslech se ukládá PCM24/48 kHz, pro Whisper PCM16/16 kHz. Obě kopie mají přesně 60 sekund.

Čtyři předem stanovené varianty:

- **Originál:** bez filtrů.
- **EQ:** high-pass 90 Hz, dva póly; široké zvýraznění 2,2 kHz o 2 dB (šířka jedna oktáva); low-pass 7 kHz, dva póly.
- **Dynamika:** kompresor s detekcí špiček, prahem −21 dBFS, poměrem 4:1, attack 1 ms a release 80 ms; následně `dynaudnorm` s 250ms rámci, vyhlazením přes 31 rámců, maximálním zesílením 8× a cílovým RMS 0,1; na konci limiter se stropem −1 dBFS, attack 5 ms, release 80 ms a kompenzací latence. Úplné volby jsou ve skriptu a uloženém manifestu.
- **EQ + dynamika:** obě předchozí úpravy v tomto pořadí.

Všechny varianty používají faster-whisper large-v3, CUDA float16 a totožné volby: čeština, beam 5, teplota 0, vypnutý předchozí text, vypnuté VAD, časové značky a pevná nápověda `Pedagogická intervence, speciálně pedagogické centrum, školské poradenské zařízení.`. Reference neslouží jako prompt. WER zachovává předchozí normalizaci: 113 hodnocených slov, dvě nejistá slova maskovaná, ignorovaná interpunkce a velikost písmen; původní pravopis se zachovává. `frozen_alignment.py` obsahuje nezměněnou původní funkci zarovnání. V každém běhu se počet chyb kontroluje také nezávislým výpočtem Levenshteinovy vzdálenosti po odstranění maskovaných slov.

## Spuštění

Z kořene repozitáře v jeho původním Pipenv:

```bash
export PIPENV_VENV_IN_PROJECT=1
export PIPENV_IGNORE_VIRTUALENVS=1
export PIPENV_DONT_LOAD_ENV=1
pipenv run python experiments/audio-enhancement/run.py --check-only
pipenv run python experiments/audio-enhancement/run.py
```

GPU běh potřebuje cuBLAS/cuDNN v `LD_LIBRARY_PATH` podle hlavního README. Skript nastavuje offline režim a FFmpeg používá s vlastními lokálními knihovnami. Nic neinstaluje. Cesty a výstupní adresář jsou připnuté ve skriptu; pro novou sérii je potřeba nový adresář. `--prepare-only` připraví audio bez načítání modelu; následující spuštění může převzít připravené soubory se shodným manifestem. Existující výsledky inference se nepřepisují.

## Výsledky

| Varianta | Chyby | WER | RMS dBFS | Špička dBFS | Špička minus RMS |
| --- | ---: | ---: | ---: | ---: | ---: |
| Originál | 48 | 42,48 % | −32,88 | −3,91 | 28,97 dB |
| EQ | 48 | 42,48 % | −32,95 | −3,80 | 29,14 dB |
| Dynamika | 47 | 41,59 % | −26,48 | −4,04 | 22,45 dB |
| EQ + dynamika | 48 | 42,48 % | −26,55 | −3,64 | 22,92 dB |

Statistiky se vztahují k 16kHz vstupu ASR. Dynamika zvýšila průměrnou úroveň o 6,40 dB a snížila rozdíl špička–RMS o 6,52 dB. Žádný soubor neměl vzorky na hranici přebuzení. Vzájemný časový posun podle korelace byl nejvýše jeden vzorek (0,0625 ms) na 16 kHz.

Přínos pro přepis se neprokázal: jediná chyba rozdílu na jedné ladicí minutě není přesvědčivé zlepšení. Dynamická varianta navíc obsahuje významově chybnou záměnu „teroristický pracovník“. Samotná nižší WER ji proto nečiní lepším přepisem. Úprava dynamiky může být kandidátem pro poslech; pro ASR zatím zůstává původní audio.

Originál byl zopakován na konci série: text, slova i časové značky jsou totožné. Hashe zdroje, ruční reference a lokálního modelu zůstaly shodné. Celkem pět přepisů s desetisekundovými pauzami. Doba včetně načtení a pauz 62,437 s; nejvyšší pozorovaná teplota GPU 70 °C, celková GPU paměť 5 513 MiB (vzorkování jednou za sekundu).

Aktuální kontrola byla nově připravena ze zachovaného minutového WAV. Dřívějších 38,94 % pocházelo z první minuty jiného připraveného PCM souboru, který byl mezitím na přání uživatele smazán. Tento starší výsledek proto není kontrolou této série. Smysluplné je porovnání čtyř nynějších variant mezi sebou.

Výsledky, kompletní příkazy filtrů, zarovnání chyb a poslechové soubory jsou pod `/home/david/transcriber-runs/260613_090426-20261023-01/samples/audio-enhancement-20261029-01`. V každé složce je `listening-48k.wav`, `audio-16k.wav`, `processing.json`, `result.json` a `transcript.txt`; společný přehled je `comparison.txt` a `comparison.json`. Opakovaná kontrola má vlastní přepis a odkazuje na původní audio, nevytváří další kopii WAV.
