"""Validate completed tuning artifacts and print a compact, ranked report."""
from collections import Counter
import hashlib
import json
from pathlib import Path
import sys

root = Path(sys.argv[1])
comparison = json.loads((root / 'comparison.json').read_text())
assert comparison['completed'] and not comparison['pilotOnly']
plan = comparison['experiment']
results = comparison['results']
assert json.loads((root / 'round-trip-preflight.json').read_text())['passed']
fingerprints = json.loads((root / 'attention-parameter-fingerprints.json').read_text())
assert fingerprints and not any(key.endswith('.bias') for key in fingerprints)
assert len(results) == 55
assert len({r['spec']['name'] for r in results}) == len(results)
assert {r['spec']['name'] for r in results[:48]} == {s['name'] for s in plan['variants']}
assert all(r['status'] == 'ok' for r in results)
for field, hash_field in [('preparedAudio', 'preparedAudioSha256'), ('reference', 'referenceSha256')]:
    assert hashlib.sha256(Path(plan[field]).read_bytes()).hexdigest() == plan[hash_field]
for r in results:
    folder = root / r['spec']['name']
    assert json.loads((folder / 'result.json').read_text()) == r
    assert (folder / 'transcript.txt').read_text().strip() == r['text']
    chunks = r['chunks']
    assert chunks[0]['start'] == 0 and chunks[-1]['end'] == 60
    assert all(a['end'] == b['start'] for a, b in zip(chunks, chunks[1:]))
    assert r['text'] == ' '.join(c['text'] for c in chunks).strip()
    score = r['score']
    assert round(100 * score['errors'] / 113, 2) == score['werPercent']
    counts = Counter(a['op'] for a in score['alignment'])
    assert score['errors'] == sum(counts[k] for k in ['substitute', 'delete', 'insert'])
    assert len(score['maskedWords']) <= 2
    assert not any(ord(c) < 32 and c not in '\n\t\r' for c in r['text'])
    assert '\ufffd' not in r['text']
by_name = {r['spec']['name']: r for r in results}
initial = by_name['greedy_batch-60s-full']
final = by_name['final-greedy-control']
assert initial['text'] == final['text'] and initial['chunks'] == final['chunks']
best_beam = min((r for r in results[:48] if r['spec']['beamSize']),
                key=lambda r: (r['score']['errors'], r['elapsedSeconds']))
beam_control = by_name[best_beam['spec']['name'] + '-norm1-temp1.0']
assert best_beam['text'] == beam_control['text'] and best_beam['chunks'] == beam_control['chunks']
base = min(results[:48], key=lambda r: (r['score']['errors'], r['elapsedSeconds']))
best = min(results, key=lambda r: (r['score']['errors'], r['elapsedSeconds']))
print('Verified: 48 grid variants + 6 fine-tuning runs + 1 final control; identical input and reference.')
print('Final greedy control matches initial text and token IDs.')
print('Repeated best beam configuration matches its original text and token IDs.')
print('Best grid:', base['spec']['name'], base['score']['errors'], base['score']['werPercent'])
print('Best overall:', best['spec']['name'], best['score']['errors'], best['score']['werPercent'])
print('\nDecoder\tTarget seconds\tFull-context WER\tLocal256 WER')
for decoder in ['greedy_batch', 'greedy', 'beam4', 'beam8', 'maes4', 'maes8']:
    for seconds in [15, 20, 25, 60]:
        scores = [by_name[f'{decoder}-{seconds}s-{ctx}']['score']['werPercent'] for ctx in ['full', 'local256']]
        print(f'{decoder}\t{seconds}\t{scores[0]:.2f}\t{scores[1]:.2f}')
print('\nAll runs, ranked:')
for r in sorted(results, key=lambda r: (r['score']['errors'], r['spec']['name'])):
    print(r['spec']['name'], r['score']['errors'], r['score']['werPercent'], r['elapsedSeconds'], sep='\t')
print('\nBest transcript:\n' + best['text'])
print('\nGPU:', (root / 'run-metrics.json').read_text())
