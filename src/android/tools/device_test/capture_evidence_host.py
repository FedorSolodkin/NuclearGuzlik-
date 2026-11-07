#!/usr/bin/env python3
"""Local CPU diagnostic capture matching Android extraction. No remote inference.

Requires numpy and onnxruntime. Validate parity on the same PCM before comparing
results across runtimes. This tool is for offline evaluation, not Android timing.
"""
import argparse
from collections import deque
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import os
from pathlib import Path
import time
import wave

os.environ.setdefault('VECLIB_MAXIMUM_THREADS', '1')
os.environ.setdefault('OPENBLAS_NUM_THREADS', '1')
import numpy as np
import onnxruntime as ort
ort.disable_telemetry_events()

CHUNK, HOP, MIN = 160000, 80000, 8000
WINDOW = (0.5 - 0.5*np.cos(2*np.pi*np.arange(400)/399))**.85
mel = lambda x: 1127*np.log1p(x/700)
delta = (mel(8000)-mel(20))/81
left = mel(20) + np.arange(80)*delta
bins = mel(np.arange(256)*16000/512)
BANKS = np.maximum(0, np.minimum((bins[None,:]-left[:,None])/delta,
    (left[:,None]+2*delta-bins[None,:])/delta)).astype(np.float32)


def fbank(pcm, cmn=True):
    frames = np.lib.stride_tricks.sliding_window_view(pcm.astype(np.float32), 400)[::160].copy()
    frames -= frames.mean(axis=1, keepdims=True)
    frames[:,1:] -= np.float32(.97)*frames[:,:-1].copy()
    frames[:,0] *= np.float32(1)-np.float32(.97)
    frames *= WINDOW.astype(np.float32)
    fft = np.fft.rfft(frames, n=512)[:,:256]
    power = (fft.real**2 + fft.imag**2).astype(np.float32)
    features = np.log(np.maximum(power @ BANKS.T, np.float32(1.1920929e-7))).astype(np.float32)
    if cmn: features -= features.mean(axis=0, keepdims=True)
    return features


