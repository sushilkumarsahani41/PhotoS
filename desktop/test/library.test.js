'use strict';
const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const os = require('os');
const path = require('path');
const lib = require('../src/library');

const tmp = () => fs.mkdtempSync(path.join(os.tmpdir(), 'dv-'));
const write = (p, data) => { fs.mkdirSync(path.dirname(p), { recursive: true }); fs.writeFileSync(p, data); return p; };

test('names follow the phone app rules', () => {
  assert.strictEqual(lib.safeName('a/b:c*?.jpg'), 'a_b_c__.jpg');
  assert.strictEqual(lib.safeName('   '), 'Media');
  assert.strictEqual(lib.albumFolder('Camera'), 'Camera');
  assert.strictEqual(lib.albumFolder(''), 'Other');
  assert.strictEqual(lib.albumFolder('.thumbnails'), 'Other');
  assert.strictEqual(lib.albumFolder('x'.repeat(100)).length, 80);
});

test('drive root, PhotoS folder, or an album folder all resolve to PhotoS', () => {
  const root = path.resolve('/media/usb');
  assert.strictEqual(lib.resolveVault(root), path.join(root, 'PhotoS'));
  assert.strictEqual(lib.resolveVault(path.join(root, 'PhotoS')), path.join(root, 'PhotoS'));
  assert.strictEqual(lib.resolveVault(path.join(root, 'PhotoS', 'Camera')), path.join(root, 'PhotoS'));
});

test('scan lists media by album and ignores partial and hidden files', async () => {
  const v = path.join(tmp(), 'PhotoS');
  write(path.join(v, 'Camera', 'a.jpg'), 'a');
  write(path.join(v, 'Camera', '.photos-part-1.part'), 'x');
  write(path.join(v, 'Camera', 'notes.txt'), 'x');
  write(path.join(v, '.nomedia'), '');
  write(path.join(v, 'old.mp4'), 'v');
  write(path.join(v, 'WhatsApp Images', 'Sent', 'b.png'), 'b');
  const items = (await lib.scan(v)).sort((x, y) => x.name.localeCompare(y.name));
  assert.deepStrictEqual(items.map((i) => [i.name, i.album, i.kind]), [['a.jpg', 'Camera', 'image'], ['b.png', 'WhatsApp Images', 'image'], ['old.mp4', 'Unsorted', 'video']]);
});

test('import copies new files into folder-named albums and skips content already on the drive', async () => {
  const root = tmp(); const v = path.join(root, 'PhotoS');
  write(path.join(v, 'Camera', 'IMG_1.jpg'), 'same-bytes');
  const pc = path.join(root, 'pc');
  write(path.join(pc, 'Holiday', 'renamed copy.jpg'), 'same-bytes'); // already backed up under another name
  write(path.join(pc, 'Holiday', 'new.jpg'), 'new-bytes');
  write(path.join(pc, 'Holiday', 'twin.jpg'), 'twin'); write(path.join(pc, 'Other', 'twin2.jpg'), 'twin'); // identical pair in one batch
  const files = await lib.gather([pc]);
  const r = await lib.importFiles({ vault: v, files });
  assert.strictEqual(r.failed.length, 0);
  assert.strictEqual(r.added.length, 2);
  assert.strictEqual(r.duplicates.length, 2);
  assert.strictEqual(fs.readFileSync(path.join(v, 'Holiday', 'new.jpg'), 'utf8'), 'new-bytes');
  assert.ok(!fs.readdirSync(path.join(v, 'Holiday')).some((n) => n.startsWith('.photos-part')));
  // Running again adds nothing.
  const again = await lib.importFiles({ vault: v, files: await lib.gather([pc]) });
  assert.strictEqual(again.added.length, 0);
  assert.strictEqual(again.duplicates.length, 4);
});

test('a different file with the same name gets the hash prefix, like the phone', async () => {
  const root = tmp(); const v = path.join(root, 'PhotoS');
  write(path.join(v, 'Camera', 'IMG_1.jpg'), 'phone version');
  const src = write(path.join(root, 'pc', 'Camera', 'IMG_1.jpg'), 'pc version');
  const r = await lib.importFiles({ vault: v, files: await lib.gather([src]) });
  const hash = await lib.sha256(src);
  assert.deepStrictEqual(r.added, [path.join(v, 'Camera', `${hash.slice(0, 12)}_IMG_1.jpg`)]);
  assert.strictEqual(fs.readFileSync(path.join(v, 'Camera', 'IMG_1.jpg'), 'utf8'), 'phone version');
});

