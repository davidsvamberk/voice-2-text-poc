"""Finite Parakeet tuning grid; same PCM minute, frozen reference, no training."""
from pathlib import Path
import argparse
import ast
from collections import Counter
from contextlib import redirect_stdout, redirect_stderr
from datetime import datetime, timezone
import hashlib
import importlib.metadata
import itertools
import json
import math
import os
import re
import shutil
import subprocess
import sys
import threading
import time

HERE=Path(__file__).resolve().parent
SAMPLES=Path('/home/david/transcriber-runs/260613_090426-20261023-01/samples')
OUTPUT=SAMPLES/'parakeet-tuning-20261028-03'
PREVIOUS=SAMPLES/'asr-options-20261028-01'
AUDIO=SAMPLES/'baseline-original-settings/audio.wav'
REFERENCE=SAMPLES/'manual-reference-003500-003600.txt'
MODEL=HERE/'models/parakeet-tdt-0.6b-v3.nemo'
os.environ.update(HF_HUB_OFFLINE='1',TRANSFORMERS_OFFLINE='1',HF_HUB_DISABLE_TELEMETRY='1',DO_NOT_TRACK='1',
                  HF_HOME=str(HERE/'.cache/huggingface'),NEMO_CACHE_DIR=str(HERE/'.cache/nemo'),
                  TMPDIR=str(HERE/'.cache/tmp'),MPLCONFIGDIR=str(HERE/'.cache/matplotlib'),NUMBA_CACHE_DIR=str(HERE/'.cache/numba'))


def sha(path):
    d=hashlib.sha256()
    with Path(path).open('rb') as f:
        while x:=f.read(8*1024*1024):d.update(x)
    return d.hexdigest()


def canonical(value):
    return hashlib.sha256(json.dumps(value,ensure_ascii=False,sort_keys=True,separators=(',',':'),allow_nan=False).encode()).hexdigest()


def save(path,value):
    p=Path(path);tmp=p.with_suffix(p.suffix+'.tmp')
    tmp.write_text(json.dumps(value,ensure_ascii=False,indent=2,allow_nan=False)+'\n',encoding='utf-8')
    os.replace(tmp,p)


old=json.loads((PREVIOUS/'experiment.json').read_text())
assert sha(AUDIO)==old['preparedAudioSha256'] and sha(REFERENCE)==old['referenceSha256']
reference=[None if m.group().startswith('?') else m.group().casefold() for m in re.finditer(r'\?[^?]+\?|\w+',REFERENCE.read_text())]
N=sum(x is not None for x in reference)
assert N==113
node=next(n for n in ast.parse((PREVIOUS/'original-reference-evaluator.py').read_text()).body if isinstance(n,ast.FunctionDef) and n.name=='align')
ns={};exec(compile(ast.Module(body=[node],type_ignores=[]),'<frozen-WER>','exec'),ns)
align=ns['align']


def score_text(text):
    hyp=re.findall(r'\w+',text.casefold())
    errors,counts,alignment=align(reference,hyp)
    rr=[a['reference'] for a in alignment if a['reference'] is not None]
    hh=[a['hypothesis'] for a in alignment if a['hypothesis'] is not None and a['op']!='mask']
    prev=list(range(len(hh)+1))
    for i,x in enumerate(rr,1):
        row=[i]
        for j,y in enumerate(hh,1):row.append(min(prev[j]+1,row[-1]+1,prev[j-1]+(x!=y)))
        prev=row
    assert prev[-1]==errors
    return dict(errors=errors,werPercent=round(100*errors/N,2),counts=counts,alignment=alignment,
                maskedWords=[a['hypothesis'] for a in alignment if a['op']=='mask'])


for name,expected in [('08-control',53),('04-hints-no-previous',41)]:
    r=json.loads((PREVIOUS/name/'result.json').read_text())
    text=' '.join(w['text'] for s in r['segments'] for w in s['words'] if 0<=(w['start']+w['end'])/2<60)
    assert score_text(text)['errors']==expected