def runs(ids, valid, speaker=None):
    out=[];i=0
    while i<len(ids):
        value=int(ids[i]);j=i+1
        while j<len(ids) and ids[j]==value:j+=1
        a=min(valid,i*CHUNK//len(ids));b=min(valid,j*CHUNK//len(ids))
        if value and b>a:out.append((speaker or value,a,b))
        i=j
    return out


def capture(pcm, models, workers):
    def session(name):
        options=ort.SessionOptions();options.intra_op_num_threads=2;options.inter_op_num_threads=1
        return ort.InferenceSession(str(models/name), options, providers=['CPUExecutionProvider'])
    seg=session('pyannote_segmentation.onnx');emb=session('speaker_model.onnx')
    seg_name=seg.get_inputs()[0].name;emb_name=emb.get_inputs()[0].name
    def embed(clean):
        vector=emb.run(None,{emb_name:fbank(clean)[None]})[0].reshape(-1).astype(np.float32)
        if not np.isfinite(vector).all():raise ValueError('Invalid embedding')
        norm=np.linalg.norm(vector)
        if norm<1e-8:raise ValueError('Zero embedding')
        return vector/norm
    def chunk(start):
        valid=min(CHUNK,len(pcm)-start);audio=pcm[start:start+valid]
        padded=np.zeros(CHUNK,dtype=np.float32);padded[:valid]=audio.astype(np.float32)/32768
        logits=seg.run(None,{seg_name:padded[None,None]})[0][0]
        classes=np.argmax(logits,axis=1)
        solo=runs(np.where((classes>=1)&(classes<=3),classes,0),valid)
        masks=np.array([0,1,2,4,3,5,6])[classes]
        activity=[]
        for s in (1,2,3):activity+=runs((masks & (1<<(s-1)))!=0,valid,s)
        anchors=[]
        for s,a,b in solo:
            if b-a>=MIN:anchors.append(((s,a,b),embed(audio[a:b])))
        short=[r for r in solo if r[2]-r[1]<MIN]
        for speaker in dict.fromkeys(r[0] for r in short):
            selected=[r for r in short if r[0]==speaker]
            clean=np.concatenate([audio[a:b] for _,a,b in selected])
            if len(clean)>=MIN:
                vector=embed(clean)
                for r in selected:anchors.append((r,vector))
        found=[]
        for speaker,a,b in activity:
            candidates=[i for i,(r,_) in enumerate(anchors) if r[0]==speaker]
            if not candidates:continue
            ordered=sorted(candidates,key=lambda i:anchors[i][0][1]);cuts={a,b}
            for i in ordered:
                _,x,y=anchors[i][0]
                if a<=x<=b:cuts.add(x)
                if a<=y<=b:cuts.add(y)
            for i,j in zip(ordered,ordered[1:]):
                middle=(anchors[i][0][2]+anchors[j][0][1])//2
                if a<middle<b:cuts.add(middle)
            points=sorted(cuts)
            for x,y in zip(points,points[1:]):
                mid=(x+y)//2
                index=min(candidates,key=lambda i:max(anchors[i][0][1]-mid,mid-anchors[i][0][2],0))
                found.append(((start+x)*1000//16000,(start+y)*1000//16000,
                              anchors[index][1],(start+anchors[index][0][1])*1000//16000))
        return found
    starts=list(range(0,len(pcm),HOP));vectors=[];regions=[];identity={}
    iterator=iter(enumerate(starts));pending=deque();t0=time.monotonic()
    with ThreadPoolExecutor(max_workers=workers) as pool:
        def submit():
            try:i,start=next(iterator)
            except StopIteration:return
            pending.append((i,pool.submit(chunk,start)))
        for _ in range(workers*2):submit()
        while pending:
            i,future=pending.popleft()
            for a,b,vector,preview in future.result():
                key=id(vector)
                if key not in identity:
                    identity[key]=len(vectors);vectors.append(vector)
                regions.append(dict(startMs=a,endMs=b,embedding=identity[key],window=i,previewMs=preview))
            submit()
            if i%50==0 or not pending:print(f'{i+1}/{len(starts)} windows, {time.monotonic()-t0:.1f}s',flush=True)
    return dict(embeddings=[v.tolist() for v in vectors],regions=regions,
                windows=[dict(startMs=s*1000//16000,endMs=min(s+CHUNK,len(pcm))*1000//16000) for s in starts],
                runtime='local CPU onnxruntime '+ort.__version__,capture_ms=round((time.monotonic()-t0)*1000),audio_ms=len(pcm)*1000//16000)


def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('audio',type=Path);ap.add_argument('output',type=Path)
    ap.add_argument('--models',type=Path,default=Path(__file__).parent/'assets/models');ap.add_argument('--workers',type=int,default=3)
    ap.add_argument('--verify-fixture',type=Path)
    args=ap.parse_args()
    if args.verify_fixture:
        lines=args.verify_fixture.read_text().splitlines();samples=np.fromstring(lines[0],sep=',',dtype=np.int16)
        expected=np.array([np.fromstring(line,sep=',') for line in lines[1:] if line.strip()])
        error=float(np.max(np.abs(fbank(samples,False)-expected)));print('Kaldi fixture max error:',error)
        assert error<.02
    for name,expected in [('speaker_model.onnx','3955447b0499dc9e0a4541a895df08b03c69098eba4e56c02b5603e9f7f4fcbb'),
                          ('pyannote_segmentation.onnx','057ee564753071c0b09b5b611648b50ac188d50846bff5f01e9f7bbf1591ea25')]:
        assert hashlib.sha256((args.models/name).read_bytes()).hexdigest()==expected, name
    if args.audio.suffix=='.wav':
        with wave.open(str(args.audio),'rb') as f:
            assert f.getnchannels()==1 and f.getsampwidth()==2 and f.getframerate()==16000
            pcm=np.frombuffer(f.readframes(f.getnframes()),dtype='<i2')
    else:pcm=np.fromfile(args.audio,dtype='<i2')
    result=capture(pcm,args.models,args.workers)
    args.output.write_text(json.dumps(result,separators=(',',':')));args.output.chmod(0o600)
    print('Done:',result['capture_ms'],'ms, embeddings:',len(result['embeddings']))


if __name__=='__main__':main()