test('one chosen album and original modified time are kept', async () => {
  const root = tmp(); const v = path.join(root, 'PhotoS');
  const src = write(path.join(root, 'pc', 'Downloads', 'x.png'), 'x');
  const when = new Date('2021-06-01T10:00:00Z'); fs.utimesSync(src, when, when);
  const r = await lib.importFiles({ vault: v, files: await lib.gather([src]), album: 'Family' });
  assert.strictEqual(r.added[0], path.join(v, 'Family', 'x.png'));
  assert.strictEqual(Math.round(fs.statSync(r.added[0]).mtimeMs / 1000), when.getTime() / 1000);
});

test('hash cache is reused and persisted', async () => {
  const dir = tmp(); const f = write(path.join(dir, 'a.jpg'), 'abc'); const st = fs.statSync(f);
  const c = new lib.HashCache(path.join(dir, 'h.json'));
  const entry = { path: f, size: st.size, mtime: st.mtimeMs };
  const h = await c.hash(entry); await c.save();
  const c2 = await new lib.HashCache(path.join(dir, 'h.json')).load();
  assert.strictEqual(c2.get(entry), h);
});

test('dates from file names', () => {
  assert.strictEqual(lib.dateFromName('IMG_20240512_183005.jpg'), new Date(2024, 4, 12, 18, 30, 5).getTime());
  assert.strictEqual(lib.dateFromName('Screenshot_2024-05-12-18-30-05-123.png'), new Date(2024, 4, 12, 18, 30, 5).getTime());
  assert.strictEqual(lib.dateFromName('PXL_20231231_235959999.mp4'), new Date(2023, 11, 31, 23, 59, 59).getTime());
  assert.strictEqual(lib.dateFromName('holiday.jpg'), null);
  assert.strictEqual(lib.dateFromName('IMG_20241399_999999.jpg'), null);
});

test('video creation time from the mvhd header', async () => {
  const box = (type, body) => { const b = Buffer.alloc(8); b.writeUInt32BE(8 + body.length); b.write(type, 4, 'latin1'); return Buffer.concat([b, body]); };
  const when = Date.UTC(2022, 2, 3, 4, 5, 6);
  const mvhd = Buffer.alloc(20); mvhd.writeUInt32BE(0, 0); mvhd.writeUInt32BE((when / 1000) + 2082844800, 4);
  const file = write(path.join(tmp(), 'v.mp4'), Buffer.concat([box('ftyp', Buffer.from('isom0000')), box('mdat', Buffer.alloc(100)), box('moov', box('mvhd', mvhd))]));
  assert.strictEqual(await lib.videoDate(file), when);
});

test('an old DualVault folder is renamed to PhotoS once and old part files are ignored', async () => {
  const root = tmp();
  write(path.join(root, 'DualVault', 'Camera', 'a.jpg'), 'a');
  write(path.join(root, 'DualVault', 'Camera', '.dualvault-part-old.part'), 'x');
  const v = await lib.openVault(root);
  assert.strictEqual(v, path.join(root, 'PhotoS'));
  assert.ok(!fs.existsSync(path.join(root, 'DualVault')));
  assert.deepStrictEqual((await lib.scan(v)).map((i) => i.name), ['a.jpg']);
  assert.strictEqual(lib.resolveVault(path.join(root, 'DualVault', 'Camera')), path.join(root, 'DualVault'));
  assert.strictEqual(await lib.openVault(path.join(root, 'PhotoS', 'Camera')), path.join(root, 'PhotoS'));
});

test('pause holds new files until resumed', async () => {
  const root = tmp(); const v = path.join(root, 'PhotoS');
  for (let i = 0; i < 5; i++) write(path.join(root, 'pc', 'Trip', `p${i}.jpg`), `photo ${i}`);
  const gate = new lib.Gate(); gate.pause();
  const run = lib.importFiles({ vault: v, files: await lib.gather([path.join(root, 'pc')]), gate, workers: 2 });
  await new Promise((r) => setTimeout(r, 150));
  assert.ok(!fs.existsSync(path.join(v, 'Trip')), 'nothing copied while paused');
  gate.resume();
  const r = await run;
  assert.strictEqual(r.added.length, 5);
});

