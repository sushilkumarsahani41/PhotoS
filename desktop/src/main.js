'use strict';
// More file-system threads: listing, stat and thumbnail reads on a drive run in parallel instead of 4 at a time.
process.env.UV_THREADPOOL_SIZE ??= '16';
const { app, BrowserWindow, ipcMain, dialog, protocol, nativeImage, shell, powerSaveBlocker } = require('electron');
const fs = require('fs');
const fsp = fs.promises;
const path = require('path');
const crypto = require('crypto');
const { Readable } = require('stream');
const exifr = require('exifr');
const lib = require('./library');

protocol.registerSchemesAsPrivileged([
  { scheme: 'psmedia', privileges: { standard: true, secure: true, stream: true, supportFetchAPI: true, corsEnabled: true } },
  { scheme: 'psthumb', privileges: { standard: true, secure: true, supportFetchAPI: true } },
]);

const MIME = {
  '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.png': 'image/png', '.gif': 'image/gif', '.webp': 'image/webp', '.bmp': 'image/bmp', '.avif': 'image/avif',
  '.heic': 'image/heic', '.heif': 'image/heif', '.tif': 'image/tiff', '.tiff': 'image/tiff', '.dng': 'image/x-adobe-dng',
  '.mp4': 'video/mp4', '.m4v': 'video/mp4', '.mov': 'video/quicktime', '.3gp': 'video/3gpp', '.mkv': 'video/x-matroska', '.webm': 'video/webm', '.avi': 'video/x-msvideo',
};

let win;
let vault = null; // absolute path of <drive>/PhotoS
let settings = {};
let importAbort = null;
let driveIndex = null; // the drive's own catalog + thumbnail pack (docs/DRIVE-INDEX.md)
let indexRun = null;   // { abort, gate } while Build fast index runs
let importGate = null;
let importTouched = null; // folders written by the current import (for Mac metadata cleanup)
const userData = () => app.getPath('userData');
const settingsFile = () => path.join(userData(), 'settings.json');
const cache = () => (cache.instance ??= new lib.HashCache(path.join(userData(), 'hashes.json')));
const dates = new Map(); // "path|size|mtime" -> capture time (ms), persisted
const datesFile = () => path.join(userData(), 'dates.json');

async function loadState() {
  try { settings = JSON.parse(await fsp.readFile(settingsFile(), 'utf8')); } catch { settings = {}; }
  try { for (const [k, v] of Object.entries(JSON.parse(await fsp.readFile(datesFile(), 'utf8')))) dates.set(k, v); } catch { /* first run */ }
  await cache().load();
}
const saveSettings = () => fsp.writeFile(settingsFile(), JSON.stringify(settings)).catch(() => {});
const saveDates = () => fsp.writeFile(datesFile(), JSON.stringify(Object.fromEntries(dates))).catch(() => {});

/** Only files inside the chosen PhotoS folder may be served to the window. */
function inside(file) {
  if (!vault) return false;
  const rel = path.relative(vault, path.resolve(file));
  return rel !== '' && !rel.startsWith('..') && !path.isAbsolute(rel);
}
const encode = (p) => Buffer.from(p, 'utf8').toString('base64url');
const decode = (s) => Buffer.from(s, 'base64url').toString('utf8');

const dateKey = (item) => `${item.path}|${item.size}|${Math.round(item.mtime)}`;
async function captureDate(item) {
  const key = dateKey(item);
  if (dates.has(key)) return dates.get(key);
  let when = null;
  try {
    if (item.kind === 'image') {
      const tags = await exifr.parse(item.path, { pick: ['DateTimeOriginal', 'CreateDate', 'ModifyDate'], reviveValues: true });
      const d = tags?.DateTimeOriginal ?? tags?.CreateDate ?? tags?.ModifyDate;
      if (d instanceof Date && !isNaN(d)) when = d.getTime();
    } else if (['.mp4', '.mov', '.m4v', '.3gp'].includes(item.ext)) {
      when = await lib.videoDate(item.path);
    }
  } catch { /* unreadable metadata */ }
  when ??= lib.dateFromName(item.name) ?? item.mtime;
  dates.set(key, when);
  return when;
}

