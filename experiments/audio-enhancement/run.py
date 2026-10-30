"""Four fixed audio chains, one Whisper configuration and frozen manual reference."""
from pathlib import Path
import argparse
from datetime import datetime, timezone
import hashlib
import json
import math
import os
import re
import shutil
import struct
import subprocess
import sys
import threading
import time
import wave

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
SAMPLES = Path('/home/david/transcriber-runs/260613_090426-20261023-01/samples')
SOURCE = SAMPLES / 'manual-reference-003500-003600.wav'
REFERENCE = SAMPLES / 'manual-reference-003500-003600.txt'
OUTPUT = SAMPLES / 'audio-enhancement-20261029-01'
MODEL = ROOT / 'models/large-v3'
FFMPEG = ROOT / '.venv/bin/ffmpeg'
RUNTIME = ROOT / '.venv/ffmpeg-runtime/usr/lib/x86_64-linux-gnu'
PROMPT = 'Pedagogická intervence, speciálně pedagogické centrum, školské poradenské zařízení.'
OPTIONS = dict(language='cs', task='transcribe', beam_size=5, temperature=0.0,
               condition_on_previous_text=False, vad_filter=False, word_timestamps=True, initial_prompt=PROMPT)
EQ = 'highpass=f=90:p=2,equalizer=f=2200:t=o:w=1:g=2,lowpass=f=7000:p=2'
DYNAMICS = ('acompressor=threshold=0.0891251:ratio=4:attack=1:release=80:knee=2:detection=peak:makeup=1,'
            'dynaudnorm=f=250:g=31:p=0.9:m=8:r=0.1:t=0.003,'
            'alimiter=limit=0.891251:attack=5:release=80:level=false:latency=true')
VARIANTS = [('01-original', 'anull'), ('02-eq', EQ), ('03-dynamics', DYNAMICS), ('04-eq-dynamics', EQ + ',' + DYNAMICS)]
os.environ.update(HF_HUB_OFFLINE='1', HF_HUB_DISABLE_TELEMETRY='1', DO_NOT_TRACK='1')
import numpy as np
from frozen_alignment import align
sys.path.insert(0, str(ROOT / 'worker'))
from transcriber_worker.engine import Engine, get_versions