test('stop while paused ends without copying', async () => {
  const root = tmp(); const v = path.join(root, 'PhotoS');
  write(path.join(root, 'pc', 'Trip', 'a.jpg'), 'a');
  const gate = new lib.Gate(); gate.pause(); const stop = new AbortController();
  const run = lib.importFiles({ vault: v, files: await lib.gather([path.join(root, 'pc')]), gate, signal: stop.signal });
  stop.abort(); gate.resume();
  const r = await run;
  assert.strictEqual(r.added.length, 0);
  assert.strictEqual(r.failed[0].error, 'Cancelled');
});

test('parallel copies are capped between 1 and 10', async () => {
  const root = tmp(); const v = path.join(root, 'PhotoS');
  for (let i = 0; i < 12; i++) write(path.join(root, 'pc', 'Many', `m${i}.jpg`), `m ${i}`);
  const r = await lib.importFiles({ vault: v, files: await lib.gather([path.join(root, 'pc')]), workers: 50 });
  assert.strictEqual(r.added.length, 12);
  assert.strictEqual(lib.MAX_PARALLEL, 10);
});

test('drive name saved by the phone app is read', async () => {
  const v = path.join(tmp(), 'PhotoS');
  assert.strictEqual(await lib.driveName(v), null);
  write(path.join(v, '.photos-drive.json'), JSON.stringify({ name: '  Family SSD  ' }));
  assert.strictEqual(await lib.driveName(v), 'Family SSD');
  assert.deepStrictEqual((await lib.scan(v)), []);
});

test('drive index: thumbnails and catalog round-trip in the shared format', async () => {
  const v = path.join(tmp(), 'PhotoS');
  write(path.join(v, 'Camera', 'a.jpg'), 'aaaa');
  const idx = await lib.DriveIndex.open(v);
  const jpeg = Buffer.concat([Buffer.from([0xff, 0xd8]), Buffer.from('thumb-a')]);
  idx.put({ path: 'Camera/a.jpg', size: 4, modified: 5, date: 1690000000000, hash: 'h1', mime: 'image/jpeg' });
  await idx.addThumb('h1', jpeg);
  await idx.addThumb('h1', jpeg); // same content twice is stored once
  await idx.save();
  const json = JSON.parse(fs.readFileSync(path.join(v, '.index', 'catalog.json'), 'utf8'));
  assert.deepStrictEqual(json.items, [['Camera/a.jpg', 4, 5, 1690000000000, 'h1', 'image/jpeg']]);
  assert.deepStrictEqual(json.thumbs, { h1: [0, 0, jpeg.length] });
  assert.strictEqual(fs.statSync(path.join(v, '.index', 'thumbs-000.bin')).size, jpeg.length);
  const again = await lib.DriveIndex.open(v);
  assert.deepStrictEqual(await again.readThumb(path.join(v, 'Camera', 'a.jpg'), 4), jpeg);
  assert.strictEqual(await again.readThumb(path.join(v, 'Camera', 'a.jpg'), 5), null, 'size changed: not used');
  assert.deepStrictEqual(await lib.scan(v).then((items) => items.map((i) => i.name)), ['a.jpg'], '.index is not listed as photos');
});

test('drive index: rejects unsafe paths and bad thumbnail bytes', async () => {
  const v = path.join(tmp(), 'PhotoS');
  write(path.join(v, '.index', 'catalog.json'), JSON.stringify({ version: 1, items: [['../evil.jpg', 1, 0, 0, 'x', 'image/jpeg'], ['ok.jpg', 3, 0, 0, 'h', 'image/jpeg']], thumbs: { h: [0, 0, 3] } }));
  write(path.join(v, '.index', 'thumbs-000.bin'), 'abc');
  write(path.join(v, 'ok.jpg'), 'abc');
  const idx = await lib.DriveIndex.open(v);
  assert.deepStrictEqual([...idx.entries.keys()], ['ok.jpg']);
  assert.strictEqual(await idx.readThumb(path.join(v, 'ok.jpg'), 3), null, 'not a JPEG');
});

