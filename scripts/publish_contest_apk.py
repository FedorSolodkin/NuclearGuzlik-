import concurrent.futures, gzip, hashlib, json, os, pathlib, subprocess
root=pathlib.Path(__file__).resolve().parents[1]
spec=json.loads((root/'config/contest-models.json').read_text())
layout=json.loads((root/'artifacts/release-transfer/layout.json').read_text())
output=root/'VoiceMind-4.0.8-contest-offline.apk'
with gzip.open(root/'artifacts/release-transfer/template.gz','rb') as src,output.open('wb') as dst:
 while block:=src.read(4*1024*1024): dst.write(block)
rename={'speaker_model.onnx':'wespeaker-voxceleb-resnet34-LM.onnx','pyannote_segmentation.onnx':'pyannote-segmentation-3.0.onnx'}
def fetch(model):
 path=root/model['file']
 subprocess.run(['curl','--fail','--location','--retry','4','--connect-timeout','30','--output',str(path),'https://github.com/dext01/voicemind-models/releases/download/models-v1/'+rename.get(model['file'],model['file'])],check=True)
 h=hashlib.sha256()
 with path.open('rb') as f:
  for b in iter(lambda:f.read(4*1024*1024),b''):h.update(b)
 assert path.stat().st_size==model['bytes'] and h.hexdigest()==model['sha256'],model['file']
 return path
with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
 paths=list(pool.map(fetch,spec['models']))
with output.open('r+b') as dst:
 for row in layout:
  dst.seek(row['offset'])
  with (root/row['file']).open('rb') as f:
   for b in iter(lambda:f.read(4*1024*1024),b''):dst.write(b)
h=hashlib.sha256()
with output.open('rb') as f:
 for b in iter(lambda:f.read(4*1024*1024),b''):h.update(b)
assert h.hexdigest()=='ba9151b0dc0205961e7f24fb6f4da874a40f300acf058f0e72bd04c713457adb',h.hexdigest()
repo='FedorSolodkin/NuclearGuzlik-';tag='contest-offline-v4.0.8'
if subprocess.run(['gh','release','view',tag,'--repo',repo],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode:
 subprocess.run(['gh','release','create',tag,'--repo',repo,'--draft','--target','codex/android-boundary-word-timing','--title','VoiceMind 4.0.8 — точный Whisper + Gemma, всё внутри APK','--notes','Одна автономная APK, точный Whisper podlodka и Gemma ru90k уже внутри. Android 8+, ARM64. Модели извлекаются при первом запуске без скачивания. Проверено в авиарежиме. SHA-256: ba9151b0dc0205961e7f24fb6f4da874a40f300acf058f0e72bd04c713457adb'],check=True)
subprocess.run(['gh','release','upload',tag,'--repo',repo,'--clobber',str(output),str(root/'artifacts/apk/SHA256SUMS'),str(root/'config/contest-models.json')],check=True)
subprocess.run(['gh','release','edit',tag,'--repo',repo,'--draft=false','--latest'],check=True)
print('Published byte-identical signed APK:',h.hexdigest())
