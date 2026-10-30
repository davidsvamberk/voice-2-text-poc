# Parakeet TDT 0.6B v3 — samostatný pokus

> Archiv experimentu (29. 10. 2026): pro další práci byl zvolen Whisper s nápovědou a `condition_on_previous_text=False`. Na přání uživatele byly smazány výsledky a odvozené vstupy dosavadních pokusů; zůstaly ruční reference a její minutové audio. Níže uvedené cesty k výsledkům jsou historické. Skripty nyní potřebují znovu připravit experimentální vstupy a metadata, než je lze spustit. Rozhodnutí a zachované cesty jsou v `docs/desktop-handoff.md` v kořeni projektu. Model a Pipenv prostředí jsou zachované.

Tento adresář slouží pro porovnání Parakeetu s existujícími běhy Whisperu. Nemění Java CLI, Python worker ani jejich checkpointy. Má vlastní Pipenv prostředí a lock. Vstupem je totožné připravené mono PCM audio 16 kHz z tříminutového vzorku 35:00–38:00.

Používá NeMo 2.4.0, PyTorch 2.7.1 a torchaudio 2.7.1. PyArrow je připnutý na 21.0.0 kvůli kompatibilitě s NumPy 1.x používaným NeMo 2.4; PyArrow 26 při importu vyžadoval NumPy 2. Přesné tranzitivní závislosti a hashe jsou v `Pipfile.lock`. Modelová revize je `541d1f99c6b0c3cd0b11a95167540bb8edefd82b`; stažený checkpoint a jeho SHA-256 jsou v ignorovaném `models/provenance.json`.

## Příprava

Z tohoto adresáře, s instalovaným Pipenv a CPython 3.12:

```bash
export PIPENV_VENV_IN_PROJECT=1
export PIPENV_IGNORE_VIRTUALENVS=1
export PIPENV_DONT_LOAD_ENV=1
pipenv --python /cesta/k/python3.12
pipenv verify
pipenv sync
pipenv run python download_model.py
```

Instalace a stažení modelu potřebují internet. Stažení odmítne přepsat již existující checkpoint. Model, cache ani `.venv` se necommitují. Samotný benchmark přepíná Hugging Face a Transformers do offline režimu a obnovuje model pouze z lokálního checkpointu.

## Porovnání

`benchmark.py` používá cesty k již existujícím vzorkům pod `/home/david/transcriber-runs/260613_090426-20261023-01/samples`. Konkrétní vstup a referenci ověřuje jejich původními SHA-256. Výstupní adresář je `parakeet-tdt-v3-20261028-02` vedle předchozích pokusů; existující adresář se nepřepisuje.

```bash
pipenv run python benchmark.py --check-only
pipenv run python benchmark.py
```

Běh vyžaduje přístup k NVIDIA GPU. Provede tři přepisy, každý v novém procesu s nově načteným modelem, s desetisekundovými pauzami. Parametry: celý tříminutový vzorek, batch size 1, výchozí dekódování modelu, časové značky, modelové váhy FP32 a CUDA FP16 autocast. Není automatický fallback na CPU.

WER se počítá na první minutě podle stejné implementace jako u Whisperu: 113 slov ruční reference, dvě nejistá slova vynechána, ignoruje se interpunkce a velikost písmen. Pravopis a tvary slov reference zůstávají zachované. Celý tříminutový text je nutné posoudit i kvůli významovým chybám a opakováním mimo referenční minutu.

Výsledky obsahují text, časové značky, WER, otisky výstupů, revizi modelu, konfiguraci dekodéru, verze knihoven, dobu inference a měření GPU paměti a teploty. Výsledek tohoto krátkého pokusu není ověření celého vícehodinového přepisu.