let scanGeneration = 0;
/**
 * Lists the drive and returns at once: dates come from the saved cache, else the file name, else the file time.
 * Capture dates that still need reading (EXIF / video headers) are then read in parallel in the background and
 * sent to the window in batches, which re-sorts once they arrive.
 */
async function scanVault(sender) {
  const generation = ++scanGeneration;
  if (!vault) return { vault: null, items: [] };
  const exists = await fsp.access(vault).then(() => true, () => false);
  if (!exists) return { vault, missing: true, items: [] };
  const items = await lib.scan(vault);
  driveIndex = await lib.DriveIndex.open(vault).catch(() => null);
  const unread = [];
  for (const it of items) {
    const known = dates.get(dateKey(it));
    const indexed = driveIndex?.entry(it.path);
    if (known != null) it.date = known;
    else if (indexed && indexed.size === it.size && indexed.date > 0) { it.date = indexed.date; dates.set(dateKey(it), it.date); }
    else { it.date = lib.dateFromName(it.name) ?? it.mtime; unread.push(it); }
  }
  const indexedCount = driveIndex ? items.filter((it) => { const e = driveIndex.entry(it.path); return e && e.size === it.size && driveIndex.hasThumb(e.hash); }).length : 0;
  items.sort((a, b) => b.date - a.date || a.name.localeCompare(b.name));
  if (unread.length) readDatesInBackground(unread, sender, generation);
  // Mac: quietly remove hidden "._" files macOS left in the PhotoS folder (phones would show them as broken photos).
  lib.removeAppleDouble([vault, path.dirname(vault), path.join(vault, '.index'), ...new Set(items.map((it) => path.dirname(it.path)))]).catch(() => {});
  return {
    vault, driveName: await lib.driveName(vault), pending: unread.length, indexed: indexedCount, items: items.map((it) => ({
      id: encode(it.path), name: it.name, album: it.album, kind: it.kind, size: it.size, date: it.date, ext: it.ext,
      web: it.kind === 'video' || lib.WEB_IMAGE.has(it.ext),
    })),
  };
}
async function readDatesInBackground(unread, sender, generation) {
  let batch = [], done = 0, last = Date.now();
  const flush = () => { if (batch.length && !sender.isDestroyed()) sender.send('dates', { updates: batch, done, total: unread.length }); batch = []; last = Date.now(); };
  await lib.pool(unread, 16, async (it) => {
    if (generation !== scanGeneration) return;
    const when = await captureDate(it);
    done++;
    // Share the date through the drive's catalog so other devices don't have to read it again.
    if (driveIndex && when) {
      const rel = driveIndex.rel(it.path), old = driveIndex.entry(it.path);
      if (rel) driveIndex.put({ path: rel, size: it.size, modified: it.mtime, date: when, hash: old?.size === it.size ? old.hash : '', mime: MIME[it.ext] ?? '' });
    }
    if (when !== it.date) batch.push([encode(it.path), when]);
    if (Date.now() - last > 600) flush();
  });
  if (generation !== scanGeneration) return;
  flush();
  if (!sender.isDestroyed()) sender.send('dates', { updates: [], done: unread.length, total: unread.length, finished: true });
  saveDates();
  driveIndex?.save().catch(() => {}); // a read-only drive simply keeps no catalog
}