DECODERS=[('greedy_batch','greedy_batch',None),('greedy','greedy',None),
          ('beam4','beam',4),('beam8','beam',8),('maes4','maes',4),('maes8','maes',8)]
GRID=[dict(name=f'{label}-{seconds}s-{attention}',seconds=seconds,strategy=strategy,beamSize=beam,
           attention=attention,scoreNorm=True,temperature=1.0)
      for attention in ['full','local256'] for label,strategy,beam in DECODERS for seconds in [60,15,20,25]]
# Explicitly run baseline, beam and MAES pilots before filling the complete Cartesian product.
priority=['greedy_batch-60s-full','beam4-60s-full','maes4-60s-full','greedy_batch-20s-full','greedy_batch-20s-local256']
GRID.sort(key=lambda x:(priority.index(x['name']) if x['name'] in priority else len(priority),x['name']))
assert len(GRID)==48 and len({x['name'] for x in GRID})==48

parser=argparse.ArgumentParser()
parser.add_argument('--check-only',action='store_true')
parser.add_argument('--pilot-only',action='store_true')
args=parser.parse_args()
import numpy as np
import soundfile as sf
wave,sr=sf.read(str(AUDIO),frames=960000,dtype='float32')
assert sr==16000 and wave.ndim==1 and len(wave)==960000
# Boundaries are chosen at low-energy points near the target, independently of reference text.
frame=1600;hop=400
starts=np.arange(0,len(wave)-frame+1,hop)
squares=wave.astype(np.float64)**2
prefix=np.concatenate(([0.0],np.cumsum(squares)))
energy=(prefix[starts+frame]-prefix[starts])/frame
centers=starts+frame//2
plans={}
for seconds in [15,20,25,60]:
    boundaries=[0]
    while len(wave)-boundaries[-1]>seconds*sr+sr:
        desired=boundaries[-1]+seconds*sr
        mask=(centers>=desired-sr)&(centers<=desired+sr)
        candidates=np.where(mask)[0]
        best=int(candidates[np.argmin(energy[candidates])])
        boundaries.append(int(centers[best]))
    boundaries.append(len(wave))
    plans[seconds]=list(zip(boundaries[:-1],boundaries[1:]))
    assert all(a<b for a,b in plans[seconds])
    assert sum(b-a for a,b in plans[seconds])==len(wave)
provenance=json.loads((HERE/'models/provenance.json').read_text())
assert sha(MODEL)==provenance['sha256']
versions={p:importlib.metadata.version(p) for p in ['nemo-toolkit','torch','torchaudio','numpy','lightning','pyarrow']}
# Complete optional decoder sections from the installed structured defaults.
from omegaconf import OmegaConf,open_dict
from nemo.collections.asr.parts.submodules.rnnt_decoding import RNNTBPEDecodingConfig
import tarfile
with tarfile.open(MODEL,'r|*') as archive:
    for member in archive:
        if member.name.endswith('model_config.yaml'):
            checkpoint_cfg=OmegaConf.create(archive.extractfile(member).read().decode())
            break
    else:
        raise RuntimeError('Checkpoint model config missing')
base_cfg=OmegaConf.to_container(OmegaConf.merge(OmegaConf.create(OmegaConf.to_container(OmegaConf.structured(RNNTBPEDecodingConfig()))),checkpoint_cfg.decoding),resolve=True,enum_to_str=True)


def decoder_config(spec):
    cfg=OmegaConf.create(base_cfg)
    with open_dict(cfg):
        cfg.strategy=spec['strategy'];cfg.compute_timestamps=False;cfg.preserve_alignments=False
        cfg.confidence_cfg.preserve_frame_confidence=False
        cfg.beam.beam_size=spec['beamSize'] or 4
        cfg.beam.return_best_hypothesis=True
        cfg.beam.score_norm=spec['scoreNorm']
        cfg.beam.preserve_alignments=False
        cfg.beam.softmax_temperature=spec['temperature']
        cfg.beam.allow_cuda_graphs=False
        cfg.greedy.use_cuda_graph_decoder=False
    return cfg