Zdroje: [modelová karta NVIDIA](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3), [NeMo 2.4.0](https://pypi.org/project/nemo-toolkit/2.4.0/).

## Naměřený základ (28. 10. 2026)

Na první minutě stejného vzorku (113 hodnocených slov) vyšly tři nezávislé procesy s FP16 autocastem shodně: 79 odchylek, WER 69,91 %. Shodovaly se text, tokeny i časové značky. Doba inference celé tříminutové nahrávky byla 1,625–1,696 s, načtení modelu přibližně 12–13 s. Nejvyšší pozorovaná celková GPU paměť byla 5 332 MiB, teplota 60 °C.

Jedna kontrola s FP32 bez autocastu vyšla na 80 odchylek, WER 70,80 %, inference 1,992 s. Změna přesnosti tedy v tomto pokusu nepomohla. Whisper měl v původním nastavení 53 odchylek (46,90 %) a s nápovědou a vypnutím předchozího kontextu 41 odchylek (36,28 %). Nejde o obecné srovnání modelů, pouze o tento vzorek a ruční referenci.

Výsledky jsou vedle původních vzorků v `parakeet-tdt-v3-20261028-02`, kontrola v `parakeet-tdt-v3-20261028-03-fp32-control`. První pokus `...-01` uchovává diagnostiku chyby serializace enumu konfigurace; tato chyba byla ve srovnávacím skriptu opravena převodem enumů na řetězce.

## Prozkoumané možnosti ladění

Rozlišujeme možnosti skutečně přítomné v instalovaném NeMo 2.4.0 a funkce novější dokumentace. Následující rešerše předcházela matici měření popsané níže; vyšší beam nebo více kontextu samy o sobě nezaručují nižší WER.

1. **Kratší úseky.** Instalované `EncDecRNNTModel.transcribe()` doporučuje 5–25 sekund na soubor. První kandidát je rozdělení na 15/20/25 sekund, ideálně podle pauz. Pro zachování slov na hranicích je potřeba překryv, původní časové posuny a konzervativní spojení. Samotná podpora dlouhého vstupu není důkazem nejlepší přesnosti na něm.
2. **Beam search.** `RNNTDecoding` pro TDT připouští `greedy`, `greedy_batch`, `beam`, `maes` a `malsd_batch`. Pro první pokus lze porovnat `beam_size=4/8`, `score_norm=True`, `return_best_hypothesis=True`. Čas a paměť mohou vzrůst. Není třeba přetrénovat váhy.
3. **Omezení časových značek u beam.** V této verzi `BeamTDTInfer` odmítá `preserve_alignments=True`, zatímco `transcribe(timestamps=True)` tuto volbu vynucuje. Nestačí tedy pouze změnit `strategy` v současném benchmarku. Pro férový první test beam lze použít přímo minutový vstup, vypnout timestampy a vyhodnotit celý text proti minutové referenci; i greedy kontrola musí být spuštěna na stejném minutovém vstupu.
4. **Akustický kontext.** Checkpoint používá `self_attention_model=rel_pos`, `att_context_size=[-1,-1]`, tedy neomezený kontext v rámci vstupu. Modelová karta popisuje přepnutí na `rel_pos_local_attn` s `[256,256]`. To mění akustickou pozornost, nejde o slovník ani textovou nápovědu. Pro tři minuty nebyla zjištěna paměťová nouze; přínos pro přesnost je nutné teprve změřit.
5. **Doménové fráze.** TDT `transcribe()` nemá Whisperův `initial_prompt`/`hotwords`. Novější NeMo dokumentuje GPU Phrase Boosting i pro TDT bez přetrénování. Instalované 2.4.0 ale v konfiguraci ani v příslušných dekodérech nemá `boosting_tree` / `key_phrases`; pouhé přidání těchto klíčů není implementace. Vyžadovalo by to kompatibilní novější runtime, jeho připnutí a znovu kontrolní běh bez boostingu.
6. **Externí český n-gram LM.** NeMo 2.4 má v TDT `maes` cestě parametry `ngram_lm_model` a `ngram_lm_alpha`. Potřebuje KenLM a jazykový model se správným subword kódováním pro tokenizer Parakeetu. Libovolný slovní ARPA model nelze považovat za automaticky kompatibilní. Korpus pro LM musí být oddělený od hodnocené ruční reference.

Priorita dalšího měření: (a) minutová greedy kontrola a 15–25s úseky, (b) beam 4/8 na shodném vstupu, (c) teprve potom nový runtime a mírný phrase boosting s oborovými termíny. Hodnocená minuta nesmí být použita jako text nápovědy ani jako trénovací data pro LM.

Zdroje pro tyto možnosti: [NeMo 2.4 RNNT API](https://github.com/NVIDIA/NeMo/blob/v2.4.0/nemo/collections/asr/models/rnnt_models.py), [TDT beam decoder](https://github.com/NVIDIA/NeMo/blob/v2.4.0/nemo/collections/asr/parts/submodules/tdt_beam_decoding.py), [RNNT konfigurace a výběr dekodérů](https://github.com/NVIDIA/NeMo/blob/v2.4.0/nemo/collections/asr/parts/submodules/rnnt_decoding.py), [novější Phrase Boosting](https://docs.nvidia.com/nemo/speech/nightly/asr/asr_customization/word_boosting.html), [modelová karta](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3).

## Matice ladění

`tune.py` porovnává 48 kombinací: dekodéry `greedy_batch`, `greedy`, `beam` s šířkou 4/8 a `maes` s šířkou 4/8; cílové délky 15/20/25/60 sekund; plný kontext nebo lokální pozornost `[256,256]`. Každá varianta dostane přesně stejných prvních 60 sekund připraveného PCM. Dělicí body se posouvají nejvýše o sekundu k nejtiššímu 100ms oknu. Úseky jsou souvislé bez překryvu: žádné vzorky se nevynechávají ani neopakují, nespojuje se text podle reference. Toto je jednoduchý experiment s dělením; nezahrnuje překryv a jeho deduplikaci.

Pro nejlepší beam variantu následuje šest kombinací `score_norm=True/False` × `softmax_temperature=0.8/1.0/1.2`. Poslední běh opakuje minutovou greedy kontrolu po všech přepnutích pozornosti. Celkem jde o 55 přepisů včetně opakování. Teplota zde škáluje logity dekodéru. Všechny varianty mají vypnuté timestampy, stejný FP16 autocast a batch size 1, mezi běhy je 10 sekund pauza. Nové moduly pozornosti se vždy přepnou do režimu `eval()`.

```bash
pipenv run python tune.py --check-only
pipenv run python tune.py --pilot-only
pipenv run python tune.py
pipenv run python analyze_tuning.py /home/david/transcriber-runs/260613_090426-20261023-01/samples/parakeet-tuning-20261028-03
```

Výstupní kořen je připnutý ve skriptu. Při pokračování se přebírají hotové výsledky se shodnou specifikací a kontrolují se model, vstup, reference a verze knihoven. Pro změnu experimentu použít nový adresář. `comparison.json` obsahuje všechny výsledky, `comparison.txt` jejich tabulku a podsložky samostatné texty, tokeny, konfigurace a zarovnání chyb. `analyze_tuning.py` ověřuje úplnost matice, artefakty, hashe, časové pokrytí i závěrečnou kontrolu a vypíše seřazené výsledky.

První série `parakeet-tuning-20261028-01` obsahuje pouze selhání přípravy konfigurace před inferencí. Pro minimální konfiguraci checkpointu se nyní doplňují volitelné sekce z výchozích hodnot stejným postupem jako v NeMo. Série `...-02` je neplatná pro finální srovnání: závěrečná greedy kontrola se lišila po přepínání pozornosti. Diagnostika je v jejím `validation-failure.json`.

Měření vybírá nastavení podle stejné jedné minuty, proto jde o ladicí výsledek. Před změnou hlavního programu je potřeba ověření na jiné ručně přepsané části nahrávky. Předchozí skóre Whisperu na první minutě tříminutového vstupu má jiný okolní kontext než tento minutový vstup. Novější phrase boosting ani externí jazykový model tato matice neobsahuje.

Opravená série `parakeet-tuning-20261028-03` odstraňuje chybu NeMo 2.4: `change_attention_model()` nepropaguje checkpointové `use_bias=False` a vytvoří náhodné bias parametry lineárních projekcí. Pomocný skript je odstraní a po každém přepnutí porovná SHA-256 všech naučených parametrů pozornosti s originálem. Dva vstupní kontrolní přepisy před a po přepnutí plná → lokální → plná pozornost musí mít totožný text i tokeny. Vedle 55 hodnocených běhů jsou tyto dvě diagnostické kontroly uložené zvlášť. Závěrečná kontrola musí opět odpovídat počáteční.

## Ověřené výsledky opakování — 29. 10. 2026

V sérii `parakeet-tuning-20261028-03` prošlo všech 48 základních kombinací, šest doplňkových běhů i závěrečná kontrola. Po přepínání zůstalo totožných všech 168 parametrických tensorů pozornosti. Vstupní kontrola plná → lokální → plná, závěrečná greedy kontrola a opakování nejlepší beam konfigurace měly shodné texty i tokeny. Kontrolní skript ověřil úplnost, souvislé pokrytí celé minuty, shodu artefaktů, hashe vstupu a reference a počty chyb.

| Varianta na přesně stejném minutovém PCM | Chyby / 113 slov | WER |
| --- | ---: | ---: |
| Parakeet, greedy, celý minutový blok, plný kontext | 86 | 76,11 % |
| Parakeet, greedy / greedy_batch, přibližně 20 s, plný kontext | 61 | 53,98 % |
| Nejlepší beam / MAES varianty, přibližně 20 s | 64 | 56,64 % |
| Whisper large-v3, výchozí nastavení | 51 | 45,13 % |
| Whisper large-v3, nápověda a vypnutý předchozí text | 44 | 38,94 % |

Nejlepší Parakeet zůstal `greedy_batch-20s-full` (stejné skóre jako `greedy-20s-full`). U nejlepší MAES varianty všechny kombinace normalizace a teploty skončily na 64 chybách; žádná greedy nepřekonala. Nejlepší Parakeet měl 38 záměn, 22 vynechání a jednu vloženou jednotku. Přepis obsahuje zkomoleniny i ztrátu významu, např. chybějící zápor v pasáži o průběhu vyučování. V hodnocených textech nebyly nalezeny náhradní znaky Unicode ani neočekávané řídicí znaky.

Parakeet série včetně načtení a pauz trvala 654,378 s. Nejvyšší pozorovaná celková paměť GPU byla 6 831 MiB, teplota 64 °C a příkon 72,11 W (vzorkování jednou za sekundu). Doby jednotlivých variant zahrnují konfiguraci a případné přepnutí a ověření pozornosti; nejsou čistým měřením samotného dekodéru.

Kontroly Whisperu jsou v `whisper-minute-control-20261028-01`. Skript `whisper_minute_control.py` se spouští z kořenového Pipenv, zatímco Parakeet má vlastní prostředí. Ověřuje shodu modelu a verzí se staršími běhy, přesný otisk 960 000 PCM vzorků s Parakeetem i stejnou referenci a vyhodnocení. Rozpoznávání trvalo 4,548 s a 3,721 s. Dřívějších 46,90 / 36,28 % pocházelo z hodnocení první minuty tříminutového vstupu, proto to není tentýž kontext jako nyní.

Plná tabulka je v `parakeet-tuning-20261028-03/comparison.txt`, jednotlivé přepisy a zarovnání v příslušných podsložkách. Souhrnný ověřený report je v `comparison-verified.txt`. Výsledek podporuje ponechání Whisperu pro tento vzorek; ani nejlepší výstup není dostatečně věrný bez oprav. Ověření na další ručně přepsané části je stále potřebné před zobecněním na celou nahrávku.