// --- Thumbnails: the OS renders them (Explorer's thumbnails on Windows, so HEIC and videos work when Windows can show them). ---
const thumbDir = () => path.join(userData(), 'thumbs');
let thumbSlots = Math.max(4, Math.min(12, require('os').cpus().length));
const waiting = [];
async function slot(fn) {
  if (thumbSlots === 0) await new Promise((r) => waiting.push(r));
  thumbSlots--;
  try { return await fn(); } finally { thumbSlots++; waiting.shift()?.(); }
}
async function thumbFile(file, size) {
  const st = await fsp.stat(file);
  return path.join(thumbDir(), `${crypto.createHash('sha1').update(`${file}|${st.size}|${st.mtimeMs}|${size}`).digest('hex')}.jpg`);
}
async function thumbnail(file, size, cachedOnly = false) {
  const cached = await thumbFile(file, size);
  try { return await fsp.readFile(cached); } catch { /* not cached */ }
  // The drive's thumbnail pack: one small read instead of rendering the whole photo.
  if (size <= 512 && driveIndex) {
    const packed = await driveIndex.readThumb(file, (await fsp.stat(file)).size);
    if (packed) { fsp.mkdir(thumbDir(), { recursive: true }).then(() => fsp.writeFile(cached, packed)).catch(() => {}); return packed; }
  }
  if (cachedOnly) return null;
  const bytes = await slot(async () => {
    let img = null;
    try { img = await nativeImage.createThumbnailFromPath(file, { width: size, height: size }); } catch { /* unsupported */ }
    if (!img || img.isEmpty()) {
      const full = nativeImage.createFromPath(file); // JPEG/PNG fallback
      if (full.isEmpty()) return null;
      const s = full.getSize(); const scale = Math.min(1, size / Math.max(s.width, s.height));
      img = full.resize({ width: Math.round(s.width * scale), height: Math.round(s.height * scale), quality: 'good' });
    }
    return img.toJPEG(82);
  });
  if (!bytes) return null;
  await fsp.mkdir(thumbDir(), { recursive: true });
  fsp.writeFile(cached, bytes).catch(() => {});
  return bytes;
}

/** 512 px JPEG for the drive index, rendered by the OS (works for HEIC and videos when Windows can preview them). */
async function indexThumb(file) {
  try {
    let img = await nativeImage.createThumbnailFromPath(file, { width: 512, height: 512 }).catch(() => null);
    if (!img || img.isEmpty()) {
      const full = nativeImage.createFromPath(file);
      if (full.isEmpty()) return null;
      const s = full.getSize(), k = Math.min(1, 512 / Math.max(s.width, s.height));
      img = full.resize({ width: Math.round(s.width * k), height: Math.round(s.height * k), quality: 'good' });
    }
    return img.toJPEG(80);
  } catch { return null; }
}

function registerProtocols() {
  protocol.handle('psthumb', async (req) => {
    const url = new URL(req.url);
    const file = decode(url.pathname.slice(1));
    if (!inside(file)) return new Response('Forbidden', { status: 403 });
    const size = Math.min(2560, Math.max(64, Number(url.searchParams.get('s')) || 320));
    try {
      const bytes = await thumbnail(file, size, url.searchParams.has('cached'));
      return bytes ? new Response(bytes, { headers: { 'content-type': 'image/jpeg', 'cache-control': 'no-cache' } }) : new Response('No preview', { status: 404 });
    } catch { return new Response('Not found', { status: 404 }); }
  });
  // Full files, with byte ranges so videos can seek.
  protocol.handle('psmedia', async (req) => {
    const file = decode(new URL(req.url).pathname.slice(1));
    if (!inside(file)) return new Response('Forbidden', { status: 403 });
    let st; try { st = await fsp.stat(file); } catch { return new Response('Not found', { status: 404 }); }
    const type = MIME[path.extname(file).toLowerCase()] ?? 'application/octet-stream';
    const range = /bytes=(\d*)-(\d*)/.exec(req.headers.get('range') ?? '');
    if (range && st.size > 0) {
      let start = range[1] === '' ? Math.max(0, st.size - Number(range[2])) : Number(range[1]);
      let end = range[1] !== '' && range[2] !== '' ? Math.min(Number(range[2]), st.size - 1) : st.size - 1;
      if (start > end || start >= st.size) return new Response(null, { status: 416, headers: { 'content-range': `bytes */${st.size}` } });
      return new Response(Readable.toWeb(fs.createReadStream(file, { start, end })), { status: 206, headers: {
        'content-type': type, 'content-length': String(end - start + 1), 'content-range': `bytes ${start}-${end}/${st.size}`, 'accept-ranges': 'bytes', 'access-control-allow-origin': '*' } });
    }
    return new Response(Readable.toWeb(fs.createReadStream(file)), { headers: { 'content-type': type, 'content-length': String(st.size), 'accept-ranges': 'bytes', 'access-control-allow-origin': '*' } });
  });
}

