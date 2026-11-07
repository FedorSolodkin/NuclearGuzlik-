/* Shared contract checks for the editor and dependency-free Node tests. */
(function (root) {
  const person = /^speaker_[1-9]\d*$/;
  function issues(segments, duration) {
    const out = [];
    segments.forEach((g, i) => {
      if (!Number.isSafeInteger(g.start) || !Number.isSafeInteger(g.end) || g.start < 0 || g.end <= g.start)
        out.push({i, message: 'неверный интервал в миллисекундах'});
      if (Number.isFinite(duration) && duration > 0 && g.end > Math.ceil(duration))
        out.push({i, message: 'конец позже длины аудио'});
      if (typeof g.text !== 'string' || !g.text.trim()) out.push({i, message: 'пустой текст'});
      if (typeof g.speaker !== 'string' || !(person.test(g.speaker) || ['unknown', 'overlap'].includes(g.speaker)))
        out.push({i, message: 'неверный идентификатор говорящего'});
    });
    return out;
  }
  function ready(segments, duration) {
    return segments.length > 0 && Number.isFinite(duration) && duration > 0 &&
      issues(segments, duration).length === 0 && segments.every(g => g.reviewed === true && person.test(g.speaker) && !/[\d()[\]{}]/.test(g.text));
  }
  function transcript(segments, duration, verified = false) {
    if (!segments.length || issues(segments, duration).length) throw new Error('Исправьте ошибки текста, говорящих и времени');
    if (verified && !ready(segments, duration)) throw new Error('Проверьте реплики по аудио, определите говорящих и запишите числа словами');
    const sorted = segments.slice().sort((a, b) => a.start - b.start || a.end - b.end);
    const ids = new Map();
    return {transcript: sorted.map(g => {
      if (person.test(g.speaker) && !ids.has(g.speaker)) ids.set(g.speaker, 'speaker_' + (ids.size + 1));
      return {speaker_id: ids.get(g.speaker) || g.speaker, start_ms: g.start, end_ms: g.end,
        text: g.text.trim().replace(/\s+/g, ' ')};
    })};
  }
  const api = {issues, ready, transcript};
  root.LabelerCore = api;
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
})(globalThis);
