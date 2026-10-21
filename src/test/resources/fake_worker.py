import json
from pathlib import Path
import sys
import time

root = Path(__file__).parent
mode = (root / 'mode').read_text()
def emit(**kw):
    print(json.dumps(dict(schemaVersion=1, **kw)), flush=True)
if '--probe' in sys.argv:
    print(json.dumps(dict(schemaVersion=1, engineVersions={'fake':'1'})))
    sys.exit(0)
r = json.loads(Path(sys.argv[-1]).read_text())
assert Path(r['workDir']).is_absolute()
assert Path(r['modelDir']).is_absolute()
assert r['config']['modelDir'] == r['modelDir']
print('diagnostic česky' * 10000, file=sys.stderr, flush=True)
if mode == 'malformed':
    print('not JSON', flush=True)
    time.sleep(60)
if mode == 'early': sys.exit(0)
if mode == 'error':
    emit(type='error',code='INFERENCE',message='GPU failed')
    sys.exit(1)
if mode == 'wait':
    (root / 'pid').write_text(str(__import__('os').getpid()))
    time.sleep(60)
emit(type='ready',engineVersions={'fake':'1'})
if mode.startswith('event:'):
    print(mode[6:],flush=True)
    sys.exit(0)
if mode != 'missing':
    p = Path(r['workDir']) / 'blocks' / '000000.json'
    p.parent.mkdir(exist_ok=True)
    result = dict(schemaVersion=1,inputSha256=r['inputSha256'],configSha256=r['configSha256'],blockId=0,segments=[])
    if mode == 'invalid': result['inputSha256']='wrong'
    p.write_text(json.dumps(result))
emit(type='block_done',blockId=0)
if mode == 'duplicate': emit(type='block_done',blockId=0)
if mode == 'exit': sys.exit(7)