function registerIpc() {
  ipcMain.handle('state', async () => ({ vault, autoIndex: settings.autoIndex !== false, driveName: vault ? await lib.driveName(vault) : null, platform: process.platform, version: app.getVersion(), parallel: settings.parallel ?? lib.DEFAULT_PARALLEL, maxParallel: lib.MAX_PARALLEL }));
  ipcMain.handle('set-parallel', (_e, n) => { settings.parallel = Math.max(1, Math.min(lib.MAX_PARALLEL, Math.round(Number(n)) || lib.DEFAULT_PARALLEL)); saveSettings(); return settings.parallel; });
  ipcMain.handle('choose-drive', async () => {
    const r = await dialog.showOpenDialog(win, { title: 'Choose your backup drive or its PhotoS folder', properties: ['openDirectory'] });
    if (r.canceled || !r.filePaths[0]) return null;
    vault = await lib.openVault(r.filePaths[0]);
    settings.vault = vault; saveSettings();
    return vault;
  });
  ipcMain.handle('scan', (e) => scanVault(e.sender));
  // Video frames grabbed by the window are cached like OS thumbnails.
  ipcMain.handle('store-thumb', async (_e, id, size, dataUrl) => {
    const f = decode(id);
    if (!inside(f) || typeof dataUrl !== 'string' || !dataUrl.startsWith('data:image/jpeg;base64,') || dataUrl.length > 4e6) return;
    await fsp.mkdir(thumbDir(), { recursive: true });
    await fsp.writeFile(await thumbFile(f, size), Buffer.from(dataUrl.slice(23), 'base64'));
  });
  ipcMain.handle('reveal', (_e, id) => { const f = decode(id); if (inside(f)) shell.showItemInFolder(f); });
  ipcMain.handle('open-external', (_e, id) => { const f = decode(id); if (inside(f)) return shell.openPath(f); });

  ipcMain.handle('pick-sources', async (_e, mode) => {
    const r = await dialog.showOpenDialog(win, mode === 'folder'
      ? { title: 'Choose folders with photos', properties: ['openDirectory', 'multiSelections'] }
      : { title: 'Choose photos and videos', properties: ['openFile', 'multiSelections'], filters: [{ name: 'Photos and videos', extensions: [...lib.IMAGE, ...lib.VIDEO].map((x) => x.slice(1)) }] });
    return r.canceled ? null : r.filePaths;
  });
  // Separate from picking so the window can show "Finding photos…" while big folders are read.
  ipcMain.handle('prepare-sources', async (_e, paths) => summarize(await lib.gather((paths ?? []).filter((p) => typeof p === 'string'))));
  ipcMain.handle('import', async (e, { token, album }) => {
    const files = pending.get(token);
    if (!files || !vault) throw new Error('Nothing to import');
    pending.delete(token);
    importAbort = new AbortController();
    importGate = new lib.Gate();
    // Keep the PC awake while copying; the display may still turn off.
    const awake = powerSaveBlocker.start('prevent-app-suspension');
    try {
      const chosen = album ? lib.albumFolder(album) : null;
      driveIndex ??= await lib.DriveIndex.open(vault).catch(() => null);
      const touched = new Set([vault, path.dirname(vault), path.join(vault, '.index')]);
      importTouched = touched;
      const onAdded = async ({ target, hash, source }) => {
        touched.add(path.dirname(target));
        if (!driveIndex) return;
        const rel = driveIndex.rel(target); if (!rel) return;
        const st = await fsp.stat(target);
        const date = await captureDate({ path: source.path, name: source.name, size: source.size, mtime: source.mtime, kind: source.kind, ext: path.extname(source.name).toLowerCase() });
        driveIndex.put({ path: rel, size: st.size, modified: st.mtimeMs, date, hash, mime: MIME[path.extname(target).toLowerCase()] ?? '' });
        // Rendered from the PC's copy, which is usually faster than reading it back from the drive.
        if (!driveIndex.hasThumb(hash)) { const jpeg = await indexThumb(source.path); if (jpeg) await driveIndex.addThumb(hash, jpeg); }
      };
      return await lib.importFiles({ vault, files, album: chosen, cache: cache(), workers: settings.parallel ?? lib.DEFAULT_PARALLEL, signal: importAbort.signal, gate: importGate, onAdded,
        onProgress: (p) => e.sender.send('import-progress', p) });
    } finally {
      importAbort = null; importGate = null; powerSaveBlocker.stop(awake);
      await driveIndex?.save().catch(() => {});
      // Mac: remove the hidden "._" files macOS wrote next to the new photos (phones would show them as broken photos).
      if (importTouched) await lib.removeAppleDouble(importTouched).catch(() => {});
      importTouched = null;
    }
  });
  ipcMain.handle('cancel-import', () => { importAbort?.abort(); importGate?.resume(); });
  // Build fast index: catalog + packed thumbnails for photos already on the drive, so it opens fast everywhere.
  ipcMain.handle('build-index', async (e, opts = {}) => {
    if (!vault || indexRun) return null;
    if (opts.rebuild) await fsp.rm(path.join(vault, '.index'), { recursive: true, force: true }).catch(() => {});
    indexRun = { abort: new AbortController(), gate: new lib.Gate() };
    const { abort, gate } = indexRun;
    const awake = powerSaveBlocker.start('prevent-app-suspension');
    try {
      driveIndex = await lib.DriveIndex.open(vault);
      const items = await lib.scan(vault);
      const todo = items.filter((it) => { const x = driveIndex.entry(it.path); return !x || x.size !== it.size || !x.hash || !driveIndex.hasThumb(x.hash); });
      let done = 0, failed = 0, last = 0;
      const send = (force) => { if (force || Date.now() - last > 300) { last = Date.now(); if (!e.sender.isDestroyed()) e.sender.send('index-progress', { done, total: todo.length, failed }); } };
      send(true);
      // In the background only two files at a time, so browsing stays smooth; when asked for, use the copy setting.
      await lib.pool(todo, opts.background ? 2 : Math.max(2, settings.parallel ?? lib.DEFAULT_PARALLEL), async (it) => {
        await gate.wait();
        if (abort.signal.aborted) return;
        try {
          const old = driveIndex.entry(it.path);
          const known = old && old.size === it.size && old.hash ? old.hash : cache().get({ path: it.path, size: it.size, mtime: it.mtime });
          const [hash, jpeg, date] = await Promise.all([known ?? lib.sha256(it.path), indexThumb(it.path), old?.size === it.size && old.date ? old.date : captureDate(it)]);
          if (!known) cache().set({ path: it.path, size: it.size, mtime: it.mtime }, hash);
          driveIndex.put({ path: driveIndex.rel(it.path), size: it.size, modified: it.mtime, date: date || 0, hash, mime: MIME[it.ext] ?? '' });
          if (jpeg) await driveIndex.addThumb(hash, jpeg);
        } catch { failed++; }
        done++;
        if (done % 200 === 0) await driveIndex.save().catch(() => {});
        send(done === todo.length);
      });
      await driveIndex.save();
      await cache().save().catch(() => {});
      saveDates();
      // Mac: also tidy hidden "._" files anywhere in the PhotoS folder (left by earlier copies from a Mac).
      const cleaned = await lib.removeAppleDouble([vault, path.dirname(vault), path.join(vault, '.index'), ...items.map((it) => path.dirname(it.path))]).catch(() => 0);
      return { done, total: todo.length, failed, stopped: abort.signal.aborted, all: items.length, cleaned };
    } catch (err) {
      return { error: err.code === 'EROFS' || err.code === 'EACCES' || err.code === 'EPERM' ? 'This drive is read-only, so the index cannot be written.' : err.message };
    } finally { indexRun = null; powerSaveBlocker.stop(awake); }
  });
  ipcMain.handle('set-auto-index', (_e, on) => { settings.autoIndex = !!on; saveSettings(); return settings.autoIndex; });

  // ---------- Duplicates ----------
  const describe = (g) => ({ hash: g.hash, size: g.size, keep: encode(g.keep), files: g.files.map((f) => ({ id: encode(f.path), name: f.name, album: f.album, kind: f.kind, date: f.date, folder: path.relative(vault, path.dirname(f.path)).split(path.sep).join('/') })) });
  ipcMain.handle('find-duplicates', async () => {
    if (!vault) return null;
    driveIndex = await lib.DriveIndex.open(vault).catch(() => null);
    const found = lib.findDuplicates(await lib.scan(vault), driveIndex);
    return { groups: found.groups.map(describe), unchecked: found.unchecked, extra: found.extra, bytes: found.bytes };
  });
  let dupeRun = null;
  ipcMain.handle('remove-duplicates', async (e, choices) => {
    if (!vault || dupeRun || !Array.isArray(choices)) return null;
    dupeRun = { abort: new AbortController(), gate: new lib.Gate() };
    const awake = powerSaveBlocker.start('prevent-app-suspension');
    try {
      driveIndex = await lib.DriveIndex.open(vault);
      // Recompute from the drive now, then apply the window's choices (which sets, and which copy to keep).
      const fresh = new Map(lib.findDuplicates(await lib.scan(vault), driveIndex).groups.map((g) => [g.hash, g]));
      const groups = [];
      for (const c of choices) {
        const g = fresh.get(c.hash); if (!g) continue;
        const keep = decode(String(c.keep ?? ''));
        if (g.files.some((f) => f.path === keep)) g.keep = keep;
        groups.push(g);
      }
      // Move to the Trash when the drive has one (Mac drives, most Windows fixed drives); otherwise delete.
      const discard = async (file) => { if (!inside(file)) throw new Error('Outside the backup folder'); try { await shell.trashItem(file); } catch { await fsp.rm(file); } };
      let last = 0;
      const r = await lib.removeDuplicates({ groups, index: driveIndex, discard, signal: dupeRun.abort.signal, gate: dupeRun.gate,
        onProgress: (p) => { if (Date.now() - last > 200 || p.done === p.total) { last = Date.now(); if (!e.sender.isDestroyed()) e.sender.send('dupe-progress', p); } } });
      await lib.removeAppleDouble([vault]).catch(() => {});
      return { ...r, failed: r.failed.map((f) => ({ name: path.basename(f.file), error: f.error })) };
    } finally { dupeRun = null; powerSaveBlocker.stop(awake); }
  });
  ipcMain.handle('pause-dupes', () => dupeRun?.gate.pause());
  ipcMain.handle('resume-dupes', () => dupeRun?.gate.resume());
  ipcMain.handle('cancel-dupes', () => { dupeRun?.abort.abort(); dupeRun?.gate.resume(); });
  ipcMain.handle('pause-index', () => indexRun?.gate.pause());
  ipcMain.handle('resume-index', () => indexRun?.gate.resume());
  ipcMain.handle('cancel-index', () => { indexRun?.abort.abort(); indexRun?.gate.resume(); });
  ipcMain.handle('pause-import', () => { importGate?.pause(); return !!importGate; });
  ipcMain.handle('resume-import', () => { importGate?.resume(); return !!importGate; });
}

