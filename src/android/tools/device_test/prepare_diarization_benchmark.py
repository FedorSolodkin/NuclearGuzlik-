#!/usr/bin/env python3
"""Fetch a small, fixed VoxConverse subset without downloading the 4 GB audio ZIP.

Uses the authors' test RTTMs (v0.3); annotations are evaluation-only inputs.
Audio stays in the ignored diagnostic assets directory. No private data upload.
"""
import argparse
import hashlib
import io
import json
import time
from pathlib import Path, PurePosixPath
import urllib.request
import wave
import zipfile
import zlib

URL = 'https://www.robots.ox.ac.uk/~vgg/data/voxconverse/data/voxconverse_test_wav.zip'


class RangeReader(io.RawIOBase):
    """Read-only seekable HTTP archive; refuse servers ignoring byte ranges."""
    def __init__(self, url):
        self.url, self.position, self.etag = url, 0, None
        self.cached_start,self.cached_data=0,b''
        request = urllib.request.Request(url, headers={'Range': 'bytes=0-0'})
        with urllib.request.urlopen(request, timeout=30) as response:
            if response.status != 206:
                raise ValueError('Server must support partial HTTP reads')
            self.size = int(response.headers['Content-Range'].split('/')[-1])
            self.etag = response.headers.get('ETag')
            response.read(1)

    def seekable(self): return True
    def readable(self): return True
    def tell(self): return self.position

    def seek(self, offset, whence=0):
        self.position = offset + (0 if whence == 0 else self.position if whence == 1 else self.size)
        if self.position < 0: raise ValueError('Negative seek')
        return self.position

    def read(self, size=-1):
        size = min(self.size-self.position, size if size >= 0 else self.size)
        if size <= 0: return b''
        if size > 32_000_000: raise ValueError('Unexpectedly large archive member')
        offset=self.position-self.cached_start
        if 0<=offset and offset+size<=len(self.cached_data):
            data=self.cached_data[offset:offset+size]
        else:
            amount=min(self.size-self.position,max(size,65536))
            end = self.position+amount-1
            headers = {'Range': f'bytes={self.position}-{end}'}
            if self.etag: headers['If-Range'] = self.etag
            request = urllib.request.Request(self.url, headers=headers)
            for attempt in range(3):
                try:
                    with urllib.request.urlopen(request, timeout=30) as response:
                        expected = f'bytes {self.position}-{end}/{self.size}'
                        if response.status != 206 or response.headers.get('Content-Range') != expected:
                            raise ValueError('Archive changed or server ignored byte range')
                        fetched = response.read(amount+1)
                        if len(fetched) != amount: raise ValueError('Incomplete range')
                    break
                except (urllib.error.URLError,TimeoutError,ConnectionError):
                    if attempt==2:raise
                    time.sleep(.5*(attempt+1))
            self.cached_start,self.cached_data=self.position,fetched
            data=fetched[:size]
        self.position += size
        return data


def read_references(archive):
    references = {}
    with zipfile.ZipFile(archive) as z:
        for item in z.infolist():
            path = PurePosixPath(item.filename)
            if 'test' not in path.parts or path.suffix != '.rttm': continue
            regions = []
            for line in z.read(item).decode().splitlines():
                if not line.strip(): continue
                fields = line.split()
                start = round(float(fields[3])*1000)
                end = round((float(fields[3])+float(fields[4]))*1000)
                if end > start: regions.append([start, end, fields[7]])
            if regions: references[path.stem] = regions
    return references


def select_cases(references, per_count=2, skip_per_count=0):
    selected = []
    for count in (1, 2, 3, 4, 5):
        candidates = [name for name, regions in references.items()
                      if len({r[2] for r in regions}) == count and max(r[1] for r in regions) >= 30_000]
        candidates.sort(key=lambda name: (max(r[1] for r in references[name]), name))
        if len(candidates) < per_count+skip_per_count: raise ValueError(f'Insufficient {count}-speaker references')
        selected.extend(candidates[skip_per_count:skip_per_count+per_count])
    return selected


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('annotations', type=Path, help='ZIP from github.com/joonson/voxconverse')
    parser.add_argument('output', type=Path)
    parser.add_argument('--skip-per-count',type=int,default=0,help='Reserve a disjoint subsequent subset for confirmation')
    args = parser.parse_args()
    if (args.output/'manifest.json').exists():
        raise SystemExit('Manifest already exists; use a new directory to keep the evaluation frozen')
    refs = read_references(args.annotations)
    if args.skip_per_count<0:parser.error('skip-per-count must be nonnegative')
    selected = select_cases(refs,skip_per_count=args.skip_per_count)
    args.output.mkdir(parents=True, exist_ok=True)
    records = []
    with zipfile.ZipFile(RangeReader(URL)) as audio:
        members = {PurePosixPath(n).stem: n for n in audio.namelist() if n.endswith('.wav')}
        for name in selected:
            item = audio.getinfo(members[name])
            if item.file_size > 32_000_000: raise ValueError('Unexpected WAV size')
            directory = args.output/name
            directory.mkdir(exist_ok=True)
            path = directory/'audio-16k.wav'
            data=path.read_bytes() if path.exists() else b''
            if len(data)!=item.file_size or zlib.crc32(data)!=item.CRC:data=audio.read(item)
            path.write_bytes(data)
            with wave.open(str(path), 'rb') as wav:
                if (wav.getnchannels(), wav.getsampwidth(), wav.getframerate()) != (1, 2, 16000):
                    raise ValueError('Expected 16 kHz mono PCM16 audio')
                duration = round(wav.getnframes()*1000/16000)
            (directory/'reference.json').write_text(json.dumps(refs[name]))
            record = dict(id=name, audio_ms=duration, reference_speakers=len({r[2] for r in refs[name]}),
                          audio_sha256=hashlib.sha256(data).hexdigest(), reference=refs[name])
            records.append(record)
            print(name, duration, 'ms, reference voices:', record['reference_speakers'], flush=True)
    manifest = dict(dataset='VoxConverse test v0.3',
                    source='https://github.com/joonson/voxconverse', audio_archive=URL,
                    annotations_sha256=hashlib.sha256(args.annotations.read_bytes()).hexdigest(),
                    license='CC-BY-4.0', selection=f'Two shortest recordings >=30s for each of 1/2/3/4/5 speakers after skipping {args.skip_per_count} per count',
                    evaluation_only=True, speaker_count_hint=False, records=records)
    (args.output/'manifest.json').write_text(json.dumps(manifest, indent=2)+'\n')
    print('Frozen cases:', len(records), '; audio minutes:', round(sum(r['audio_ms'] for r in records)/60_000, 2))


if __name__ == '__main__': main()