for spec in GRID:
    cfg=decoder_config(spec)
    assert cfg.model_type=='tdt' and list(cfg.durations)==[0,1,2,3,4]
    assert not cfg.preserve_alignments and not cfg.compute_timestamps
    assert cfg.confidence_cfg.preserve_frame_confidence is False
    assert cfg.beam.return_best_hypothesis is True

if args.check_only:
    print(json.dumps(dict(status='ready',variantCount=len(GRID),plans={k:[(round(a/sr,3),round(b/sr,3)) for a,b in v] for k,v in plans.items()},versions=versions),ensure_ascii=False),flush=True)
    raise SystemExit(0)

plan=dict(createdAt=datetime.now(timezone.utc).isoformat(),model=provenance,versions=versions,
          preparedAudio=str(AUDIO),preparedAudioSha256=old['preparedAudioSha256'],
          pcmMinuteSha256=hashlib.sha256(wave.tobytes()).hexdigest(),reference=str(REFERENCE),referenceSha256=old['referenceSha256'],
          scoredReferenceWords=N,ignoredReferenceWords=old['ignoredReferenceWords'],
          scoring='Entire text of exactly the first 60 PCM seconds; same casefold, punctuation, spelling and uncertainty masking as preceding evaluation.',
          plansSamples=plans,segmentation='Contiguous, no-overlap partition; low-energy 100ms window center within +/-1s of desired boundary. No removed or repeated samples.',
          variants=GRID,precision='FP32 weights with FP16 autocast',pauseSeconds=10,
          context='full=rel_pos [-1,-1]; local256=rel_pos_local_attn [256,256]',
          attentionSwitchFix='NeMo 2.4 change_attention_model omits use_bias=False; remove introduced linear biases and assert unchanged parameter fingerprints after every switch.',
          timestampMode=False,notes='All variants have timestamps disabled to support TDT beam search. Previous 3-minute WER is a separate control, not the same context.')
if OUTPUT.exists():
    previous=json.loads((OUTPUT/'experiment.json').read_text())
    for key in ['model','versions','preparedAudioSha256','pcmMinuteSha256','referenceSha256','variants','timestampMode']:
        assert previous[key]==json.loads(json.dumps(plan[key])),key
else:
    OUTPUT.mkdir()
    save(OUTPUT/'experiment.json',plan)
shutil.copyfile(__file__,OUTPUT/'tune.py')
shutil.copyfile(HERE/'Pipfile.lock',OUTPUT/'Pipfile.lock')
shutil.copyfile(HERE/'Pipfile',OUTPUT/'Pipfile')
save(SAMPLES/'parakeet-tuning-20261028-02'/'validation-failure.json',dict(
    validForComparison=False,reason='Final full-context control changed. NeMo 2.4 change_attention_model omits checkpoint use_bias=False and introduces random linear biases.',
    replacementExperiment=str(OUTPUT)))

stop=threading.Event();gpu=[]
def monitor():
    while not stop.is_set():
        try:
            p=subprocess.run(['nvidia-smi','--query-gpu=memory.used,temperature.gpu,power.draw','--format=csv,noheader,nounits'],capture_output=True,text=True,timeout=10)
            if p.returncode==0:gpu.append(tuple(map(float,p.stdout.strip().splitlines()[0].split(','))))
        except Exception:pass
        stop.wait(1)
thread=threading.Thread(target=monitor,daemon=True);thread.start();started=time.monotonic()

import torch
import nemo.collections.asr as nemo_asr
from omegaconf import OmegaConf,open_dict
assert torch.cuda.is_available()
model=nemo_asr.models.ASRModel.restore_from(restore_path=str(MODEL),map_location=torch.device('cpu')).to('cuda').eval()
base_cfg=OmegaConf.to_container(OmegaConf.merge(OmegaConf.create(OmegaConf.to_container(OmegaConf.structured(RNNTBPEDecodingConfig()))),model.cfg.decoding),resolve=True,enum_to_str=True)
current_attention='full'
assert model.cfg.encoder.use_bias is False


