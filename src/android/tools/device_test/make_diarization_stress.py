#!/usr/bin/env python3
"""Controlled stress variants of frozen human audio, not additional human labels."""
import argparse
import hashlib
import json
from pathlib import Path
import wave
import numpy as np


def insert_gap(reference, cut_ms, gap_ms):
    shifted=[]
    for a,b,speaker in reference:
        if a<cut_ms: shifted.append([a,min(b,cut_ms),speaker])
        if b>cut_ms: shifted.append([max(a,cut_ms)+gap_ms,b+gap_ms,speaker])
    return shifted


def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('source',type=Path);ap.add_argument('output',type=Path)
    args=ap.parse_args()
    if (args.output/'manifest.json').exists():raise SystemExit('Use a new output to preserve frozen inputs')
    source=json.loads((args.source/'manifest.json').read_text())
    records=[]
    variants=[('noise10','wdvva'),('quiet18','wdvva'),('gap30','lubpm'),('silence30',None)]
    for variant,name in variants:
        if name:
            original=next(r for r in source['records'] if r['id']==name)
            audio=args.source/name/'audio-16k.wav'
            if hashlib.sha256(audio.read_bytes()).hexdigest()!=original['audio_sha256']:raise ValueError('Source changed')
            with wave.open(str(audio),'rb') as wav:pcm=np.frombuffer(wav.readframes(wav.getnframes()),dtype='<i2').copy()
            reference=original['reference']
        else:pcm=np.zeros(30*16000,dtype='<i2');reference=[]
        if variant=='noise10':
            rng=np.random.default_rng(20261106)
            signal=pcm.astype(np.float64)
            noise=rng.normal(size=len(signal))
            noise*=np.sqrt(np.mean(signal**2)/10/np.mean(noise**2))
            mixed=signal+noise
            mixed*=min(1,32767/max(1,np.max(np.abs(mixed))))
            pcm=np.rint(mixed).astype('<i2')
        elif variant=='quiet18':pcm=np.rint(pcm.astype(np.float64)*10**(-18/20)).astype('<i2')
        elif variant=='gap30':
            cut_ms=len(pcm)//32
            cut=cut_ms*16
            pcm=np.concatenate([pcm[:cut],np.zeros(30*16000,dtype='<i2'),pcm[cut:]])
            reference=insert_gap(reference,cut_ms,30000)
        identity=(name or 'negative')+'_'+variant
        directory=args.output/identity;directory.mkdir(parents=True,exist_ok=True)
        audio=directory/'audio-16k.wav'
        with wave.open(str(audio),'wb') as wav:
            wav.setnchannels(1);wav.setsampwidth(2);wav.setframerate(16000);wav.writeframes(pcm.tobytes())
        (directory/'reference.json').write_text(json.dumps(reference))
        records.append(dict(id=identity,audio_ms=len(pcm)//16,reference_speakers=len({r[2] for r in reference}),
                            reference=reference,audio_sha256=hashlib.sha256(audio.read_bytes()).hexdigest(),
                            variant=variant,source_id=name))
    manifest=dict(dataset='Controlled stress on VoxConverse test v0.3',source=source['source'],
                  selection='Fixed 10dB Gaussian noise, -18dB gain, 30s inserted pause, pure silence',
                  license=source['license'],evaluation_only=True,speaker_count_hint=False,records=records,
                  limitation='Artificial perturbations; do not establish accuracy on real far-field microphones or real noise')
    (args.output/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
    print('Stress variants:',len(records))


if __name__=='__main__':main()