// Picked files stay in the main process; the window only gets a summary and a token.
const pending = new Map();
function summarize(files) {
  if (!files.length) return { token: null, count: 0 };
  const token = crypto.randomUUID();
  pending.set(token, files);
  const albums = {};
  for (const f of files) { const a = lib.albumFolder(f.folder); albums[a] = (albums[a] ?? 0) + 1; }
  return { token, count: files.length, bytes: files.reduce((n, f) => n + f.size, 0), videos: files.filter((f) => f.kind === 'video').length, albums };
}

function createWindow() {
  win = new BrowserWindow({
    width: 1280, height: 860, minWidth: 760, minHeight: 520, backgroundColor: '#000000', title: 'PhotoS',
    icon: path.join(__dirname, 'icon.png'),
    // Mac: native look with the window buttons set into the sidebar.
    ...(process.platform === 'darwin' ? { titleBarStyle: 'hiddenInset', trafficLightPosition: { x: 18, y: 20 } } : {}),
    autoHideMenuBar: true,
    webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, sandbox: true, nodeIntegration: false },
  });
  win.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
  win.webContents.on('will-navigate', (e) => e.preventDefault());
  win.loadFile(path.join(__dirname, 'index.html'));
}

// Groups taskbar windows and pinned shortcuts under PhotoS (matches the installer's app ID).
if (process.platform === 'win32') app.setAppUserModelId('app.photos.desktop');

app.whenReady().then(async () => {
  await loadState();
  if (settings.vault) vault = await lib.openVault(settings.vault).catch(() => settings.vault);
  registerProtocols();
  registerIpc();
  createWindow();
  app.on('activate', () => { if (BrowserWindow.getAllWindows().length === 0) createWindow(); });
});
app.on('window-all-closed', () => { if (process.platform !== 'darwin') app.quit(); });