def attention_fingerprints():
    return {f'{i}.{name}':hashlib.sha256(parameter.detach().cpu().contiguous().numpy().tobytes()).hexdigest()
            for i,layer in enumerate(model.encoder.layers) for name,parameter in layer.self_attn.named_parameters()}


attention_weights=attention_fingerprints()
save(OUTPUT/'attention-parameter-fingerprints.json',attention_weights)


def configure(spec):
    global current_attention
    if current_attention!=spec['attention']:
        if spec['attention']=='full':model.change_attention_model(self_attention_model='rel_pos',att_context_size=[-1,-1])
        else:model.change_attention_model(self_attention_model='rel_pos_local_attn',att_context_size=[256,256])
        # NeMo 2.4 reconstructs attention without passing checkpoint use_bias=False.
        # Its strict=False state load leaves NEW random linear biases in place.
        # Preserve the checkpoint architecture and verify every learned attention tensor.
        for layer in model.encoder.layers:
            for name in ['linear_q','linear_k','linear_v','linear_out']:
                getattr(layer.self_attn,name).bias=None
            layer.self_attn.use_bias=False
        assert attention_fingerprints()==attention_weights, 'Attention parameters changed'
        current_attention=spec['attention']
    cfg=decoder_config(spec)
    model.change_decoding_strategy(cfg,verbose=False)
    model.eval()


def infer_variant(spec):
    folder=OUTPUT/spec['name']
    if (folder/'result.json').exists():
        previous=json.loads((folder/'result.json').read_text())
        assert previous['spec']==spec
        print(json.dumps(dict(status='reused',variant=spec['name'],errors=previous.get('score',{}).get('errors'))),flush=True)
        return previous
    folder.mkdir(exist_ok=True)
    print(json.dumps(dict(status='starting',variant=spec['name'])),flush=True)
    t0=time.monotonic();chunks=[]
    try:
        with (folder/'run.log').open('w') as log,redirect_stdout(log),redirect_stderr(log):
            configure(spec)
            torch.cuda.reset_peak_memory_stats()
            for a,b in plans[spec['seconds']]:
                with torch.inference_mode(),torch.autocast(device_type='cuda',dtype=torch.float16):
                    output=model.transcribe([wave[a:b].copy()],batch_size=1,return_hypotheses=True,num_workers=0,timestamps=None,verbose=False)
                if isinstance(output,tuple):output=output[0]
                assert len(output)==1
                h=output[0]
                assert isinstance(h.text,str)
                tokens=h.y_sequence.detach().cpu().tolist() if hasattr(h.y_sequence,'detach') else list(h.y_sequence)
                chunks.append(dict(start=a/sr,end=b/sr,text=h.text.strip(),tokens=tokens))
            torch.cuda.synchronize()
        text=' '.join(c['text'] for c in chunks).strip()
        scores=score_text(text)
        result=dict(status='ok',spec=spec,chunks=chunks,text=text,score=scores,elapsedSeconds=round(time.monotonic()-t0,3),
                    decodingConfig=OmegaConf.to_container(model.cfg.decoding,resolve=True,enum_to_str=True),
                    torchPeakAllocatedMiB=round(torch.cuda.max_memory_allocated()/1024**2,2),textSha256=canonical(text))
        (folder/'transcript.txt').write_text(text+'\n',encoding='utf-8')
    except Exception as exc:
        import traceback
        result=dict(status='failed',spec=spec,elapsedSeconds=round(time.monotonic()-t0,3),error=repr(exc),traceback=traceback.format_exc(),chunks=chunks)
        torch.cuda.empty_cache()
    save(folder/'result.json',result)
    print(json.dumps(dict(status=result['status'],variant=spec['name'],elapsedSeconds=result['elapsedSeconds'],
                          errors=result.get('score',{}).get('errors'),werPercent=result.get('score',{}).get('werPercent'),error=result.get('error')),ensure_ascii=False),flush=True)
    return result