def sha(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as f:
        while b := f.read(8 * 1024 * 1024):
            h.update(b)
    return h.hexdigest()


def model_sha():
    h = hashlib.sha256()
    for p in sorted(MODEL.rglob('*'), key=lambda p: p.relative_to(MODEL).as_posix()):
        assert not p.is_symlink()
        if p.is_file():
            name = p.relative_to(MODEL).as_posix().encode()
            h.update(struct.pack('>i', len(name))); h.update(name)
            h.update(struct.pack('>q', p.stat().st_size)); h.update(bytes.fromhex(sha(p)))
    return h.hexdigest()


def save(path, data):
    path = Path(path); tmp = path.with_suffix(path.suffix + '.tmp')
    tmp.write_text(json.dumps(data, ensure_ascii=False, indent=2, allow_nan=False) + '\n')
    tmp.replace(path)


reference = [None if m.group().startswith('?') else m.group().casefold()
             for m in re.finditer(r'\?[^?]+\?|\w+', REFERENCE.read_text())]
assert sum(w is not None for w in reference) == 113
assert sum(w is None for w in reference) == 2
reference_sha = sha(REFERENCE); source_sha = sha(SOURCE)
assert reference_sha == 'f99c6fb2b888bc0128746f25ce37d092c96a2c505477105b7414159ea160eac5'
assert source_sha == '3e8d07f8e13378422f83c78dc1a8916c5f3d10776b558f8490bbba7e5e090e8b'
with wave.open(str(SOURCE)) as f:
    assert (f.getnchannels(), f.getsampwidth(), f.getframerate(), f.getnframes()) == (1, 3, 48000, 2880000)
assert model_sha() == '398b5448d38b409d26caf7accea27e391bc6418feb05d4a9b54bac541cb1e0e2'
assert align(['a', None, 'b'], ['a', 'x', 'b'])[0] == 0
assert align(['a', None, 'b'], ['a', 'c'])[0] == 1
assert align(['a', 'b'], ['x', 'b', 'c'])[0] == 2
env = os.environ.copy(); env['LD_LIBRARY_PATH'] = str(RUNTIME)
for name in ['highpass', 'equalizer', 'lowpass', 'acompressor', 'dynaudnorm', 'alimiter']:
    check = subprocess.run([str(FFMPEG), '-hide_banner', '-h', 'filter=' + name], env=env, capture_output=True, text=True, check=True)
    assert 'Unknown filter' not in check.stdout + check.stderr
parser = argparse.ArgumentParser()
parser.add_argument('--check-only', action='store_true')
parser.add_argument('--prepare-only', action='store_true')
args = parser.parse_args()
if args.check_only:
    assert not OUTPUT.exists()
    print(json.dumps(dict(status='ready', variants=VARIANTS, options=OPTIONS, versions=get_versions()), ensure_ascii=False), flush=True)
    raise SystemExit(0)

plan = dict(createdAt=datetime.now(timezone.utc).isoformat(), source=str(SOURCE), sourceSha256=source_sha,
            reference=str(REFERENCE), referenceSha256=reference_sha, referenceWords=113, ignoredWords=2,
            model=str(MODEL), modelSha256=model_sha(), versions=get_versions(), options=OPTIONS, variants=VARIANTS,
            processing='Float processing at 48 kHz, listening copy PCM24 48 kHz, ASR copy PCM16 16 kHz; no cuts or speed changes.',
            pauseSeconds=10, scoring='Entire text of exact 60-second audio; casefold, punctuation ignored; original spelling and uncertainty masking retained.')
if OUTPUT.exists():
    old = json.loads((OUTPUT / 'experiment.json').read_text())
    for k in ['sourceSha256', 'referenceSha256', 'modelSha256', 'versions', 'options', 'variants']:
        assert old[k] == json.loads(json.dumps(plan[k])), k
else:
    OUTPUT.mkdir()
    save(OUTPUT / 'experiment.json', plan)
for src in [Path(__file__), HERE / 'frozen_alignment.py', ROOT / 'Pipfile', ROOT / 'Pipfile.lock']:
    shutil.copyfile(src, OUTPUT / src.name)


def read_pcm(path):
    with wave.open(str(path)) as f:
        assert (f.getnchannels(), f.getsampwidth(), f.getframerate(), f.getnframes()) == (1, 2, 16000, 960000)
        return np.frombuffer(f.readframes(960000), dtype='<i2').astype(np.float32) / 32768.0


def db(x):
    return round(20 * math.log10(max(float(x), 1e-12)), 3)


def audio_stats(audio):
    rms = np.sqrt(np.mean(audio.astype(np.float64) ** 2))
    windows = np.sqrt(np.mean(audio.reshape(-1, 1600).astype(np.float64) ** 2, axis=1))
    peak = float(np.max(np.abs(audio)))
    return dict(peakDbfs=db(peak), rmsDbfs=db(rms), crestDb=round(db(peak)-db(rms), 3),
                median100msRmsDbfs=db(np.median(windows)), maximum100msRmsDbfs=db(np.max(windows)),
                clippedSamples=int(np.count_nonzero(np.abs(audio) >= 0.999969)))


prepared = {}
for name, chain in VARIANTS:
    folder = OUTPUT / name; folder.mkdir(exist_ok=True)
    listening = folder / 'listening-48k.wav'; asr = folder / 'audio-16k.wav'
    commands = [[str(FFMPEG), '-v', 'error', '-nostdin', '-n', '-i', str(SOURCE), '-map', '0:a:0',
                 '-af', 'aformat=sample_fmts=fltp,' + chain, '-ac', '1', '-ar', '48000', '-c:a', 'pcm_s24le', str(listening)],
                [str(FFMPEG), '-v', 'error', '-nostdin', '-n', '-i', str(listening), '-map', '0:a:0',
                 '-ac', '1', '-ar', '16000', '-c:a', 'pcm_s16le', str(asr)]]
    for command, target in zip(commands, [listening, asr]):
        if not target.exists():
            subprocess.run(command, env=env, check=True)
    with wave.open(str(listening)) as f:
        assert f.getnframes() == 2880000 and f.getframerate() == 48000
    audio = read_pcm(asr)
    stats = audio_stats(audio)
    assert stats['clippedSamples'] == 0
    prepared[name] = dict(audioSha256=sha(asr), listeningSha256=sha(listening), stats=stats, commands=commands)
    if name == '01-original':
        original = audio
    # Correlation peak confirms that filters did not introduce a word-scale time offset.
    nfft = 1 << (2 * len(audio) - 1).bit_length()
    correlation = np.fft.irfft(np.fft.rfft(audio, nfft) * np.conj(np.fft.rfft(original, nfft)), nfft)
    nearby = np.concatenate((correlation[-320:], correlation[:321]))
    lag = int(np.argmax(nearby)) - 320
    prepared[name]['correlationLagMs'] = round(lag / 16, 4)
    assert abs(lag) <= 80, ('Unexpected offset above 5 ms', name, lag)
    save(folder / 'processing.json', prepared[name])
    print(json.dumps(dict(status='prepared', variant=name, **stats, correlationLagMs=lag/16)), flush=True)
save(OUTPUT / 'audio-preparation.json', prepared)
if args.prepare_only:
    raise SystemExit(0)


def score(text):
    errors, counts, alignment = align(reference, re.findall(r'\w+', text.casefold()))
    rr = [a['reference'] for a in alignment if a['reference'] is not None]
    hh = [a['hypothesis'] for a in alignment if a['hypothesis'] is not None and a['op'] != 'mask']
    row = list(range(len(hh) + 1))
    for i, x in enumerate(rr, 1):
        new = [i]
        for j, y in enumerate(hh, 1):
            new.append(min(row[j]+1, new[-1]+1, row[j-1]+(x != y)))
        row = new
    assert row[-1] == errors
    return dict(errors=errors, werPercent=round(errors/113*100, 2), counts=counts, alignment=alignment)


stop = threading.Event(); gpu = []
def monitor():
    while not stop.is_set():
        p = subprocess.run(['nvidia-smi', '--query-gpu=memory.used,temperature.gpu,power.draw', '--format=csv,noheader,nounits'], capture_output=True, text=True)
        if p.returncode == 0:
            gpu.append(tuple(map(float, p.stdout.strip().splitlines()[0].split(','))))
        stop.wait(1)
thread = threading.Thread(target=monitor, daemon=True); thread.start()
results = []
started = time.monotonic()
try:
    engine = Engine(MODEL, {})
    order = [name for name, _ in VARIANTS]
    for index, name in enumerate(order + ['05-original-repeat']):
        source_name = '01-original' if name == '05-original-repeat' else name
        folder = OUTPUT / name; folder.mkdir(exist_ok=True)
        assert not (folder / 'result.json').exists(), 'Use a fresh output directory for new ASR runs'
        print(json.dumps(dict(status='starting', variant=name)), flush=True)
        t0 = time.monotonic()
        segments, _ = engine.model.transcribe(read_pcm(OUTPUT / source_name / 'audio-16k.wav'), **OPTIONS)
        segments = [dict(start=float(s.start), end=float(s.end), text=s.text,
                         words=[dict(start=float(w.start), end=float(w.end), text=w.word) for w in s.words or []]) for s in segments]
        text = ' '.join(s['text'].strip() for s in segments)
        assert '\ufffd' not in text
        result = dict(name=name, sourceVariant=source_name, text=text, segments=segments, score=score(text),
                      elapsedSeconds=round(time.monotonic()-t0, 3), audio=prepared[source_name])
        save(folder / 'result.json', result)
        (folder / 'transcript.txt').write_text(text + '\n')
        results.append(result)
        save(OUTPUT / 'comparison.json', dict(experiment=plan, results=results, completed=False))
        print(json.dumps(dict(status='completed', variant=name, errors=result['score']['errors'], werPercent=result['score']['werPercent'])), flush=True)
        if index < len(order):
            time.sleep(10)
    assert results[0]['segments'] == results[-1]['segments'] and results[0]['text'] == results[-1]['text']
    assert sha(REFERENCE) == reference_sha and sha(SOURCE) == source_sha
    assert model_sha() == plan['modelSha256']
    save(OUTPUT / 'comparison.json', dict(experiment=plan, results=results, completed=True, originalRepeatMatches=True))
    lines = ['Audio enhancement / Whisper fixed hints + no previous text',
             'Exact same 60-second source; 113 scored reference words; two uncertain words ignored.',
             'Casefold and punctuation normalization only. No reference sentences supplied to ASR.',
             'Original repeated after all chains: identical text, timestamps and words.', '',
             'Variant\tErrors\tWER %\tRMS dBFS\tPeak dBFS\tCrest dB']
    for r in results:
        s = r['audio']['stats']
        lines.append(f"{r['name']}\t{r['score']['errors']}\t{r['score']['werPercent']}\t{s['rmsDbfs']}\t{s['peakDbfs']}\t{s['crestDb']}")
    for r in results[:4]:
        lines += ['', r['name'], r['text']]
    (OUTPUT / 'comparison.txt').write_text('\n'.join(lines) + '\n')
finally:
    stop.set(); thread.join(timeout=10)
    save(OUTPUT / 'run-metrics.json', dict(elapsedSeconds=round(time.monotonic()-started, 3),
        gpuSamples=len(gpu), peakGpuMemoryMiB=max((v[0] for v in gpu), default=None),
        peakGpuTemperatureC=max((v[1] for v in gpu), default=None), peakGpuPowerW=max((v[2] for v in gpu), default=None)))
