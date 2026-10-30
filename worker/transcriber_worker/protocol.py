import json
import math
import os
from pathlib import Path
import re
import tempfile
import traceback

from .engine import get_versions, local_model, offline


def require(condition, message):
    if not condition:
        raise ValueError(message)


def number(value):
    return type(value) in (int, float) and math.isfinite(value)


def version(value):
    require(type(value) is dict and type(value.get('schemaVersion')) is int and value['schemaVersion'] == 1,
            'Unsupported or invalid schemaVersion')


def no_duplicates(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, f'Duplicate JSON key: {key}')
        result[key] = value
    return result


def load_request(path: Path) -> dict:
    return json.loads(path.read_text(encoding='utf-8'), object_pairs_hook=no_duplicates,
                      parse_constant=lambda value: (_ for _ in ()).throw(ValueError(f'Nonfinite JSON: {value}')))


def validate_request(r):
    version(r)
    for key in ('workDir', 'modelDir'):
        require(type(r.get(key)) is str and Path(r[key]).is_absolute(), f'Invalid {key}')
    local_model(Path(r['modelDir']))
    for key in ('inputSha256', 'configSha256'):
        require(type(r.get(key)) is str and re.fullmatch('[0-9a-f]{64}', r[key]), f'Invalid {key}')
    c = r.get('config')
    require(type(c) is dict, 'Missing config')
    require(c.get('modelDir') == r['modelDir'], 'Model path mismatch')
    require(type(c.get('modelSha256')) is str and re.fullmatch('[0-9a-f]{64}', c['modelSha256']), 'Invalid model identity')
    for key, expected in dict(device='cuda',computeType='float16',language='cs',beamSize=5,
                              vadFilter=False,wordTimestamps=True).items():
        require(type(c.get(key)) is type(expected) and c[key] == expected, f'Invalid ASR setting: {key}')
    require(type(c.get('conditionOnPreviousText')) is bool, 'Invalid conditioning flag')
    for key in ('initialPrompt', 'hotwords'):
        require(c.get(key) is None or type(c.get(key)) is str, f'Invalid {key}')
    require(number(c.get('temperature')) and c['temperature'] == 0, 'Invalid temperature')
    require(type(c.get('targetSeconds')) is int and c['targetSeconds'] > 0, 'Invalid targetSeconds')
    require(type(c.get('searchSeconds')) is int and 0 <= c['searchSeconds'] < c['targetSeconds'], 'Invalid searchSeconds')
    require(number(c.get('overlapSeconds')) and c['overlapSeconds'] >= 0, 'Invalid overlap')
    require(c['overlapSeconds'] / 2 < c['targetSeconds'] - c['searchSeconds'], 'Overlap too large')
    versions = c.get('engineVersions')
    require(type(versions) is dict and versions and all(type(k) is str and type(v) is str and v for k,v in versions.items()), 'Invalid versions')
    plan = r.get('plan')
    version(plan)
    require(number(plan.get('duration')) and plan['duration'] >= 0 and type(plan.get('blocks')) is list, 'Invalid plan')
    previous_start, previous_end = -1, 0
    for i,b in enumerate(plan['blocks']):
        require(type(b) is dict and type(b.get('id')) is int and b['id'] == i, 'Invalid plan ID')
        start,end = b.get('start'),b.get('end')
        require(number(start) and number(end) and 0 <= start < end <= plan['duration'] and
                start > previous_start and end > previous_end and start <= previous_end, 'Invalid plan interval')
        previous_start,previous_end = start,end
    require(previous_end == plan['duration'], 'Incomplete plan')
    pending = r.get('pendingBlockIds')
    require(type(pending) is list and all(type(i) is int and 0 <= i < len(plan['blocks']) for i in pending), 'Invalid pending IDs')
    require(len(set(pending)) == len(pending), 'Duplicate pending ID')


def validate_segments(segments, length):
    require(type(segments) is list, 'Invalid segments')
    previous = 0
    def interval(item):
        require(type(item) is dict and type(item.get('text')) is str and number(item.get('start'))
                and number(item.get('end')) and 0 <= item['start'] <= item['end'] <= length, 'Invalid result interval/text')
    for s in segments:
        interval(s)
        require(s['start'] >= previous and type(s.get('words')) is list, 'Invalid segment order/words')
        previous = s['end']
        word_end = 0
        for w in s['words']:
            interval(w)
            require(w['start'] >= word_end, 'Invalid word order')
            word_end = w['end']


def atomic_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(mode='w', encoding='utf-8', dir=path.parent, suffix='.tmp', delete=False) as out:
            temporary = Path(out.name)
            json.dump(value, out, ensure_ascii=False, allow_nan=False)
            out.flush()
            os.fsync(out.fileno())
        os.replace(temporary, path)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def run_request(request: dict, engine_factory, emit) -> int:
    def event(kind, **fields):
        emit(dict(schemaVersion=1, type=kind, **fields))
    try:
        offline()
        validate_request(request)
        versions = get_versions()
        require(versions == request['config']['engineVersions'], 'Installed engine versions changed')
        pending = request['pendingBlockIds']
        engine = engine_factory(Path(request['modelDir']), request['config']) if pending else None
        event('ready', engineVersions=versions)
        root = Path(request['workDir'])
        for ident in pending:
            block = request['plan']['blocks'][ident]
            segments = engine.transcribe(root / 'audio.wav', block)
            validate_segments(segments, block['end'] - block['start'])
            atomic_json(root / 'blocks' / f'{ident:06d}.json', dict(schemaVersion=1,
                inputSha256=request['inputSha256'], configSha256=request['configSha256'], blockId=ident,
                blockStart=block['start'], blockEnd=block['end'], segments=segments))
            event('block_done', blockId=ident)
        return 0
    except Exception as exc:
        traceback.print_exc(file=__import__('sys').stderr)
        event('error', code='WORKER_FAILURE', message=str(exc))
        return 1
