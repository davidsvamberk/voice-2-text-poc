"""Lazy ASR imports keep protocol tests independent of inference packages."""
import importlib.metadata
import math
import os
from pathlib import Path

PACKAGES = ('faster-whisper', 'ctranslate2', 'av', 'numpy', 'tokenizers', 'huggingface-hub', 'onnxruntime')


def offline():
    os.environ['HF_HUB_OFFLINE'] = '1'
    os.environ['HF_HUB_DISABLE_TELEMETRY'] = '1'
    os.environ['DO_NOT_TRACK'] = '1'


def local_model(model_dir):
    model_dir = Path(model_dir)
    if not model_dir.is_absolute() or not model_dir.is_dir() or model_dir.is_symlink():
        raise ValueError('Model must be an absolute local directory')
    if any(p.is_symlink() for p in model_dir.rglob('*')):
        raise ValueError('Model symlinks are forbidden')
    for name in ('model.bin', 'config.json', 'tokenizer.json', 'preprocessor_config.json'):
        if not (model_dir / name).is_file():
            raise ValueError(f'Missing local model file: {name}')
    return model_dir


def get_versions():
    return {name: importlib.metadata.version(name) for name in PACKAGES}


def probe(model_dir: Path) -> dict:
    offline()
    local_model(model_dir)
    import faster_whisper  # noqa: F401; importability only, never instantiate a model
    import av  # noqa: F401
    import ctranslate2
    if ctranslate2.get_cuda_device_count() < 1 or 'float16' not in ctranslate2.get_supported_compute_types('cuda'):
        raise RuntimeError('CUDA float16 is unavailable')
    return dict(schemaVersion=1, engineVersions=get_versions())


def read_block(audio_path: Path, block: dict):
    """Seek RF64/PCM and trim decoded frames by PTS; allocate only this block.

    AudioPreparer guarantees mono s16 at 16 kHz. No resampler is needed; frame
    timestamps after a backward seek determine the exact sample intersection.
    """
    import av
    import numpy as np
    rate = 16000
    start = round(block['start'] * rate)
    end = round(block['end'] * rate)
    if end <= start:
        raise ValueError('Empty audio block')
    audio = np.empty(end - start, dtype=np.float32)
    cursor = start
    with av.open(str(audio_path), mode='r') as container:
        stream = container.streams.audio[0]
        ctx = stream.codec_context
        if ctx.sample_rate != rate or ctx.channels != 1 or ctx.format.name != 's16':
            raise ValueError('Expected prepared mono 16 kHz s16 PCM audio')
        container.seek(math.floor(start / rate / stream.time_base), stream=stream, backward=True)
        for frame in container.decode(audio=0):
            if frame.pts is None or frame.sample_rate != rate:
                raise ValueError('Missing/invalid audio frame timestamp')
            frame_start = round(frame.pts * frame.time_base * rate)
            if frame_start >= end:
                break
            left = max(start, frame_start)
            right = min(end, frame_start + frame.samples)
            if right <= left:
                continue
            if left != cursor:
                raise ValueError('Discontinuous audio after seek')
            samples = frame.to_ndarray().reshape(-1)
            audio[left-start:right-start] = np.asarray(samples[left-frame_start:right-frame_start], dtype=np.float32)
            cursor = right
            if cursor == end:
                break
    if cursor != end:
        raise ValueError('Audio ended before block end')
    # In-place normalization avoids retaining a second block-sized array.
    audio /= 32768.0
    return audio


class Engine:
    def __init__(self, model_dir: Path, config: dict):
        offline()
        model_dir = local_model(model_dir)
        from faster_whisper import WhisperModel
        self.model = WhisperModel(str(model_dir), device='cuda', compute_type='float16', local_files_only=True)

    def transcribe(self, audio_path: Path, block: dict) -> list[dict]:
        audio = read_block(audio_path, block)
        segments, _ = self.model.transcribe(audio, language='cs', task='transcribe', beam_size=5,
            temperature=0.0, condition_on_previous_text=True, vad_filter=False,
            word_timestamps=True, initial_prompt=None)
        # Fully consume the lazy generator before committing a result or emitting done.
        return [dict(start=s.start, end=s.end, text=s.text,
                     words=[dict(start=w.start, end=w.end, text=w.word) for w in (s.words or [])])
                for s in segments]
