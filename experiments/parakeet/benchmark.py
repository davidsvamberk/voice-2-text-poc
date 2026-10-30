"""Local Parakeet spike: three independent runs, fixed audio and frozen WER evaluation."""
from pathlib import Path
import argparse
import ast
from datetime import datetime, timezone
import hashlib
import importlib.metadata
import json
import math
import os
import re
import shutil
import subprocess
import sys
import threading
import time

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
SAMPLES = Path('/home/david/transcriber-runs/260613_090426-20261023-01/samples')
PREVIOUS = SAMPLES / 'asr-options-20261028-01'
OUTPUT = SAMPLES / 'parakeet-tdt-v3-20261028-02'
REFERENCE = SAMPLES / 'manual-reference-003500-003600.txt'
AUDIO = SAMPLES / 'baseline-original-settings/audio.wav'
MODEL = HERE / 'models/parakeet-tdt-0.6b-v3.nemo'
EVALUATOR = PREVIOUS / 'original-reference-evaluator.py'
os.environ.update(HF_HUB_OFFLINE='1', TRANSFORMERS_OFFLINE='1', HF_HUB_DISABLE_TELEMETRY='1', DO_NOT_TRACK='1',
                  HF_HOME=str(HERE/'.cache/huggingface'), NEMO_CACHE_DIR=str(HERE/'.cache/nemo'),
                  TMPDIR=str(HERE/'.cache/tmp'), MPLCONFIGDIR=str(HERE/'.cache/matplotlib'),
                  NUMBA_CACHE_DIR=str(HERE/'.cache/numba'))


def sha(path):
    d=hashlib.sha256()
    with Path(path).open('rb') as f:
        while chunk:=f.read(8*1024*1024):d.update(chunk)
    return d.hexdigest()


def write_json(path,value):
    path=Path(path)
    temporary=path.with_suffix(path.suffix+'.tmp')
    temporary.write_text(json.dumps(value,ensure_ascii=False,indent=2,allow_nan=False)+'\n',encoding='utf-8')
    os.replace(temporary,path)


def canonical(value):
    return hashlib.sha256(json.dumps(value,ensure_ascii=False,sort_keys=True,separators=(',',':'),allow_nan=False).encode()).hexdigest()


old=json.loads((PREVIOUS/'experiment.json').read_text())
assert sha(REFERENCE)==old['referenceSha256']
assert sha(AUDIO)==old['preparedAudioSha256']
reference=[None if m.group().startswith('?') else m.group().casefold() for m in re.finditer(r'\?[^?]+\?|\w+',REFERENCE.read_text())]
N=sum(x is not None for x in reference)
assert N==113
align_node=next(n for n in ast.parse(EVALUATOR.read_text()).body if isinstance(n,ast.FunctionDef) and n.name=='align')
namespace={}
exec(compile(ast.Module(body=[align_node],type_ignores=[]),'<frozen-WER-alignment>','exec'),namespace)
align=namespace['align']


def score(words):
    selected=[w for w in words if 0 <= (w['start']+w['end'])/2 < 60]
    hypothesis=[t.casefold() for w in selected for t in re.findall(r'\w+',w['text'])]
    errors,counts,alignment=align(reference,hypothesis)
    rr=[a['reference'] for a in alignment if a['reference'] is not None]
    hh=[a['hypothesis'] for a in alignment if a['hypothesis'] is not None and a['op']!='mask']
    previous=list(range(len(hh)+1))
    for i,x in enumerate(rr,1):
        current=[i]
        for j,y in enumerate(hh,1):current.append(min(previous[j]+1,current[-1]+1,previous[j-1]+(x!=y)))
        previous=current
    assert previous[-1]==errors
    return dict(errors=errors,werPercent=round(100*errors/N,2),counts=counts,alignment=alignment,
                firstMinuteText=' '.join(w['text'] for w in selected),
                maskedHypothesisWords=[a['hypothesis'] for a in alignment if a['op']=='mask'])


for name,expected in [('08-control',53),('04-hints-no-previous',41)]:
    previous=json.loads((PREVIOUS/name/'result.json').read_text())
    assert score([w for s in previous['segments'] for w in s['words']])['errors']==expected
provenance=json.loads((HERE/'models/provenance.json').read_text())


def json_safe(value):
    if isinstance(value,dict):return {str(k):json_safe(v) for k,v in value.items()}
    if isinstance(value,(list,tuple)):return [json_safe(v) for v in value]
    if hasattr(value,'detach'):return json_safe(value.detach().cpu().tolist())
    if hasattr(value,'item'):return value.item()
    return value