test('Mac "._" metadata files are removed and never listed as photos', async () => {
  const v = path.join(tmp(), 'PhotoS');
  write(path.join(v, 'Trip', 'a.jpg'), 'a');
  write(path.join(v, 'Trip', '._a.jpg'), 'mac metadata');
  write(path.join(v, '._Trip'), 'mac metadata');
  assert.deepStrictEqual((await lib.scan(v)).map((i) => i.name), ['a.jpg']);
  assert.strictEqual(await lib.removeAppleDouble([v, path.join(v, 'Trip')], true), 2);
  assert.deepStrictEqual(fs.readdirSync(path.join(v, 'Trip')), ['a.jpg']);
  assert.ok(fs.existsSync(path.join(v, 'Trip', 'a.jpg')));
});

test('duplicates: grouped by fingerprint, sensible copy kept, only verified copies removed', async () => {
  const v = path.join(tmp(), 'PhotoS');
  const a = write(path.join(v, 'Camera', 'IMG_1.jpg'), 'same photo');
  const b = write(path.join(v, 'Other', 'IMG_1 (1).jpg'), 'same photo');
  const c = write(path.join(v, 'WhatsApp Images', 'IMG-WA.jpg'), 'same photo');
  const d = write(path.join(v, 'Camera', 'unique.jpg'), 'unique');
  const changed = write(path.join(v, 'Camera', 'twin.jpg'), 'twin');
  const changedCopy = write(path.join(v, 'Other', 'twin copy.jpg'), 'twin');
  const idx = await lib.DriveIndex.open(v);
  const items = await lib.scan(v);
  for (const it of items) idx.put({ path: idx.rel(it.path), size: it.size, modified: 0, date: it.name === 'IMG-WA.jpg' ? 1 : 1000, hash: await lib.sha256(it.path), mime: 'image/jpeg' });
  fs.writeFileSync(changedCopy, 'TWIN'); // same size, different content: must not be removed
  const found = lib.findDuplicates(await lib.scan(v), idx);
  assert.strictEqual(found.groups.length, 2);
  const photo = found.groups.find((g) => g.files.length === 3);
  assert.strictEqual(photo.keep, c, 'earliest capture date in a named album is kept');
  assert.strictEqual(found.extra, 3);
  const removed = [];
  const r = await lib.removeDuplicates({ groups: found.groups, index: idx, discard: async (f) => { removed.push(f); fs.rmSync(f); } });
  assert.strictEqual(r.removed, 2);
  assert.deepStrictEqual(removed.sort(), [a, b].sort());
  assert.strictEqual(r.failed.length, 1, 'changed copy is reported, not removed');
  assert.ok(fs.existsSync(c) && fs.existsSync(d) && fs.existsSync(changed) && fs.existsSync(changedCopy));
  const again = await lib.DriveIndex.open(v);
  assert.ok(!again.entries.has('Camera/IMG_1.jpg'), 'removed copies leave the index');
});

test('date search: same rules as the phone app (day first)', () => {
  const D = require('../src/datequery');
  const today = new Date(2026, 10, 20);
  const p = (s) => D.parse(s, today);
  assert.deepStrictEqual(p('2023'), { year: 2023, month: null, day: null });
  assert.deepStrictEqual(p('05/2023'), { year: 2023, month: 5, day: null });
  assert.deepStrictEqual(p('12/05/2023'), { year: 2023, month: 5, day: 12 });
  assert.deepStrictEqual(p('12-05-23'), { year: 2023, month: 5, day: 12 });
  assert.deepStrictEqual(p('2023-05-12'), { year: 2023, month: 5, day: 12 });
  assert.deepStrictEqual(p('May'), { year: null, month: 5, day: null });
  assert.deepStrictEqual(p('12th May 2023'), { year: 2023, month: 5, day: 12 });
  assert.deepStrictEqual(p('May 12, 2023'), { year: 2023, month: 5, day: 12 });
  assert.deepStrictEqual(p('yesterday'), { year: 2026, month: 11, day: 19 });
  assert.deepStrictEqual(p('last month'), { year: 2026, month: 10, day: null });
  for (const bad of ['holiday', 'mayday', 'IMG_1234', '31/02/2023', '13/13/2023', '1234']) assert.strictEqual(p(bad), null, bad);
  assert.ok(D.matches(p('12 may'), new Date(2019, 4, 12).getTime()));
  assert.ok(!D.matches(p('12 may'), new Date(2019, 4, 13).getTime()));
  assert.strictEqual(D.label(p('12 may')), '12 May, any year');
});
