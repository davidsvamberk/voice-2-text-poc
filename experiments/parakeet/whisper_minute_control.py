"""Run from the root Pipenv: Whisper controls on the exact Parakeet PCM minute."""
import ast
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import struct
import sys
import time
import wave

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
SAMPLES = Path('/home/david/transcriber-runs/260613_090426-20261023-01/samples')
GRID = SAMPLES / 'parakeet-tuning-20261028-03'
OUTPUT = SAMPLES / 'whisper-minute-control-20261028-01'
plan = json.loads((GRID / 'experiment.json').read_text())
for p, h in [('preparedAudio', 'preparedAudioSha256'), ('reference', 'referenceSha256')]:
    assert hashlib.sha256(Path(plan[p]).read_bytes()).hexdigest() == plan[h]
os.environ.update(HF_HUB_OFFLINE='1', HF_HUB_DISABLE_TELEMETRY='1', DO_NOT_TRACK='1')
sys.path.insert(0, str(ROOT / 'worker'))
from transcriber_worker.engine import Engine, get_versions
import numpy as np

request = json.loads((SAMPLES / 'baseline-original-settings/request.json').read_text())
assert get_versions() == request['config']['engineVersions']
helpers = ast.parse((ROOT / 'target/run-asr-options.py').read_text())
hash_helpers = [n for n in helpers.body if isinstance(n, ast.FunctionDef) and n.name in ['file_hash', 'model_hash']]
hash_namespace = dict(hashlib=hashlib, Path=Path, struct=struct)
exec(compile(ast.Module(body=hash_helpers, type_ignores=[]), '<model-hash>', 'exec'), hash_namespace)
assert hash_namespace['model_hash'](Path(request['modelDir'])) == request['config']['modelSha256']
with wave.open(plan['preparedAudio']) as f:
    assert f.getframerate() == 16000 and f.getnchannels() == 1 and f.getsampwidth() == 2
    audio = np.frombuffer(f.readframes(960000), dtype='<i2').astype(np.float32) / 32768.0
assert audio.shape == (960000,)
assert hashlib.sha256(audio.tobytes()).hexdigest() == plan['pcmMinuteSha256']
ref = [None if m.group().startswith('?') else m.group().casefold()
       for m in re.finditer(r'\?[^?]+\?|\w+', Path(plan['reference']).read_text())]
assert sum(w is not None for w in ref) == 113
module = ast.parse((SAMPLES / 'asr-options-20261028-01/original-reference-evaluator.py').read_text())
function = next(n for n in module.body if isinstance(n, ast.FunctionDef) and n.name == 'align')
namespace = {}
exec(compile(ast.Module(body=[function], type_ignores=[]), '<frozen-WER>', 'exec'), namespace)
if '--check-only' in sys.argv:
    print('Ready: identical PCM hash, frozen scoring, same Whisper library versions.')
    raise SystemExit(0)
assert json.loads((GRID / 'comparison.json').read_text())['completed']
OUTPUT.mkdir(exist_ok=False)
shutil.copyfile(__file__, OUTPUT / Path(__file__).name)
shutil.copyfile(ROOT / 'Pipfile.lock', OUTPUT / 'Pipfile.lock')
engine = Engine(Path(request['modelDir']), request['config'])
results = []
for name, hints, previous in [('baseline', False, True), ('hints-no-previous', True, False)]:
    options = dict(language='cs', task='transcribe', beam_size=5, temperature=0.0,
                   condition_on_previous_text=previous, vad_filter=False, word_timestamps=True,
                   initial_prompt='Pedagogická intervence, speciálně pedagogické centrum, školské poradenské zařízení.' if hints else None)
    print('Starting ' + name, flush=True)
    t0 = time.monotonic()
    segments, _ = engine.model.transcribe(audio.copy(), **options)
    segments = [dict(start=float(s.start), end=float(s.end), text=s.text,
                     words=[dict(start=float(w.start), end=float(w.end), text=w.word) for w in s.words or []]) for s in segments]
    text = ' '.join(s['text'].strip() for s in segments)
    errors, counts, alignment = namespace['align'](ref, re.findall(r'\w+', text.casefold()))
    known_ref = [a['reference'] for a in alignment if a['reference'] is not None]
    known_hyp = [a['hypothesis'] for a in alignment if a['hypothesis'] is not None and a['op'] != 'mask']
    row = list(range(len(known_hyp) + 1))
    for i, word in enumerate(known_ref, 1):
        new_row = [i]
        for j, other in enumerate(known_hyp, 1):
            new_row.append(min(row[j] + 1, new_row[-1] + 1, row[j - 1] + (word != other)))
        row = new_row
    assert row[-1] == errors
    result = dict(variant=name, options=options, text=text, segments=segments,
                  errors=errors, werPercent=round(100 * errors / 113, 2), counts=counts,
                  alignment=alignment, elapsedSeconds=round(time.monotonic() - t0, 3))
    results.append(result)
    (OUTPUT / (name + '.txt')).write_text(text + '\n')
    (OUTPUT / 'comparison.json').write_text(json.dumps(dict(completed=False, pcmMinuteSha256=plan['pcmMinuteSha256'],
        referenceSha256=plan['referenceSha256'], modelConfig=request['config'], results=results), ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({k: result[k] for k in ['variant', 'errors', 'werPercent', 'elapsedSeconds']}), flush=True)
    if name == 'baseline':
        time.sleep(10)
data = json.loads((OUTPUT / 'comparison.json').read_text())
data['completed'] = True
(OUTPUT / 'comparison.json').write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n')
