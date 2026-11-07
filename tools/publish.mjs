#!/usr/bin/env node
// Шифрует аудио (и черновики) паролем и кладёт в data/ рядом с index.html.
// Использование:
//   LABELER_PASSWORD='пароль' node tools/publish.mjs <папка_с_аудио> [папка_data]
// В папке с аудио: r001.wav (+ необязательно r001.json — черновик разметки).
// Формат файла: соль(16) + iv(12) + AES-256-GCM(шифртекст+тег), ключ PBKDF2-SHA256, 200000 итераций.
import { readdirSync, readFileSync, writeFileSync, mkdirSync, existsSync } from 'node:fs';
import { join, extname, basename, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { randomBytes, pbkdf2Sync, createCipheriv } from 'node:crypto';

const pw = process.env.LABELER_PASSWORD;
const src = process.argv[2];
const out = process.argv[3] || join(dirname(fileURLToPath(import.meta.url)), '..', 'data');
if (!pw || pw.length < 8 || !src) {
  console.error('Нужны LABELER_PASSWORD (минимум 8 символов, лучше длинная фраза) и папка с аудио.');
  process.exit(1);
}
const MIME = { '.wav': 'audio/wav', '.mp3': 'audio/mpeg', '.m4a': 'audio/mp4', '.ogg': 'audio/ogg', '.flac': 'audio/flac' };

function encrypt(buf) {
  const salt = randomBytes(16), iv = randomBytes(12);
  const key = pbkdf2Sync(pw, salt, 200000, 32, 'sha256');
  const c = createCipheriv('aes-256-gcm', key, iv);
  const ct = Buffer.concat([c.update(buf), c.final(), c.getAuthTag()]);
  return Buffer.concat([salt, iv, ct]);
}

mkdirSync(out, { recursive: true });
const idxPath = join(out, 'index.json');
const index = existsSync(idxPath) ? JSON.parse(readFileSync(idxPath, 'utf8')) : [];
for (const f of readdirSync(src).sort()) {
  const ext = extname(f).toLowerCase();
  if (!MIME[ext]) continue;
  const id = basename(f, ext);
  writeFileSync(join(out, id + '.audio.enc'), encrypt(readFileSync(join(src, f))));
  const draftPath = join(src, id + '.json');
  const draft = existsSync(draftPath);
  if (draft) writeFileSync(join(out, id + '.draft.enc'), encrypt(readFileSync(draftPath)));
  const entry = { id, ext: ext.slice(1), mime: MIME[ext], draft };
  const i = index.findIndex(x => x.id === id);
  if (i >= 0) index[i] = entry; else index.push(entry);
  console.log('ok', id, draft ? '(+черновик)' : '');
}
index.sort((a, b) => a.id.localeCompare(b.id));
writeFileSync(idxPath, JSON.stringify(index, null, 1));
console.log('Всего записей в списке:', index.length);