# Validate the full -> local -> full transition on actual inference before the grid.
probe_spec=next(s for s in GRID if s['name']=='greedy_batch-60s-full')
probe_before=infer_variant({**probe_spec,'name':'preflight-before-switch'})
configure({**probe_spec,'attention':'local256'})
time.sleep(10)
probe_after=infer_variant({**probe_spec,'name':'preflight-after-switch'})
assert probe_before['status']==probe_after['status']=='ok'
assert probe_before['text']==probe_after['text'] and probe_before['chunks']==probe_after['chunks'], 'Preflight round-trip changed inference'
save(OUTPUT/'round-trip-preflight.json',dict(passed=True,textSha256=probe_before['textSha256'],attentionParameterCount=len(attention_weights)))
print(json.dumps(dict(status='attention-round-trip-verified',errors=probe_after['score']['errors'])),flush=True)

results=[]
try:
    specs=[s for s in GRID if not args.pilot_only or s['name'] in priority]
    failure_streak=0
    for index,spec in enumerate(specs):
        result=infer_variant(spec);results.append(result)
        failure_streak=failure_streak+1 if result['status']!='ok' else 0
        if failure_streak>=3:raise RuntimeError('Three consecutive configurations failed; stop and inspect diagnostics')
        save(OUTPUT/'comparison.json',dict(experiment=plan,results=results,completed=False))
        if index<len(specs)-1:time.sleep(10)
    if not args.pilot_only:
        eligible=[r for r in results if r['status']=='ok' and r['spec']['beamSize']]
        if eligible:
            best=min(eligible,key=lambda r:(r['score']['errors'],r['elapsedSeconds']))
            for norm,temp in itertools.product([True,False],[0.8,1.0,1.2]):
                spec={**best['spec'],'name':best['spec']['name']+f'-norm{int(norm)}-temp{temp}','scoreNorm':norm,'temperature':temp}
                time.sleep(10)
                results.append(infer_variant(spec))
                save(OUTPUT/'comparison.json',dict(experiment=plan,results=results,completed=False))
        # Repeat the reference context baseline after switching attention implementations.
        control=next(x for x in GRID if x['name']=='greedy_batch-60s-full')
        time.sleep(10)
        results.append(infer_variant({**control,'name':'final-greedy-control'}))
    if not args.pilot_only:
        first=results[0];last=results[-1]
        assert first['text']==last['text'] and first['chunks']==last['chunks'], 'Round-trip greedy control changed'
    assert attention_fingerprints()==attention_weights
    assert sha(AUDIO)==old['preparedAudioSha256'] and sha(REFERENCE)==old['referenceSha256'] and sha(MODEL)==provenance['sha256']
    save(OUTPUT/'comparison.json',dict(experiment=plan,results=results,completed=True,pilotOnly=args.pilot_only))
    lines=['Parakeet tuning grid - exact same 60-second PCM input and 113 scored reference words.',
           'Two uncertain words ignored; casefold and punctuation normalization unchanged.',
           'Boundaries may move +/-1s to nearby low-energy points; no audio samples dropped or duplicated.',
           'All configurations have timestamps disabled, FP16 autocast, batch size 1.','',
           'Variant\tStatus\tWord deviations\tWER percent\tSeconds']
    lines += [f"{r['spec']['name']}\t{r['status']}\t{r.get('score',{}).get('errors','')}\t{r.get('score',{}).get('werPercent','')}\t{r['elapsedSeconds']}" for r in results]
    (OUTPUT/'comparison.txt').write_text('\n'.join(lines)+'\n',encoding='utf-8')
finally:
    stop.set();thread.join(timeout=12)
    save(OUTPUT/'run-metrics.json',dict(elapsedSeconds=round(time.monotonic()-started,3),gpuSampleCount=len(gpu),
        peakTotalGpuMemoryMiB=max((s[0] for s in gpu),default=None),peakGpuTemperatureC=max((s[1] for s in gpu),default=None),peakGpuPowerW=max((s[2] for s in gpu),default=None)))
