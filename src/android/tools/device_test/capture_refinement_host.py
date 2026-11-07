#!/usr/bin/env python3
"""Capture planned short-window observations locally; never chooses a speaker count."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
from pathlib import Path
import time
import wave
import numpy as np
import onnxruntime as ort
from capture_evidence_host import fbank

ort.disable_telemetry_events()

def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('fixture',type=Path)
    ap.add_argument('--model',type=Path,default=Path(__file__).parent/'assets/models/speaker_model.onnx')
    ap.add_argument('--audio',type=Path)
    args=ap.parse_args()
    assert hashlib.sha256(args.model.read_bytes()).hexdigest()=='3955447b0499dc9e0a4541a895df08b03c69098eba4e56c02b5603e9f7f4fcbb'
    plan=json.loads((args.fixture/'refinement-plan.json').read_text())
    options=ort.SessionOptions();options.intra_op_num_threads=2;options.inter_op_num_threads=1
    session=ort.InferenceSession(str(args.model),options,providers=['CPUExecutionProvider'])
    name=session.get_inputs()[0].name
    started=time.monotonic()
    def capture(span):
        with wave.open(str(args.audio or args.fixture/'audio-16k.wav'),'rb') as src:
            assert src.getnchannels()==1 and src.getsampwidth()==2 and src.getframerate()==16000
            src.setpos(span['startMs']*16)
            samples=np.frombuffer(src.readframes((span['endMs']-span['startMs'])*16),dtype='<i2')
        vector=session.run(None,{name:fbank(samples)[None]})[0].reshape(-1).astype(np.float32)
        vector/=np.linalg.norm(vector)
        if not np.isfinite(vector).all():raise ValueError('Invalid observation')
        return dict(startMs=span['startMs'],endMs=span['endMs'],embedding=vector.tolist())
    results=[]
    with ThreadPoolExecutor(max_workers=3) as pool:
        for observation in pool.map(capture,plan):results.append(observation)
    out=args.fixture/'refinement-evidence.json'
    out.write_text(json.dumps(results,separators=(',',':')));out.chmod(0o600)
    print('Short-window observations:',len(results),'seconds:',round(time.monotonic()-started,3))

if __name__=='__main__':main()
