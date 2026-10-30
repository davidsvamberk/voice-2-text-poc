"""Preparation only: resolve a model revision once, download that revision, record hashes."""
from pathlib import Path
from datetime import datetime, timezone
import hashlib
import json
import shutil
from huggingface_hub import hf_hub_download

root = Path(__file__).resolve().parent
repo = 'nvidia/parakeet-tdt-0.6b-v3'
filename = 'parakeet-tdt-0.6b-v3.nemo'
models = root / 'models'
models.mkdir(exist_ok=True)
target = models / filename
assert not target.exists(), target
revision = '541d1f99c6b0c3cd0b11a95167540bb8edefd82b'
assert len(revision) == 40
(models / 'download-request.json').write_text(json.dumps({'repo': repo, 'revision': revision, 'filename': filename}, indent=2)+'\n')
print(f'Downloading {repo} at immutable revision {revision}', flush=True)
source = hf_hub_download(repo_id=repo, filename=filename, revision=revision, cache_dir=root/'.cache/huggingface/hub')
shutil.copyfile(source, target)
digest = hashlib.sha256()
with target.open('rb') as f:
    while chunk := f.read(8*1024*1024):
        digest.update(chunk)
provenance = dict(repo=repo, revision=revision, filename=filename, sha256=digest.hexdigest(),
                  bytes=target.stat().st_size, downloadedAt=datetime.now(timezone.utc).isoformat())
(models / 'provenance.json').write_text(json.dumps(provenance,indent=2)+'\n')
print(json.dumps(provenance,indent=2),flush=True)
