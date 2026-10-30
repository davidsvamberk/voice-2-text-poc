"""Compare real Java block planning/merging with fixed Whisper audio and hints."""
from pathlib import Path
import argparse, hashlib, json, os, re, shutil, subprocess, sys, time
ROOT=Path(__file__).resolve().parents[2]
HERE=Path(__file__).resolve().parent
SAMPLES=Path('/home/david/transcriber-runs/260613_090426-20261023-01/samples')
PREVIOUS=SAMPLES/'audio-enhancement-20261029-01'
AUDIO=PREVIOUS/'01-original/audio-16k.wav'
OUTPUT=SAMPLES/'whisper-chunking-20261029-01'
REFERENCE=SAMPLES/'manual-reference-003500-003600.txt'
JAVA=Path('/home/david/.sdkman/candidates/java/25.0.3-tem')
DEPS=[Path('/home/david/.m2/repository')/p for p in [
 'tools/jackson/core/jackson-databind/3.1.4/jackson-databind-3.1.4.jar',
 'tools/jackson/core/jackson-core/3.1.4/jackson-core-3.1.4.jar',
 'com/fasterxml/jackson/core/jackson-annotations/2.21/jackson-annotations-2.21.jar']]
CLASSES=ROOT/'target/whisper-chunking-bridge'
CLASSPATH=os.pathsep.join(map(str,[CLASSES,ROOT/'target/classes',*DEPS]))
os.environ.update(HF_HUB_OFFLINE='1',HF_HUB_DISABLE_TELEMETRY='1',DO_NOT_TRACK='1')
sys.path.insert(0,str(ROOT/'worker'))
sys.path.insert(0,str(ROOT/'experiments/audio-enhancement'))
from transcriber_worker.engine import Engine,get_versions
from transcriber_worker.protocol import validate_segments
from frozen_alignment import align
old=json.loads((PREVIOUS/'comparison.json').read_text())
assert old['completed']
meta=old['experiment'];OPTIONS=meta['options']
assert hashlib.sha256(AUDIO.read_bytes()).hexdigest()==old['results'][0]['audio']['audioSha256']
assert hashlib.sha256(REFERENCE.read_bytes()).hexdigest()==meta['referenceSha256']
assert get_versions()==meta['versions']
ref=[None if m.group().startswith('?') else m.group().casefold() for m in re.finditer(r'\?[^?]+\?|\w+',REFERENCE.read_text())]
assert sum(x is not None for x in ref)==113
parser=argparse.ArgumentParser();parser.add_argument('--check-only',action='store_true');args=parser.parse_args()
CLASSES.mkdir(exist_ok=True)
subprocess.run([str(JAVA/'bin/javac'),'-cp',CLASSPATH,'-d',str(CLASSES),str(HERE/'Bridge.java')],check=True)
if args.check_only:
 assert not OUTPUT.exists()
 print('Ready: identical PCM/reference, Java production planner and assembler compiled.',flush=True)
 raise SystemExit(0)
OUTPUT.mkdir(exist_ok=False)
for path in [Path(__file__),HERE/'Bridge.java',ROOT/'Pipfile.lock',ROOT/'experiments/audio-enhancement/frozen_alignment.py']:
 shutil.copyfile(path,OUTPUT/path.name)
save=lambda p,d:Path(p).write_text(json.dumps(d,ensure_ascii=False,indent=2)+'\n')
env=os.environ.copy();env['LD_LIBRARY_PATH']=str(ROOT/'.venv/ffmpeg-runtime/usr/lib/x86_64-linux-gnu')
p=subprocess.run([str(ROOT/'.venv/bin/ffmpeg'),'-hide_banner','-nostdin','-i',str(AUDIO),'-af','silencedetect=noise=-35dB:d=0.5','-f','null','-'],capture_output=True,text=True,env=env,check=True)
(OUTPUT/'silencedetect.log').write_text(p.stderr)
silences=[];start=None
for match in re.finditer(r'silence_(start|end):\s*([\d.]+)',p.stderr):
 if match[1]=='start':start=float(match[2])
 elif start is not None:
  silences.append(dict(start=start,end=min(60.,float(match[2]))));start=None
if start is not None:silences.append(dict(start=start,end=60.))
save(OUTPUT/'silences.json',silences)
plans={}
def bridge(*args):subprocess.run([str(JAVA/'bin/java'),'-cp',CLASSPATH,'Bridge',*map(str,args)],check=True)
for seconds in [20,30,60]:
 path=OUTPUT/f'plan-{seconds}.json';bridge('plan',OUTPUT/'silences.json',60,seconds,path)
 plans[seconds]=json.loads(path.read_text())