parser=argparse.ArgumentParser()
parser.add_argument('--check-only',action='store_true')
parser.add_argument('--single-run',type=int)
parser.add_argument('--precision',choices=['fp16','fp32'],default='fp16')
parser.add_argument('--runs',type=int,default=3)
parser.add_argument('--output-dir',type=Path,default=OUTPUT)
args=parser.parse_args()
assert 1<=args.runs<=5
OUTPUT=args.output_dir
precision_label='FP32 model weights with CUDA FP16 autocast' if args.precision=='fp16' else 'FP32 model weights, autocast disabled'
versions={p:importlib.metadata.version(p) for p in ['nemo-toolkit','torch','torchaudio','numpy','lightning','transformers','huggingface-hub','pyarrow','datasets']}

if args.single_run:
    import torch
    import nemo.collections.asr as nemo_asr
    from omegaconf import OmegaConf
    assert torch.cuda.is_available(), 'CUDA unavailable; no automatic CPU fallback'
    run=OUTPUT/f'run-{args.single_run:02d}'
    run.mkdir(exist_ok=False)
    torch.cuda.reset_peak_memory_stats()
    t0=time.monotonic()
    model=nemo_asr.models.ASRModel.restore_from(restore_path=str(MODEL),map_location=torch.device('cpu'))
    model=model.to('cuda').eval()
    torch.cuda.synchronize()
    load_seconds=time.monotonic()-t0
    print(json.dumps(dict(status='model-ready',run=args.single_run,loadSeconds=round(load_seconds,3)),ensure_ascii=False),flush=True)
    t1=time.monotonic()
    with torch.inference_mode(), torch.autocast(device_type='cuda',dtype=torch.float16,enabled=(args.precision=='fp16')):
        output=model.transcribe([str(AUDIO)],batch_size=1,return_hypotheses=True,num_workers=0,timestamps=True,verbose=False)
    torch.cuda.synchronize()
    inference_seconds=time.monotonic()-t1
    if isinstance(output,tuple):output=output[0]
    assert len(output)==1
    hypothesis=output[0]
    timestamps=json_safe(hypothesis.timestamp)
    assert isinstance(timestamps,dict) and timestamps.get('word'), f'Missing word timestamps: {timestamps}'
    words=[]
    for w in timestamps['word']:
        word=dict(start=float(w['start']),end=float(w['end']),text=str(w['word']))
        assert math.isfinite(word['start']) and math.isfinite(word['end']) and 0<=word['start']<=word['end']<=181
        words.append(word)
    assert all(a['start']<=b['start'] for a,b in zip(words,words[1:]))
    text=str(hypothesis.text).strip()
    assert text
    assert re.findall(r'\w+',text.casefold())==[token for w in words for token in re.findall(r'\w+',w['text'].casefold())], 'Text/timestamp words mismatch'
    scores=score(words)
    tokens=json_safe(hypothesis.y_sequence)
    result=dict(run=args.single_run,processId=os.getpid(),model=provenance,versions=versions,
                preparedAudioSha256=old['preparedAudioSha256'],referenceSha256=old['referenceSha256'],
                precision=precision_label,device=torch.cuda.get_device_name(),
                batchSize=1,timestamps=True,offline=True,modelReloadedForEachRun=True,
                loadSeconds=round(load_seconds,3),inferenceSeconds=round(inference_seconds,3),
                torchPeakAllocatedMiB=round(torch.cuda.max_memory_allocated()/1024**2,2),
                torchPeakReservedMiB=round(torch.cuda.max_memory_reserved()/1024**2,2),
                decodingConfig=OmegaConf.to_container(model.cfg.decoding,resolve=True,enum_to_str=True),
                text=text,words=words,rawTimestamps=timestamps,tokenIds=tokens,score=scores,
                fingerprints=dict(text=canonical(text),words=canonical(words),tokenIds=canonical(tokens),timestamps=canonical(timestamps)))
    write_json(run/'result.json',result)
    (run/'transcript.txt').write_text(text+'\n',encoding='utf-8')
    segments=timestamps.get('segment',[])
    if segments:
        (run/'transcript-segments.txt').write_text('\n'.join(str(s['segment']) for s in segments)+'\n',encoding='utf-8')
    print(json.dumps(dict(status='completed',run=args.single_run,errors=scores['errors'],werPercent=scores['werPercent'],inferenceSeconds=result['inferenceSeconds'],torchPeakAllocatedMiB=result['torchPeakAllocatedMiB'],fingerprints=result['fingerprints']),ensure_ascii=False),flush=True)
    raise SystemExit(0)

assert sha(MODEL)==provenance['sha256']
assert not OUTPUT.exists(), OUTPUT
if args.check_only:
    print(json.dumps(dict(status='ready',model=provenance,versions=versions,scoredWords=N),ensure_ascii=False),flush=True)
    raise SystemExit(0)
