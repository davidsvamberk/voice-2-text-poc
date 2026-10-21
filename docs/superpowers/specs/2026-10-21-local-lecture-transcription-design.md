# Lokální přepis českých přednášek

## Cíl a rozsah

CLI aplikace převede několikahodinovou českou přednášku na věrný text. Nahrávky mohou obsahovat šum, ozvěnu, vzdálenou řeč i překrývající se hlasy. Prioritou je přesnost před rychlostí. Zpracování audia i textu probíhá výhradně lokálně; modely a závislosti musí být připravené předem.

První verze zachová formulace, opakování a odborné termíny podle rozpoznání modelu a poskytne interpunkci. Neprovádí stylistické úpravy, shrnutí, LLM opravy, automatické odšumování, oddělování mluvčích ani opakované rozpoznávání dokončených úseků. Nezaručuje spolehlivé odhalení nesrozumitelných míst: ASR může vrátit věrohodný chybný text. Kontext nebude sloužit k domýšlení obsahu.

## Prostředí a technologie

- AMD Ryzen 5 7500F, NVIDIA RTX 4070 SUPER s 12 GB VRAM, 32 GB RAM, 2TB NVMe SSD.
- Java 25 a Spring Boot v režimu CLI bez webového serveru.
- Samostatný Python worker pro ASR na GPU.
- FFmpeg pro přípravu pracovního audia.
- Výchozí kandidát: vícejazyčný Whisper large-v3 přes faster-whisper. Jeho přesnost a výkon na cílových nahrávkách zatím nejsou ověřeny. Worker použije explicitní konfiguraci modelu a výpočetního typu, zaznamenanou ve stavu úlohy; při nedostatku paměti nebude automaticky měnit konfiguraci.

Java zůstává hlavním aplikačním stackem podle preference uživatele. Python izoluje integraci ASR nástrojů. Čistě Python řešení bylo odmítnuto kvůli preferenci Javy; Java s nativním přepisovacím procesem zůstává možnou budoucí alternativou. Spring AI a Ollama nejsou pro první verzi potřebné. Není potřeba databázový server, síťová služba ani externí fronta.

## CLI a pracovní adresář

```bash
java -jar transcriber.jar /data/prednaska.mp3
java -jar transcriber.jar /data/prednaska.mp3 --work-dir ./rozpracovano
```

Povinným argumentem je cesta ke vstupnímu souboru. Nepovinný `--work-dir` určuje adresář pro všechny pracovní soubory i výstupy. Bez něj Java použije `Files.createTempDirectory`. Absolutní cestu vypíše při startu i při ukončení úlohy.

Adresář se automaticky nemaže, ani po úspěchu. Dočasné umístění může později uklidit operační systém; pro trvalé uchování slouží explicitní pracovní adresář. Originální audio se nemění.

Nový nebo prázdný adresář lze inicializovat. Adresář s platným stavem stejné úlohy umožní automatické pokračování, bez interaktivního potvrzení. Nesouvisející neprázdný adresář nebo stav jiného vstupu či konfigurace se odmítne. Tentýž adresář nesmí současně používat více procesů; Java zajistí výlučný přístup.

Pracovní adresář obsahuje manifest úlohy, připravené audio, plán bloků, výsledky bloků, diagnostické logy, `transcript.txt` a `transcript.json`. Konkrétní interní názvy a JSON schémata budou definovány implementačním plánem a verzovány.

## Tok zpracování

1. Java ověří vstup, pracovní adresář, dostupnost FFmpeg, Python prostředí, lokálního modelu a GPU. Nic nestahuje. Chyba přípravy skončí s konkrétní diagnostikou před zahájením přepisu.
2. Identifikace úlohy zahrne hash obsahu vstupu, verzi formátu stavu a konfiguraci ovlivňující výsledek: přípravu audia, dělení a ASR model včetně jeho identity a parametrů.
3. FFmpeg vytvoří pracovní audio vhodné pro ASR: mono PCM s frekvencí 16 kHz, bez automatického odšumování. Zachová se vazba časů na originální nahrávku.
4. Vznikne a uloží se plán pracovních bloků, orientačně po 5–10 minutách. Hranice se hledají v blízkých pauzách; pokud vhodná pauza chybí, použije se časová hranice. Krátký překryv chrání slova na rozhraní. Konkrétní hodnoty budou explicitní součástí implementačního plánu a konfigurace, nikoli náhodným rozhodnutím při obnově.
5. Java spustí jeden Python worker pro celou úlohu. Ten načte model jednou a postupně zpracuje nedokončené bloky s explicitně nastavenou češtinou. Pracovní blok není vstupní okno modelu; vnitřní segmentaci obstará ASR engine.
6. Po každém dokončeném bloku se výsledek bezpečně uloží. Dokončené bloky se znovu nepřepisují.
7. Java sestaví výsledný text a strukturovaný přepis, přepočítá lokální časy na čas originálu a odstraní duplicity v překryvech. Odstraňování duplicit se omezuje na společnou časovou oblast sousedních bloků; opakování uvnitř řeči se zachovává. Nejednoznačné spojení nesmí tiše mazat potenciálně odlišnou řeč a bude zaznamenáno v diagnostice.