specs=[(f'{s}s-{hint}',s,hint) for s in [60,20,30] for hint in ['initial','hotwords']]
save(OUTPUT/'experiment.json',dict(audio=str(AUDIO),audioSha256=old['results'][0]['audio']['audioSha256'],
 reference=str(REFERENCE),referenceSha256=meta['referenceSha256'],modelSha256=meta['modelSha256'],
 options=OPTIONS,variants=specs,plans=plans,searchSeconds=1,overlapSeconds=2,versions=get_versions(),
 note='Exact production BlockPlanner, Engine timestamp normalization and TranscriptAssembler. No audio enhancement.'))
engine=Engine(Path(meta['model']),{})
original=engine.model.transcribe
results=[]
for index,(name,seconds,hint) in enumerate(specs+[('60s-initial-repeat',60,'initial')]):
 folder=OUTPUT/name;folder.mkdir()
 options={**OPTIONS,'initial_prompt':OPTIONS['initial_prompt'] if hint=='initial' else None,
          'hotwords':OPTIONS['initial_prompt'] if hint=='hotwords' else None}
 engine.model.transcribe=lambda audio,**ignored:original(audio,**options)
 print(json.dumps(dict(status='starting',variant=name,blocks=plans[seconds]['blocks'])),flush=True)
 t0=time.monotonic();blocks=[]
 for block in plans[seconds]['blocks']:
  segments=engine.transcribe(AUDIO,block);validate_segments(segments,block['end']-block['start'])
  blocks.append(dict(schemaVersion=1,inputSha256=meta['sourceSha256'],configSha256='a'*64,blockId=block['id'],
    blockStart=block['start'],blockEnd=block['end'],segments=segments))
 config=dict(modelDir=meta['model'],modelSha256=meta['modelSha256'],device='cuda',computeType='float16',language='cs',
   beamSize=5,temperature=0.,conditionOnPreviousText=False,vadFilter=False,wordTimestamps=True,
   targetSeconds=seconds,searchSeconds=1,overlapSeconds=2,engineVersions=get_versions())
 data=dict(manifest=dict(schemaVersion=1,inputSha256=meta['sourceSha256'],configSha256='a'*64,config=config),plan=plans[seconds],results=blocks)
 save(folder/'bridge-input.json',data);bridge('assemble',folder/'bridge-input.json',folder/'transcript.json')
 transcript=json.loads((folder/'transcript.json').read_text())
 text=' '.join(s['text'].strip() for s in transcript['segments'])
 errors,counts,alignment=align(ref,re.findall(r'\w+',text.casefold()))
 result=dict(name=name,targetSeconds=seconds,hint=hint,options=options,errors=errors,werPercent=round(100*errors/113,2),
  counts=counts,alignment=alignment,text=text,blocks=blocks,warnings=transcript['warnings'],elapsedSeconds=round(time.monotonic()-t0,3))
 save(folder/'result.json',result);(folder/'transcript.txt').write_text(text+'\n');results.append(result)
 save(OUTPUT/'comparison.json',dict(results=results,completed=False))
 print(json.dumps(dict(status='done',variant=name,errors=errors,werPercent=result['werPercent'],warnings=len(result['warnings']))),flush=True)
 if index<len(specs):time.sleep(10)
assert results[0]['blocks']==results[-1]['blocks'] and results[0]['text']==results[-1]['text']
assert results[0]['errors']==old['results'][0]['score']['errors']
assert hashlib.sha256(REFERENCE.read_bytes()).hexdigest()==meta['referenceSha256']
save(OUTPUT/'comparison.json',dict(results=results,completed=True,baselineRepeatMatches=True))
lines=['Whisper chunking: same 60s PCM, 113 words, real Java planning and merging, search 1s / overlap 2s.',
 'Variant\tErrors\tWER %\tAmbiguous overlaps\tBlock durations']
for r in results:
 lines.append(f"{r['name']}\t{r['errors']}\t{r['werPercent']}\t{len(r['warnings'])}\t"+str([round(b['blockEnd']-b['blockStart'],3) for b in r['blocks']]))
for r in results[:6]:lines+=['',r['name'],r['text']]
(OUTPUT/'comparison.txt').write_text('\n'.join(lines)+'\n')
