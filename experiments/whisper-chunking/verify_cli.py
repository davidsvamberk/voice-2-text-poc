"""Local, offline end-to-end check of the built JAR against the saved benchmark."""
from pathlib import Path
import hashlib
import argparse
import json
import os
import re
import subprocess
import sys
import time
import wave

ROOT = Path(__file__).resolve().parents[2]
SAMPLES = Path('/home/david/transcriber-runs/260613_090426-20261023-01/samples')
OUTPUT = SAMPLES / 'whisper-chunking-20261029-01'
AUDIO = SAMPLES / 'audio-enhancement-20261029-01/01-original/audio-16k.wav'
REFERENCE = SAMPLES / 'manual-reference-003500-003600.txt'
JAVA = Path('/home/david/.sdkman/candidates/java/25.0.3-tem/bin/java')
sys.path.insert(0, str(ROOT / 'experiments/audio-enhancement'))
from frozen_alignment import align


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def pcm(path):
    with wave.open(str(path), 'rb') as audio:
        assert audio.getnchannels() == 1 and audio.getsampwidth() == 2
        assert audio.getframerate() == 16000 and audio.getnframes() == 960000
        return audio.readframes(audio.getnframes())


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--resume-verification', action='store_true')
    args = parser.parse_args()
    metadata = json.loads((OUTPUT / 'experiment.json').read_text())
    assert sha(AUDIO) == metadata['audioSha256']
    assert sha(REFERENCE) == metadata['referenceSha256']
    destination = OUTPUT / 'cli-verification'
    destination.mkdir(exist_ok=args.resume_verification)
    env = os.environ.copy()
    for key in ['TARGETSECONDS', 'SEARCHSECONDS', 'OVERLAPSECONDS',
                'CONDITIONONPREVIOUSTEXT', 'INITIALPROMPT', 'HOTWORDS']:
        env.pop('TRANSCRIBER_' + key, None)
    env.update({
        'TRANSCRIBER_PYTHON': str(ROOT / '.venv/bin/python'),
        'TRANSCRIBER_WORKERDIR': str(ROOT / 'worker'),
        'TRANSCRIBER_MODELDIR': str(ROOT / 'models/large-v3'),
        'TRANSCRIBER_FFMPEG': str(ROOT / '.venv/bin/ffmpeg'),
        'TRANSCRIBER_FFPROBE': str(ROOT / '.venv/bin/ffprobe'),
        'HF_HUB_OFFLINE': '1', 'HF_HUB_DISABLE_TELEMETRY': '1', 'DO_NOT_TRACK': '1',
        'LD_LIBRARY_PATH': os.pathsep.join(str(ROOT / path) for path in [
            '.venv/ffmpeg-runtime/usr/lib/x86_64-linux-gnu',
            '.venv/lib/python3.12/site-packages/nvidia/cudnn/lib',
            '.venv/lib/python3.12/site-packages/nvidia/cublas/lib',
            '.venv/lib/python3.12/site-packages/nvidia/cuda_nvrtc/lib']),
    })
    ref = [None if match.group().startswith('?') else match.group().casefold()
           for match in re.finditer(r'\?[^?]+\?|\w+', REFERENCE.read_text())]
    results = []
    for seconds in [60, 20]:
        name = f'{seconds}s-initial'
        print('Starting actual JAR:', name, flush=True)
        work = destination / name
        options = env.copy()
        if seconds != 60:
            options['TRANSCRIBER_TARGETSECONDS'] = str(seconds)
        command = [str(JAVA), '-jar', str(ROOT / 'target/transcriber.jar'),
                   str(AUDIO), '--work-dir', str(work)]
        started = time.monotonic()
        if not (args.resume_verification and (work / 'transcript.jsonl').is_file()):
            run = subprocess.run(command, cwd=ROOT, env=options, capture_output=True, text=True)
            (destination / f'{name}.log').write_text(run.stdout + run.stderr)
            assert run.returncode == 0, run.stdout + run.stderr
        expected = json.loads((OUTPUT / name / 'result.json').read_text())
        manifest = json.loads((work / 'manifest.json').read_text())
        config = manifest['config']
        assert config['targetSeconds'] == seconds and config['searchSeconds'] == 1
        assert config['overlapSeconds'] == 2 and config['conditionOnPreviousText'] is False
        assert config['initialPrompt'] == metadata['options']['initial_prompt']
        assert config['hotwords'] is None
        assert pcm(work / 'audio.wav') == pcm(AUDIO)
        plan = json.loads((work / 'plan.json').read_text())
        assert plan == json.loads((OUTPUT / f'plan-{seconds}.json').read_text())
        for index, block in enumerate(expected['blocks']):
            actual = json.loads((work / f'blocks/{index:06d}.json').read_text())
            for field in ['blockId', 'blockStart', 'blockEnd', 'segments']:
                assert actual[field] == block[field], (name, index, field)
        records = [json.loads(line) for line in (work / 'transcript.jsonl').read_text().splitlines()]
        assert records[0]['type'] == 'metadata'
        transcript = {kind + 's': [{k: v for k, v in record.items() if k != 'type'}
                                  for record in records if record['type'] == kind]
                      for kind in ['segment', 'warning']}
        text = ' '.join(segment['text'].strip() for segment in transcript['segments'])
        errors, _, _ = align(ref, re.findall(r'\w+', text.casefold()))
        assert text == expected['text'] and errors == expected['errors']
        assert transcript['warnings'] == expected['warnings']
        checkpoints = [work / 'manifest.json', work / 'plan.json', *sorted((work / 'blocks').glob('*.json'))]
        before = {str(path): (sha(path), path.stat().st_mtime_ns) for path in checkpoints}
        # A completed resume must not start inference or rewrite checkpoints.
        logs_before = {str(path): (sha(path), path.stat().st_mtime_ns)
                       for path in (work / 'logs').glob('*.log') if path.name != 'java.log'}
        resumed = subprocess.run(command, cwd=ROOT, env=options, capture_output=True, text=True)
        (destination / f'{name}-resume.log').write_text(resumed.stdout + resumed.stderr)
        assert resumed.returncode == 0, resumed.stdout + resumed.stderr
        assert 'Pending blocks:' not in resumed.stdout
        assert before == {str(path): (sha(path), path.stat().st_mtime_ns) for path in checkpoints}
        assert logs_before == {str(path): (sha(path), path.stat().st_mtime_ns)
                               for path in (work / 'logs').glob('*.log') if path.name != 'java.log'}
        results.append(dict(name=name, blocks=len(plan['blocks']), errors=errors,
                            werPercent=expected['werPercent'], warnings=len(transcript['warnings']),
                            matchesBenchmark=True, resumeUnchanged=True,
                            elapsedIncludingResume=round(time.monotonic() - started, 3)))
        print(json.dumps(results[-1]), flush=True)
        if seconds == 60:
            time.sleep(10)
    assert sha(AUDIO) == metadata['audioSha256'] and sha(REFERENCE) == metadata['referenceSha256']
    report = dict(completed=True, jarSha256=sha(ROOT / 'target/transcriber.jar'), results=results)
    (destination / 'verification.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')


if __name__ == '__main__':
    main()