## Rozhraní Java–Python

Java předá workeru umístění audia, uložený plán a konfiguraci. Standardní výstup workeru obsahuje pouze strukturované JSON zprávy po řádcích: připravenost, průběh, dokončení bloku a chybu. Standardní chybový výstup slouží diagnostickým logům. Zprávy identifikují blok; protokol bude verzovaný.

Výsledky bloků se předávají soubory v pracovním adresáři, nikoli přenosem celého audia přes JSON. Java přijímá dokončení až po ověření odpovídajícího výsledku. Nedokončené či poškozené soubory se nepovažují za hotové bloky.

## Stav, chyby a obnovení

Výsledky a stavové soubory se zapisují do dočasného souboru ve stejném adresáři a následně atomicky přejmenují. Pokud prostředí atomickou operaci nepodporuje, aplikace skončí s diagnostikou, místo aby předstírala zaručený bezpečný zápis.

Při chybě workeru nebo přerušení Java ukončí související procesy, zachová dokončené výsledky a vrátí nenulový návratový kód. Automaticky nepřepne na CPU ani menší model. Uživateli sdělí cestu k úloze a způsob pokračování pomocí `--work-dir`.

Obnovení ověří vstup a konfiguraci. Přeskočí platné dokončené bloky a zpracuje zbylé. Rozpracovaný blok lze po pádu zpracovat znovu; to je obnova přerušené práce, nikoli další rozpoznávací průchod kvůli kvalitě. Pád při závěrečném skládání vyžaduje jen nové sestavení výstupů z uložených bloků. Úspěšné dokončení se oznámí až po bezpečném uložení obou výstupů.

## Výstupy

- `transcript.txt`: věrný text v UTF-8 bez automatických jazykových oprav.
- `transcript.json`: verze schématu, identita vstupu, konfigurace zpracování a segmenty s textem a začátkem/koncem v sekundách od začátku původní nahrávky. Dostupné diagnostické údaje ASR lze uložit, ale nejsou prezentovány jako kalibrovaná pravděpodobnost správnosti. Vazba na původní bloky zůstává dohledatelná.

Přesnost časových značek závisí na ASR; první verze neslibuje přesné zarovnání jednotlivých slov.

## Ověření první verze

Podle výslovného požadavku uživatele pouze základní automatizované testy. Testy řídicí logiky použijí malé syntetické vstupy a náhradu workeru; nevyžadují GPU ani stahování modelů.

Pokryjí parsování CLI a volbu pracovního adresáře, odmítnutí cizího obsahu a neshody vstupu/konfigurace, přeskočení hotových bloků při obnově, ignorování neúplného zápisu, propagaci chyby workeru a základní spojování překryvů s přepočtem časů. Příklad s legitimně opakovanými slovy ověří, že spojování nemaže opakování mimo překryv.

Ruční referenční přepisy, měření chybovosti, benchmark modelů a kvality úprav audia nejsou součástí první verze. Automatické testy ověřují chování aplikace, nikoli přesnost rozpoznávání na nekvalitních přednáškách. Provozní kompatibilita konkrétního modelu, GPU a Python závislostí zůstává do skutečného spuštění neověřená.

## Další postup

Tento dokument zachycuje dohodnutý návrh, nikoli povolení implementace. Po kontrole dokumentu uživatelem vznikne implementační plán s konkrétním dělením bloků, kontrakty, závislostmi a základními testy. Post-processing, Ollama/Spring AI, odšumování a další průchody zůstávají mimo tento rozsah.
