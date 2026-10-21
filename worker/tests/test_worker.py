import copy
import contextlib
import io
import subprocess
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
from types import SimpleNamespace as NS
from fractions import Fraction

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from transcriber_worker.protocol import run_request, load_request
from transcriber_worker.engine import Engine, probe, read_block

VERSIONS = {'faster-whisper': '1', 'ctranslate2': '2'}

class WorkerTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        root = Path(self.tmp.name)
        self.model = root / 'model'
        self.model.mkdir()
        for name in ('model.bin', 'config.json', 'tokenizer.json', 'preprocessor_config.json'):
            (self.model / name).write_text('{}')
        self.request = dict(schemaVersion=1, workDir=str(root), modelDir=str(self.model),
            inputSha256='a'*64, configSha256='b'*64,
            config=dict(modelDir=str(self.model), modelSha256='c'*64, device='cuda',
                computeType='float16', language='cs', beamSize=5, temperature=0.0,
                conditionOnPreviousText=True, vadFilter=False, wordTimestamps=True,
                targetSeconds=600, searchSeconds=30, overlapSeconds=2, engineVersions=VERSIONS),
            plan=dict(schemaVersion=1, duration=4, blocks=[dict(id=0,start=0,end=2),dict(id=1,start=2,end=4)]),
            pendingBlockIds=[0,1])
        self.enterContext(contextlib.redirect_stderr(io.StringIO()))
        self.events = []
        self.calls = []
        self.addCleanup(patch.stopall)
        patch('transcriber_worker.protocol.get_versions', return_value=VERSIONS).start()

    def factory(self, model, config):
        self.calls.append(('load', model, config))
        owner = self
        class Fake:
            def transcribe(self, audio, block):
                owner.calls.append(('block',block['id']))
                return [dict(start=0,end=1,text='Česká přednáška',words=[dict(start=0,end=1,text='Česká')])]
        return Fake()

    def test_loads_engine_once_for_pending_blocks(self):
        self.assertEqual(0, run_request(self.request, self.factory, self.events.append))
        self.assertEqual(['load','block','block'], [c[0] for c in self.calls])
        self.request['pendingBlockIds'] = []
        self.calls.clear()
        self.assertEqual(0, run_request(self.request, self.factory, self.events.append))
        self.assertEqual([], self.calls)

    def test_writes_result_before_done(self):
        def emit(event):
            if event['type'] == 'block_done':
                p = Path(self.request['workDir']) / 'blocks' / f"{event['blockId']:06d}.json"
                result = json.loads(p.read_text())
                self.assertEqual('Česká přednáška', result['segments'][0]['text'])
                self.assertEqual(event['blockId'], result['blockId'])
                self.assertEqual('a'*64, result['inputSha256'])
                self.assertEqual('b'*64, result['configSha256'])
        self.assertEqual(0, run_request(self.request, self.factory, emit))
        self.assertEqual([], list(Path(self.request['workDir']).rglob('*.tmp')))

    def test_failure_emits_error_without_fallback(self):
        def broken(*args):
            self.calls.append('attempt')
            raise RuntimeError('GPU failed')
        self.assertEqual(1, run_request(self.request, broken, self.events.append))
        self.assertEqual(['attempt'], self.calls)
        self.assertEqual(['error'], [e['type'] for e in self.events])
        self.assertIn('GPU failed', self.events[0]['message'])

    def test_inference_failure_never_commits_or_retries(self):
        attempts = []
        class Broken:
            def transcribe(self, audio, block):
                attempts.append(block['id'])
                raise RuntimeError('inference failed')
        self.assertEqual(1, run_request(self.request, lambda *args: Broken(), self.events.append))
        self.assertEqual([0], attempts)
        self.assertEqual(['ready','error'], [e['type'] for e in self.events])
        self.assertFalse((Path(self.request['workDir']) / 'blocks/000000.json').exists())

    def test_cli_rejects_bad_request_with_json_only_stdout(self):
        path = Path(self.tmp.name) / 'bad.json'
        path.write_text('{broken')
        result = subprocess.run([sys.executable, '-m', 'transcriber_worker', '--request', str(path)],
            cwd=Path(__file__).resolve().parents[1],capture_output=True,text=True,timeout=5)
        self.assertEqual(1,result.returncode)
        lines = result.stdout.splitlines()
        self.assertEqual(1,len(lines))
        self.assertEqual('error',json.loads(lines[0])['type'])
        self.assertTrue(result.stderr)

    def test_forces_local_cuda_and_czech(self):
        loaded, transcribed = [], []
        class Model:
            def __init__(self, *args, **kw): loaded.append((args,kw))
            def transcribe(self, audio, **kw):
                transcribed.append(kw)
                return iter([NS(start=0,end=1,text='česky',words=[NS(start=0,end=1,word='česky')])]), None
        with patch.dict(sys.modules, {'faster_whisper': NS(WhisperModel=Model)}), patch('transcriber_worker.engine.read_block', return_value='samples'):
            engine = Engine(self.model, self.request['config'])
            self.assertEqual('česky', engine.transcribe(Path('audio.wav'), dict(start=0,end=2))[0]['text'])
        self.assertEqual([((str(self.model),),dict(device='cuda',compute_type='float16',local_files_only=True))], loaded)
        self.assertEqual([dict(language='cs', task='transcribe', beam_size=5, temperature=0.0,
            condition_on_previous_text=True, vad_filter=False, word_timestamps=True, initial_prompt=None)], transcribed)
        self.assertEqual('1', os.environ['HF_HUB_OFFLINE'])
        self.assertEqual('1', os.environ['HF_HUB_DISABLE_TELEMETRY'])

    def test_rejects_coercions_unknown_versions_and_duplicate_ids(self):
        for path, value in [('schemaVersion',True), ('schemaVersion',2), ('pendingBlockIds',[0,0]),
                            ('pendingBlockIds',['0']), ('pendingBlockIds',[2])]:
            req = copy.deepcopy(self.request)
            req[path] = value
            self.assertEqual(1, run_request(req,self.factory,self.events.append))
        for field,value in [('beamSize','5'),('temperature','0'),('vadFilter',0),('device','cpu')]:
            req = copy.deepcopy(self.request)
            req['config'][field] = value
            self.assertEqual(1,run_request(req,self.factory,self.events.append))
        self.assertEqual([], self.calls)
        p = Path(self.tmp.name) / 'request.json'
        p.write_text('{"schemaVersion":1,"schemaVersion":2}')
        with self.assertRaises(ValueError): load_request(p)

    def test_probe_does_not_load_model(self):
        with patch.dict(sys.modules, {'faster_whisper':NS(), 'av':NS(),
             'ctranslate2':NS(get_cuda_device_count=lambda:1,get_supported_compute_types=lambda device:{'float16'})}), \
             patch('transcriber_worker.engine.get_versions',return_value=VERSIONS):
            self.assertEqual(dict(schemaVersion=1,engineVersions=VERSIONS),probe(self.model))
            with self.assertRaises(ValueError): probe(Path('remote/model'))

    def test_rf64_reader_seeks_and_trims_using_frame_timestamps(self):
        # Prepared PCM: 16k mono s16. Seek may land before the requested interval.
        frames = [NS(pts=15996,time_base=Fraction(1,16000),sample_rate=16000,samples=8,
                     to_ndarray=lambda:NS(reshape=lambda _: [10,11,12,13,14,15,16,17])),
                  NS(pts=16004,time_base=Fraction(1,16000),sample_rate=16000,samples=8,
                     to_ndarray=lambda:NS(reshape=lambda _: [18,19,20,21,22,23,24,25])),
                  NS(pts=16012,time_base=Fraction(1,16000),sample_rate=16000,samples=4)]
        stream = NS(time_base=Fraction(1,16000),codec_context=NS(sample_rate=16000,channels=1,format=NS(name='s16')))
        seeks=[]
        class Container:
            streams=NS(audio=[stream])
            def __enter__(self): return self
            def __exit__(self,*args): pass
            def seek(self,*args,**kwargs): seeks.append((args,kwargs))
            def decode(self,**kwargs): yield from frames
        class Array(list):
            def __itruediv__(self, divisor):
                return Array(v/divisor for v in self)
            def __setitem__(self,key,value): super().__setitem__(key,value)
        # Fake NumPy supports only the allocation/slicing operations needed by the block reader.
        with patch.dict(sys.modules, {'av':NS(open=lambda *a,**k:Container()),
             'numpy':NS(empty=lambda n,dtype:Array([0]*n),float32='float32',asarray=lambda x,dtype:x)}):
            result = read_block(Path('audio.rf64.wav'),dict(start=1,end=1.0005))
        self.assertEqual([v/32768 for v in range(14,22)],result)
        self.assertEqual(16000,seeks[0][0][0])
        self.assertTrue(seeks[0][1]['backward'])

if __name__ == '__main__': unittest.main()