OUTPUT.mkdir(exist_ok=False)
for p in [HERE/'benchmark.py',HERE/'Pipfile',HERE/'Pipfile.lock',HERE/'models/provenance.json']:
    shutil.copyfile(p,OUTPUT/p.name)
shutil.copyfile(EVALUATOR,OUTPUT/'original-reference-evaluator.py')
plan=dict(createdAt=datetime.now(timezone.utc).isoformat(),model=provenance,versions=versions,
          preparedAudio=str(AUDIO),preparedAudioSha256=old['preparedAudioSha256'],
          reference=str(REFERENCE),referenceSha256=old['referenceSha256'],scoredWords=N,
          ignoredWords=old['ignoredReferenceWords'],scoringWindow='Word midpoint in [0,60), punctuation ignored, casefold, original reference spelling retained.',
          runCount=args.runs,precision=precision_label,protocol=f'{args.runs} independent Python processes; fresh model, full 3-minute audio, batch_size=1, timestamps enabled. No hints or cleaning.',
          previousWhisperOriginalErrors=53,previousWhisperEnhancedErrors=49,previousWhisperBestErrors=41)
write_json(OUTPUT/'experiment.json',plan)
stop=threading.Event()
samples=[]
def monitor():
    while not stop.is_set():
        try:
            p=subprocess.run(['nvidia-smi','--query-gpu=memory.used,temperature.gpu,power.draw','--format=csv,noheader,nounits'],capture_output=True,text=True,timeout=10)
            if p.returncode==0:samples.append(tuple(map(float,p.stdout.strip().splitlines()[0].split(','))))
        except Exception:pass
        stop.wait(1)
thread=threading.Thread(target=monitor,daemon=True)
thread.start()
started=time.monotonic()
try:
    for index in range(1,args.runs+1):
        with (OUTPUT/f'run-{index:02d}.log').open('w') as log:
            completed=subprocess.run([sys.executable,str(Path(__file__).resolve()),'--single-run',str(index),'--precision',args.precision,'--runs',str(args.runs),'--output-dir',str(OUTPUT)],cwd=HERE,stdout=log,stderr=subprocess.STDOUT)
        if completed.returncode:
            print((OUTPUT/f'run-{index:02d}.log').read_text()[-18000:],flush=True)
            raise RuntimeError(f'Parakeet run {index} failed with exit code {completed.returncode}')
        result=json.loads((OUTPUT/f'run-{index:02d}/result.json').read_text())
        print(json.dumps(dict(status='completed',run=index,errors=result['score']['errors'],werPercent=result['score']['werPercent'],inferenceSeconds=result['inferenceSeconds']),ensure_ascii=False),flush=True)
        if index<args.runs:time.sleep(10)
    results=[json.loads((OUTPUT/f'run-{i:02d}/result.json').read_text()) for i in range(1,args.runs+1)]
    assert len({r['processId'] for r in results})==args.runs
    assert sha(MODEL)==provenance['sha256'] and sha(REFERENCE)==old['referenceSha256'] and sha(AUDIO)==old['preparedAudioSha256']
    fingerprints={key:len({r['fingerprints'][key] for r in results}) for key in results[0]['fingerprints']}
    summary=dict(errors=[r['score']['errors'] for r in results],werValues=[r['score']['werPercent'] for r in results],distinctFingerprints=fingerprints)
    write_json(OUTPUT/'comparison.json',dict(completed=True,experiment=plan,summary=summary,results=results))
    lines=['Parakeet TDT 0.6B v3 vs Whisper - original unfiltered 3-minute sample.',
           'Scored first minute: 113 words, two uncertain reference words ignored.',
           'Whisper original: 53 deviations, WER 46.90%.',
           'Whisper enhanced audio: 49 deviations, WER 43.36%.',
           'Whisper hints + no previous context: 41 deviations, WER 36.28%.','']
    lines += [f"Parakeet run {r['run']}: {r['score']['errors']} deviations, WER {r['score']['werPercent']}%, inference {r['inferenceSeconds']}s" for r in results]
    lines += ['',f'Distinct fingerprints: {fingerprints}',
              'WER reflects only this one minute; inspect full transcripts for meaning, omissions and repetition.']
    (OUTPUT/'comparison.txt').write_text('\n'.join(lines)+'\n',encoding='utf-8')
finally:
    stop.set();thread.join(timeout=12)
    metrics=dict(elapsedSeconds=round(time.monotonic()-started,3),gpuSampleCount=len(samples),
                 peakTotalGpuMemoryMiB=max((s[0] for s in samples),default=None),
                 peakGpuTemperatureC=max((s[1] for s in samples),default=None),peakGpuPowerW=max((s[2] for s in samples),default=None))
    write_json(OUTPUT/'run-metrics.json',metrics)
    print(json.dumps(dict(status='finished',metrics=metrics),ensure_ascii=False),flush=True)
