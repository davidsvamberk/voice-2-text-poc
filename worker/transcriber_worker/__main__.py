import argparse
import json
import os
from pathlib import Path
import sys


def main():
    # Reserve the original stdout for JSON; redirect even native-library fd 1 logs.
    with os.fdopen(os.dup(sys.stdout.fileno()), 'w', encoding='utf-8') as protocol_out:
        os.dup2(sys.stderr.fileno(), sys.stdout.fileno())
        def emit(value):
            protocol_out.write(json.dumps(value, ensure_ascii=False, allow_nan=False) + '\n')
            protocol_out.flush()
        try:
            parser = argparse.ArgumentParser()
            mode = parser.add_mutually_exclusive_group(required=True)
            mode.add_argument('--probe', action='store_true')
            mode.add_argument('--request', type=Path)
            parser.add_argument('--model-dir', type=Path)
            args = parser.parse_args()
            from .engine import Engine, probe
            from .protocol import load_request, run_request
            if args.probe:
                if args.model_dir is None:
                    raise ValueError('--probe requires --model-dir')
                emit(probe(args.model_dir))
                return 0
            return run_request(load_request(args.request), Engine, emit)
        except Exception as exc:
            print(str(exc), file=sys.stderr)
            emit(dict(schemaVersion=1, type='error', code='WORKER_FAILURE', message=str(exc)))
            return 1


if __name__ == '__main__':
    raise SystemExit(main())
