const {test} = require('node:test');
const assert = require('node:assert/strict');
const core = require('../labeler-core.js');
const turn = (x = {}) => ({speaker:'speaker_2',start:0,end:1000,text:'Да.',reviewed:true,...x});
test('verified JSON has only the four required fields and stable speaker mapping', () => {
  const result=core.transcript([turn({start:2000,end:2500}),turn({speaker:'speaker_1'}),
    turn({start:3000,end:3500})],4000,true);
  assert.deepEqual(Object.keys(result),['transcript']);
  assert.deepEqual(Object.keys(result.transcript[0]),['speaker_id','start_ms','end_ms','text']);
  assert.deepEqual(result.transcript.map(g=>g.speaker_id),['speaker_1','speaker_2','speaker_2']);
});
test('an unreviewed turn can be a draft but cannot be a verified reference', () => {
  const a=[turn({reviewed:false})];
  assert.equal(core.ready(a,2000),false);
  assert.equal(core.transcript(a,2000).transcript.length,1);
  assert.throws(()=>core.transcript(a,2000,true));
});
test('unknown and overlap survive draft export and are never renamed to people', () => {
  const a=[turn({speaker:'unknown'}),turn({speaker:'overlap',start:1000,end:1500})];
  assert.deepEqual(core.transcript(a,2000).transcript.map(g=>g.speaker_id),['unknown','overlap']);
  assert.throws(()=>core.transcript(a,2000,true));
});
test('invalid intervals and content cannot bypass export', () => {
  for(const x of [{start:NaN},{start:Infinity},{start:-1},{start:.5},{end:0},{end:3000},{text:' '},{speaker:'speaker_0'}])
    assert.throws(()=>core.transcript([turn(x)],2000));
});
test('overlapping voices are allowed and original data is not mutated', () => {
  const a=[turn({start:500,end:1500}),turn({speaker:'speaker_1'})];
  assert.equal(core.transcript(a,2000,true).transcript.length,2);
  assert.equal(a[0].start,500);
});
test('review cannot be completed without loaded audio or any turns', () => {
  assert.equal(core.ready([],1000),false);
  assert.equal(core.ready([turn()],0),false);
});

test('verified references follow spoken-number and no-editorial-note convention', () => {
  for(const text of ['Мне 2 года','[смех] Да.']) {
    assert.equal(core.ready([turn({text})],2000),false);
    assert.equal(core.transcript([turn({text})],2000).transcript.length,1);
  }
});
