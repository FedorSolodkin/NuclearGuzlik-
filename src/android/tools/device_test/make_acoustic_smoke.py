#!/usr/bin/env python3
"""macOS TTS smoke fixtures, NOT a human-speech quality benchmark."""
from pathlib import Path
import array
import json
import subprocess
import sys
import wave

OUT = Path(__file__).parent / 'assets' / 'diar'
OUT.mkdir(parents=True, exist_ok=True)
text = 'We should review the project today. Please send the final report tomorrow morning.'
clips = []
for voice in ('Daniel', 'Samantha', 'Karen'):
    aiff, wav = OUT / f'{voice}.aiff', OUT / f'{voice}.wav'
    if not wav.exists():
        subprocess.run(['say', '-v', voice, '-r', '170', '-o', str(aiff), text], check=True)
        subprocess.run(['afconvert', '-f', 'WAVE', '-d', 'LEI16@16000', '-c', '1', str(aiff), str(wav)], check=True)
    with wave.open(str(wav), 'rb') as f:
        assert f.getframerate() == 16000 and f.getnchannels() == 1
        pcm = array.array('h', f.readframes(f.getnframes()))
    if sys.byteorder != 'little': pcm.byteswap()
    audible = [i for i, sample in enumerate(pcm) if abs(sample) > 200]
    clips.append(pcm[max(0, audible[0]-160):min(len(pcm), audible[-1]+161)])


def fixture(name, schedule):
    n = max(start + len(clips[speaker]) for start, speaker in schedule) + 8000
    mixed = [0] * n
    ref = []
    for start, speaker in schedule:
        clip = clips[speaker]
        for i, x in enumerate(clip): mixed[start+i] += int(x * .45)
        ref.append([start*1000/16000, (start+len(clip))*1000/16000, str(speaker)])
    out = array.array('h', (max(-32768, min(32767, x)) for x in mixed))
    if sys.byteorder != 'little': out.byteswap()
    (OUT / f'{name}.s16').write_bytes(out.tobytes())
    (OUT / f'{name}.reference.json').write_text(json.dumps(ref))
    print(name, round(n/16000, 2), 'seconds')

fixture('tts_monologue', [(8000, 0), (16000+len(clips[0]), 0)])
t = 8000; schedule = []
for k in [0, 1, 0, 1]:
    schedule.append((t, k)); t += len(clips[k]) + 8000
fixture('tts_dialogue', schedule)
fixture('tts_overlap', [(8000, 0), (8000+len(clips[0])-16000, 1), (len(clips[0])+len(clips[1])+8000, 0)])

t = 8000; schedule = []
for k in [0, 1, 2, 0, 2, 1]:
    schedule.append((t, k)); t += len(clips[k]) + 6400
fixture('tts_three_speakers', schedule)
t = 8000; schedule = []
for k in [0, 1] * 6:
    schedule.append((t, k)); t += len(clips[k]) + 8000
fixture('tts_one_minute', schedule)
# A new voice only produces very short acknowledgements between longer turns.
aiff, wav = OUT / 'reply.aiff', OUT / 'reply.wav'
if not wav.exists():
    subprocess.run(['say', '-v', 'Samantha', '-r', '190', '-o', str(aiff), 'Yes.'], check=True)
    subprocess.run(['afconvert', '-f', 'WAVE', '-d', 'LEI16@16000', '-c', '1', str(aiff), str(wav)], check=True)
with wave.open(str(wav), 'rb') as f: reply = array.array('h', f.readframes(f.getnframes()))
if sys.byteorder != 'little': reply.byteswap()
audible = [i for i,x in enumerate(reply) if abs(x) > 200]
clips[1] = reply[max(0,audible[0]-160):min(len(reply),audible[-1]+161)]
t = 8000; schedule = []
for k in [0, 1, 0, 1, 0, 1]:
    schedule.append((t, k)); t += len(clips[k]) + 3200
fixture('tts_short_replies', schedule)

# Non-repetitive control for end-to-end ASR: repeated identical sentences can
# collapse in Whisper independently of diarization.
lines = [
    ('Daniel', 'The budget for this project is fifty thousand rubles.'),
    ('Samantha', 'That sounds reasonable. I will prepare the financial report by Friday.'),
    ('Daniel', 'Please include the equipment costs and send the document to Anna.'),
    ('Samantha', 'I agree. We should meet again next Monday to review the results.')]
natural = array.array('h', [0] * 8000); ref = []
for i, (voice, line) in enumerate(lines):
    aiff, wav = OUT / f'natural_{i}.aiff', OUT / f'natural_{i}.wav'
    if not wav.exists():
        subprocess.run(['say', '-v', voice, '-r', '170', '-o', str(aiff), line], check=True)
        subprocess.run(['afconvert', '-f', 'WAVE', '-d', 'LEI16@16000', '-c', '1', str(aiff), str(wav)], check=True)
    with wave.open(str(wav), 'rb') as f: clip = array.array('h', f.readframes(f.getnframes()))
    if sys.byteorder != 'little': clip.byteswap()
    audible = [j for j,x in enumerate(clip) if abs(x) > 200]
    clip = clip[max(0,audible[0]-160):min(len(clip),audible[-1]+161)]
    start = len(natural)
    natural.extend(int(x*.45) for x in clip)
    ref.append([start*1000/16000, len(natural)*1000/16000, str(i%2)])
    natural.extend([0]*8000)
if sys.byteorder != 'little': natural.byteswap()
(OUT / 'tts_natural_dialogue.s16').write_bytes(natural.tobytes())
(OUT / 'tts_natural_dialogue.reference.json').write_text(json.dumps(ref))
(OUT / 'tts_natural_dialogue.text.json').write_text(json.dumps(lines))
print('tts_natural_dialogue', round(len(natural)/16000,2), 'seconds')
