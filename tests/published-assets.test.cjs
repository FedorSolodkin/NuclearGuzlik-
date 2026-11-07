const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const crypto=require('node:crypto');
const root=path.resolve(__dirname,'..');
test('editor script and all declared draft assets exist and obey the JSON contract',()=>{
  const html=fs.readFileSync(path.join(root,'index.html'),'utf8');
  for(const match of html.matchAll(/<script\s+src="([^"?]+)(?:\?[^" ]*)?"/g))
    assert.ok(fs.existsSync(path.join(root,match[1])),match[1]);
  const catalog=JSON.parse(fs.readFileSync(path.join(root,'data/index.json')));
  for(const item of catalog){
    if(!item.plain)continue;
    const audio=fs.readFileSync(path.join(root,'data',item.id+'.'+item.ext));
    if(item.audio_sha256)assert.equal(crypto.createHash('sha256').update(audio).digest('hex'),item.audio_sha256);
    for(const relative of new Set([item.published_draft,item.review_draft,item.id+'.draft.json'].filter(Boolean))){
      const file=path.resolve(root,'data',relative);
      assert.ok(file.startsWith(path.join(root,'data')+path.sep),'Draft path escapes data directory');
      const document=JSON.parse(fs.readFileSync(file,'utf8'));
      assert.deepEqual(Object.keys(document),['transcript']);
      assert.ok(Array.isArray(document.transcript));
      for(const turn of document.transcript){
        assert.deepEqual(Object.keys(turn).sort(),['end_ms','speaker_id','start_ms','text']);
        assert.equal(typeof turn.speaker_id,'string');
        assert.equal(typeof turn.text,'string');
        assert.ok(Number.isSafeInteger(turn.start_ms)&&Number.isSafeInteger(turn.end_ms));
        assert.ok(turn.start_ms>=0&&turn.end_ms>turn.start_ms);
      }
      if(relative===item.published_draft&&item.published_sha256)
        assert.equal(crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex'),item.published_sha256);
    }
  }
});
