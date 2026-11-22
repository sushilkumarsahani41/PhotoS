'use strict';
// Drive logic shared by the app and the tests. Plain Node, no Electron.
// The layout matches the Android app: <drive>/PhotoS/<album>/<original name>,
// with "<first 12 hex of SHA-256>_<name>" when a different file already has that name.

const fs = require('fs');
const fsp = fs.promises;
const path = require('path');
const crypto = require('crypto');

const IMAGE = new Set(['.jpg', '.jpeg', '.png', '.gif', '.webp', '.heic', '.heif', '.bmp', '.tif', '.tiff', '.dng', '.avif']);
const VIDEO = new Set(['.mp4', '.mov', '.m4v', '.3gp', '.mkv', '.webm', '.avi']);
/** Formats Chromium can show directly; others are shown through the OS thumbnail renderer. */
const WEB_IMAGE = new Set(['.jpg', '.jpeg', '.png', '.gif', '.webp', '.bmp', '.avif']);
const PART = /^\.(photos|dualvault)-part-/; // the old prefix is still ignored

const kindOf = (name) => {
  const ext = path.extname(name).toLowerCase();
  return IMAGE.has(ext) ? 'image' : VIDEO.has(ext) ? 'video' : null;
};

/** Same rules as Integrity.safeName on the phone. */
function safeName(name) {
  // eslint-disable-next-line no-control-regex
  const out = Array.from(String(name).replace(/[\\/:*?"<>|\u0000-\u001f\u007f]/g, '_')).slice(0, 140).join('');
  return out.trim() === '' ? 'Media' : out;
}

/** Same rules as Integrity.albumFolder: the name of the folder the file lives in. */
function albumFolder(folderName) {
  const last = String(folderName || '').trim();
  return last === '' || last.startsWith('.') ? 'Other' : Array.from(safeName(last)).slice(0, 80).join('');
}

const FOLDER = 'PhotoS';
const LEGACY = 'DualVault'; // drives backed up before the rename

/** Accepts the drive root, its PhotoS (or old DualVault) folder, or any folder inside it. Pure: no disk access. */
function resolveVault(chosen) {
  let dir = path.resolve(chosen);
  for (let up = dir; ; up = path.dirname(up)) {
    if (path.basename(up) === FOLDER || path.basename(up) === LEGACY) return up;
    if (path.dirname(up) === up) break;
  }
  return path.join(dir, FOLDER);
}

/** Resolves the backup folder and renames an old DualVault folder to PhotoS once, like the phone app. */
async function openVault(chosen) {
  const found = resolveVault(chosen);
  const root = path.basename(found) === LEGACY || path.basename(found) === FOLDER ? path.dirname(found) : found;
  const current = path.join(root, FOLDER);
  const legacy = path.join(root, LEGACY);
  if (!(await exists(current)) && (await fsp.stat(legacy).then((s) => s.isDirectory(), () => false))) {
    try { await fsp.rename(legacy, current); } catch { return legacy; } // read-only drive: keep using the old name
  }
  return current;
}

/**
 * The drive's own index: catalog + packed thumbnails, shared with the Android app. Format: docs/DRIVE-INDEX.md.
 * Optional everywhere: when missing or unwritable, callers list the drive and make thumbnails themselves.
 */
class DriveIndex {
  static PACK_LIMIT = 64 * 1024 * 1024;
  constructor(vault) {
    this.vault = vault; this.dir = path.join(vault, '.index');
    this.entries = new Map(); // relative path -> { path, size, modified, date, hash, mime }
    this.thumbs = new Map();  // hash -> [pack, offset, length]
    this.packs = new Map();   // pack number -> length
    this.dirty = false; this.chain = Promise.resolve();
  }
  static async open(vault) { const i = new DriveIndex(vault); await i.load(); return i; }
  async load() {
    let names = [];
    try { names = await fsp.readdir(this.dir); } catch { return; }
    for (const n of names) {
      const m = /^thumbs-(\d{3,})\.bin$/.exec(n);
      if (m) this.packs.set(Number(m[1]), (await fsp.stat(path.join(this.dir, n))).size);
    }
    const file = names.includes('catalog.json') ? 'catalog.json' : names.includes('catalog.json.new') ? 'catalog.json.new' : null;
    if (!file) return;
    try {
      const json = JSON.parse(await fsp.readFile(path.join(this.dir, file), 'utf8'));
      if (json.version !== 1) return;
      for (const r of json.items ?? []) {
        const [p, size, modified, date, hash, mime] = r;
        if (typeof p === 'string' && p && !p.startsWith('/') && !p.split('/').includes('..'))
          this.entries.set(p, { path: p, size: Number(size) || 0, modified: Number(modified) || 0, date: Number(date) || 0, hash: String(hash ?? ''), mime: String(mime ?? '') });
      }
      for (const [h, ref] of Object.entries(json.thumbs ?? {})) if (Array.isArray(ref) && ref.length === 3) this.thumbs.set(h, ref.map(Number));
    } catch { /* damaged catalog: treat as empty, it will be rewritten */ }
  }
  rel(file) {
    const r = path.relative(this.vault, file);
    return !r || r.startsWith('..') || path.isAbsolute(r) ? null : r.split(path.sep).join('/');
  }
  entry(file) { const r = this.rel(file); return r ? this.entries.get(r) : undefined; }
  put(e) {
    const old = this.entries.get(e.path);
    if (!old || old.size !== e.size || old.date !== e.date || old.hash !== e.hash || old.modified !== e.modified || old.mime !== e.mime) { this.entries.set(e.path, e); this.dirty = true; }
  }
  hasThumb(hash) { return !!hash && this.thumbs.has(hash); }
  remove(relPath) { if (this.entries.delete(relPath)) this.dirty = true; }
  /** Appends one JPEG to the current pack. Appends are queued so they never interleave. */
  addThumb(hash, jpeg) {
    if (!hash || this.thumbs.has(hash) || !jpeg?.length) return this.chain;
    this.chain = this.chain.then(async () => {
      if (this.thumbs.has(hash)) return;
      await fsp.mkdir(this.dir, { recursive: true });
      let pack = this.packs.size ? Math.max(...this.packs.keys()) : 0;
      let length = this.packs.get(pack) ?? 0;
      if (length > 0 && length + jpeg.length > DriveIndex.PACK_LIMIT) { pack++; length = 0; }
      await fsp.appendFile(path.join(this.dir, `thumbs-${String(pack).padStart(3, '0')}.bin`), jpeg);
      this.packs.set(pack, length + jpeg.length);
      this.thumbs.set(hash, [pack, length, jpeg.length]);
      this.dirty = true;
    }).catch(() => {});
    return this.chain;
  }
  /** Thumbnail bytes for a file on the drive, if indexed and the file still has the indexed size. */
  async readThumb(file, size) {
    const e = this.entry(file);
    if (!e || !e.hash || (size != null && e.size !== size)) return null;
    const ref = this.thumbs.get(e.hash); if (!ref) return null;
    const [pack, offset, length] = ref;
    try {
      const fd = await fsp.open(path.join(this.dir, `thumbs-${String(pack).padStart(3, '0')}.bin`), 'r');
      try {
        const buf = Buffer.alloc(length);
        const { bytesRead } = await fd.read(buf, 0, length, offset);
        return bytesRead === length && buf[0] === 0xff && buf[1] === 0xd8 ? buf : null;
      } finally { await fd.close(); }
    } catch { return null; }
  }
  async save() {
    await this.chain;
    if (!this.dirty) return;
    const items = [...this.entries.values()].sort((a, b) => (a.path < b.path ? -1 : 1)).map((e) => [e.path, e.size, Math.round(e.modified), Math.round(e.date), e.hash, e.mime]);
    const json = JSON.stringify({ version: 1, updated: Date.now(), items, thumbs: Object.fromEntries(this.thumbs) });
    await fsp.mkdir(this.dir, { recursive: true });
    const temp = path.join(this.dir, 'catalog.json.new');
    await fsp.writeFile(temp, json);
    await fsp.rename(temp, path.join(this.dir, 'catalog.json'));
    this.dirty = false;
  }
}

// ---------- Duplicates ----------
/**
 * Which copy to keep from identical files: a named album over "Other"/"Unsorted", then the earliest capture date,
 * then the shortest path (usually the original), then alphabetical — so the choice is stable.
 */
function chooseKeeper(files) {
  const generic = (a) => (a === 'Other' || a === 'Unsorted' ? 1 : 0);
  return [...files].sort((a, b) => generic(a.album) - generic(b.album) || (a.date || Infinity) - (b.date || Infinity) || a.path.length - b.path.length || (a.path < b.path ? -1 : 1))[0].path;
}

/**
 * Groups byte-identical files on the drive, using the fingerprints in the drive index. Only entries whose size still
 * matches the file are trusted; files without a fingerprint are reported as `unchecked`.
 */
function findDuplicates(items, index) {
  const byHash = new Map();
  let unchecked = 0;
  for (const it of items) {
    const e = index?.entry(it.path);
    if (!e || !e.hash || e.size !== it.size) { unchecked++; continue; }
    (byHash.get(e.hash) ?? byHash.set(e.hash, []).get(e.hash)).push({ path: it.path, name: it.name, album: it.album, size: it.size, date: it.date ?? e.date ?? 0, kind: it.kind });
  }
  const groups = [];
  for (const [hash, files] of byHash) {
    if (files.length < 2) continue;
    groups.push({ hash, size: files[0].size, files, keep: chooseKeeper(files) });
  }
  groups.sort((a, b) => b.size * (b.files.length - 1) - a.size * (a.files.length - 1));
  return { groups, unchecked, extra: groups.reduce((n, g) => n + g.files.length - 1, 0), bytes: groups.reduce((n, g) => n + g.size * (g.files.length - 1), 0) };
}

/**
 * Removes the extra copies in each group. Before each removal the kept file and the copy are re-read and must still
 * have the group's fingerprint, so a file that changed since indexing is never removed. `discard` deletes one file
 * (the app moves it to the system Trash when the drive has one).
 */
async function removeDuplicates({ groups, index, discard, onProgress = () => {}, signal, gate }) {
  const result = { removed: 0, freed: 0, failed: [] };
  const total = groups.reduce((n, g) => n + g.files.length - 1, 0);
  let done = 0;
  for (const g of groups) {
    if (signal?.aborted) break;
    await gate?.wait();
    let keeperOk = false;
    try { keeperOk = (await sha256(g.keep)) === g.hash; } catch { /* unreadable */ }
    for (const f of g.files) {
      if (f.path === g.keep) continue;
      if (signal?.aborted) break;
      await gate?.wait();
      try {
        if (!keeperOk) throw new Error('The copy to keep could not be verified, so nothing in this set was removed');
        if ((await sha256(f.path)) !== g.hash) throw new Error('This file changed since it was indexed');
        await discard(f.path);
        index?.remove(index.rel(f.path));
        result.removed++; result.freed += f.size;
      } catch (e) { result.failed.push({ file: f.path, error: e.message }); }
      onProgress({ done: ++done, total, file: f.name });
    }
  }
  await index?.save().catch(() => {});
  return result;
}

/**
 * macOS stores Mac-only file information on exFAT/FAT drives as hidden "._name" companion files next to each file
 * and folder. They are not photos (phones would show them as broken images) and Macs don't need them on a backup drive,
 * so they are removed from the given folders. Same effect as Apple's `dot_clean`. Only runs on macOS unless forced.
 */
async function removeAppleDouble(dirs, force = false) {
  if (process.platform !== 'darwin' && !force) return 0;
  let removed = 0;
  for (const dir of new Set(dirs)) {
    let names = [];
    try { names = await fsp.readdir(dir); } catch { continue; }
    for (const name of names) {
      if (!name.startsWith('._')) continue;
      try { await fsp.rm(path.join(dir, name), { force: true }); removed++; } catch { /* in use or read-only: leave it */ }
    }
  }
  return removed;
}

/** The name given to this drive in the PhotoS phone app (saved as PhotoS/.photos-drive.json), or null. */
async function driveName(vault) {
  try {
    const name = JSON.parse(await fsp.readFile(path.join(vault, '.photos-drive.json'), 'utf8')).name;
    return typeof name === 'string' && name.trim() ? Array.from(name.trim()).slice(0, 40).join('') : null;
  } catch { return null; }
}

/** Lists every photo and video under the PhotoS folder. Album = first folder below it. */
async function scan(vault) {
  const items = [];
  async function walk(dir, album) {
    let entries;
    try { entries = await fsp.readdir(dir, { withFileTypes: true }); } catch { return; }
    await Promise.all(entries.map(async (e) => {
      if (e.name.startsWith('.') || PART.test(e.name)) return;
      const full = path.join(dir, e.name);
      if (e.isDirectory()) return walk(full, album ?? e.name);
      const kind = e.isFile() && kindOf(e.name);
      if (!kind) return;
      try {
        const st = await fsp.stat(full);
        items.push({ path: full, name: e.name, album: album ?? 'Unsorted', kind, size: st.size, mtime: st.mtimeMs, ext: path.extname(e.name).toLowerCase() });
      } catch { /* vanished while scanning */ }
    }));
  }
  await walk(vault, null);
  return items;
}

/** Media files to import from files and folders picked on the PC (folders are searched recursively). */
async function gather(paths) {
  const out = [];
  const seen = new Set();
  async function add(p) {
    let st;
    try { st = await fsp.stat(p); } catch { return; }
    if (st.isDirectory()) {
      const entries = await fsp.readdir(p, { withFileTypes: true }).catch(() => []);
      for (const e of entries) if (!e.name.startsWith('.')) await add(path.join(p, e.name));
    } else if (st.isFile() && kindOf(p) && !seen.has(p)) {
      seen.add(p);
      out.push({ path: p, name: path.basename(p), folder: path.basename(path.dirname(p)), size: st.size, mtime: st.mtimeMs, kind: kindOf(p) });
    }
  }
  for (const p of paths) await add(p);
  return out;
}

// SHA-256 runs on a pool of worker threads, so several files are fingerprinted at once on separate CPU cores
// and the app's main thread (which serves the window) never stalls. The worker code is inline so it also works
// from inside the packaged app archive.
const { Worker } = require('worker_threads');
const WORKER_CODE = `
const { parentPort } = require('worker_threads');
const fs = require('fs'); const crypto = require('crypto');
parentPort.on('message', ({ id, file }) => {
  const h = crypto.createHash('sha256');
  fs.createReadStream(file, { highWaterMark: 1 << 20 })
    .on('data', (c) => h.update(c))
    .on('error', (e) => parentPort.postMessage({ id, error: e.message }))
    .on('end', () => parentPort.postMessage({ id, hash: h.digest('hex') }));
});`;
const HASH_WORKERS = Math.max(2, Math.min(8, require('os').cpus().length));
const hashPool = { workers: [], queue: [], jobs: new Map(), next: 0 };
function hashWorker() {
  if (hashPool.workers.length < HASH_WORKERS) {
    const w = new Worker(WORKER_CODE, { eval: true });
    w.busy = 0;
    w.on('message', ({ id, hash, error }) => {
      const job = hashPool.jobs.get(id); hashPool.jobs.delete(id); w.busy--;
      error ? job.reject(new Error(error)) : job.resolve(hash);
      const queued = hashPool.queue.shift(); if (queued) queued();
    });
    w.on('error', (e) => { for (const [id, job] of hashPool.jobs) if (job.worker === w) { hashPool.jobs.delete(id); job.reject(e); } hashPool.workers = hashPool.workers.filter((x) => x !== w); });
    w.unref(); // after the listeners, which would otherwise keep the process alive
    hashPool.workers.push(w);
    return w;
  }
  return hashPool.workers.find((w) => w.busy === 0) ?? null;
}
/** SHA-256 of a file, computed on a worker thread. */
function sha256(file) {
  return new Promise((resolve, reject) => {
    const run = () => {
      const w = hashWorker();
      if (!w) return hashPool.queue.push(run);
      const id = ++hashPool.next; w.busy++;
      hashPool.jobs.set(id, { resolve, reject, worker: w });
      w.postMessage({ id, file });
    };
    run();
  });
}

/** Hashes that survive restarts, keyed by path + size + modified time (same idea as the phone's hash cache). */
class HashCache {
  constructor(file) { this.file = file; this.map = new Map(); this.dirty = false; }
  async load() {
    if (!this.file) return this;
    try { this.map = new Map(Object.entries(JSON.parse(await fsp.readFile(this.file, 'utf8')))); } catch { /* first run */ }
    return this;
  }
  key(f) { return `${f.path}|${f.size}|${Math.round(f.mtime)}`; }
  get(f) { return this.map.get(this.key(f)); }
  set(f, hash) { this.map.set(this.key(f), hash); this.dirty = true; }
  async hash(f) { return this.get(f) ?? (await sha256(f.path).then((h) => (this.set(f, h), h))); }
  async save() {
    if (!this.file || !this.dirty) return;
    const tmp = `${this.file}.tmp`;
    await fsp.writeFile(tmp, JSON.stringify(Object.fromEntries(this.map)));
    await fsp.rename(tmp, this.file);
    this.dirty = false;
  }
}

async function pool(items, workers, fn) {
  let next = 0;
  await Promise.all(Array.from({ length: Math.min(workers, items.length) }, async () => {
    while (next < items.length) { const i = next++; await fn(items[i], i); }
  }));
}

/**
 * Copies files onto the drive, skipping any whose exact content is already there.
 * Each copy is written to a hidden part file, flushed, re-read and compared by SHA-256, then renamed.
 * @param album null keeps each file's own folder name as its album, otherwise everything goes into that album.
 */
const DEFAULT_PARALLEL = 4, MAX_PARALLEL = 10;

/** Pause switch for an import: while paused no new file starts; files already copying finish and are verified. */
class Gate {
  constructor() { this.paused = false; this.waiters = []; }
  pause() { this.paused = true; }
  resume() { this.paused = false; this.waiters.splice(0).forEach((r) => r()); }
  wait() { return this.paused ? new Promise((r) => this.waiters.push(r)) : null; }
}

async function importFiles({ vault, files, album = null, cache = new HashCache(null), workers = DEFAULT_PARALLEL, onProgress = () => {}, signal, gate = null, onAdded = null }) {
  workers = Math.max(1, Math.min(MAX_PARALLEL, Math.round(workers) || DEFAULT_PARALLEL));
  await fsp.mkdir(vault, { recursive: true });
  const existing = await scan(vault);
  // Only drive files with exactly the same size can be duplicates, so most files are never read twice.
  const bySize = new Map();
  for (const f of existing) (bySize.get(f.size) ?? bySize.set(f.size, []).get(f.size)).push(f);
  const known = new Set(); // hashes confirmed on the drive
  const claimed = new Map(); // hash -> promise, so two identical sources in one batch copy only once
  const result = { added: [], duplicates: [], failed: [] };
  let done = 0;
  const report = (file, status) => { done++; onProgress({ done, total: files.length, file: file.name, status }); };

  async function onDrive(size, hash) {
    if (known.has(hash)) return true;
    for (const f of bySize.get(size) ?? []) {
      if (signal?.aborted) throw new Error('Cancelled');
      const h = await cache.hash(f).catch(() => null);
      if (h) known.add(h);
      if (h === hash) return true;
    }
    return false;
  }

  await pool(files, workers, async (src) => {
    await gate?.wait();
    if (signal?.aborted) { result.failed.push({ file: src.path, error: 'Cancelled' }); return report(src, 'cancelled'); }
    try {
      const hash = await sha256(src.path);
      // Identical files in one batch: wait for the first copy, then count the rest as duplicates only if it succeeded.
      while (claimed.has(hash)) await claimed.get(hash);
      if (known.has(hash)) { result.duplicates.push(src.path); return report(src, 'duplicate'); }
      let release; claimed.set(hash, new Promise((r) => (release = r)));
      try {
        if (await onDrive(src.size, hash)) { result.duplicates.push(src.path); return report(src, 'duplicate'); }
        const target = await copyVerified(src, path.join(vault, album ?? albumFolder(src.folder)), hash, signal);
        known.add(hash);
        const st = await fsp.stat(target);
        const entry = { path: target, size: st.size, mtime: st.mtimeMs };
        cache.set(entry, hash);
        (bySize.get(st.size) ?? bySize.set(st.size, []).get(st.size)).push(entry);
        result.added.push(target);
        if (onAdded) await Promise.resolve(onAdded({ target, hash, source: src })).catch(() => {}); // e.g. add to the drive index
        report(src, 'added');
      } finally { claimed.delete(hash); release(); }
    } catch (e) {
      result.failed.push({ file: src.path, error: e.message });
      report(src, 'failed');
    }
  });
  await cache.save().catch(() => {});
  return result;
}

async function copyVerified(src, dir, expected, signal) {
  await fsp.mkdir(dir, { recursive: true });
  const part = path.join(dir, `.photos-part-${crypto.randomUUID()}.part`);
  let committed = false;
  try {
    if (signal?.aborted) throw new Error('Cancelled');
    // The operating system copies the bytes (fast, off the main thread); the copy is then flushed and re-read to verify.
    await fsp.copyFile(src.path, part, fs.constants.COPYFILE_EXCL);
    const fd = await fsp.open(part, 'r+'); try { await fd.sync(); } finally { await fd.close(); }
    if (signal?.aborted) throw new Error('Cancelled');
    if (await sha256(part) !== expected) throw new Error('Verification failed: the file changed while copying, or the drive did not store it completely');
    const name = safeName(src.name);
    let target = path.join(dir, name);
    if (await exists(target)) {
      if (await sha256(target) === expected) { await fsp.rm(part); committed = true; return target; }
      target = path.join(dir, `${expected.slice(0, 12)}_${name}`);
      if (await exists(target)) throw new Error(`A different file named ${path.basename(target)} is already there`);
    }
    await fsp.rename(part, target);
    committed = true;
    // Keep the original date so the file sorts correctly in other apps.
    await fsp.utimes(target, new Date(), new Date(src.mtime)).catch(() => {});
    return target;
  } finally {
    if (!committed) await fsp.rm(part, { force: true }).catch(() => {});
  }
}

const exists = (p) => fsp.access(p).then(() => true, () => false);

/** Capture date from the file name, e.g. IMG_20240512_183005.jpg or Screenshot_2024-05-12-18-30-05. */
function dateFromName(name) {
  const m = /(20\d\d|19\d\d)[-_.]?(\d\d)[-_.]?(\d\d)[-_ T.]?(\d\d)[-_.:]?(\d\d)[-_.:]?(\d\d)/.exec(name);
  if (!m) return null;
  const [y, mo, d, h, mi, s] = m.slice(1).map(Number);
  if (mo < 1 || mo > 12 || d < 1 || d > 31 || h > 23 || mi > 59 || s > 59) return null;
  return new Date(y, mo - 1, d, h, mi, s).getTime();
}

/** Creation time from an MP4/MOV header (moov → mvhd), without reading the video data. */
async function videoDate(file) {
  const fd = await fsp.open(file, 'r');
  try {
    const size = (await fd.stat()).size;
    const buf = Buffer.alloc(16);
    async function find(start, end, type) {
      for (let pos = start; pos + 8 <= end;) {
        const { bytesRead } = await fd.read(buf, 0, 16, pos);
        if (bytesRead < 8) return null;
        let len = buf.readUInt32BE(0); const t = buf.toString('latin1', 4, 8); let header = 8;
        if (len === 1) { len = Number(buf.readBigUInt64BE(8)); header = 16; } else if (len === 0) len = end - pos;
        if (len < header) return null;
        if (t === type) return { start: pos + header, end: pos + len };
        pos += len;
      }
      return null;
    }
    const moov = await find(0, size, 'moov');
    const mvhd = moov && await find(moov.start, moov.end, 'mvhd');
    if (!mvhd) return null;
    const b = Buffer.alloc(12); await fd.read(b, 0, 12, mvhd.start);
    const secs = b[0] === 1 ? Number(b.readBigUInt64BE(4)) : b.readUInt32BE(4);
    if (!secs) return null;
    const ms = (secs - 2082844800) * 1000; // seconds since 1904
    return ms > Date.UTC(1990, 0, 1) && ms < Date.now() + 86400000 ? ms : null;
  } catch { return null; } finally { await fd.close(); }
}

module.exports = { chooseKeeper, findDuplicates, removeDuplicates, removeAppleDouble, DriveIndex, driveName, Gate, DEFAULT_PARALLEL, MAX_PARALLEL, IMAGE, VIDEO, WEB_IMAGE, kindOf, safeName, albumFolder, resolveVault, openVault, scan, gather, sha256, HashCache, importFiles, dateFromName, videoDate, pool };
