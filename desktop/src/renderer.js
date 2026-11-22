'use strict';
const $ = (id) => document.getElementById(id);
const el = (tag, cls, text) => { const e = document.createElement(tag); if (cls) e.className = cls; if (text != null) e.textContent = text; return e; };
const svg = (paths, extra = '') => `<svg viewBox="0 0 24 24" ${extra}>${paths}</svg>`;

const state = { vault: null, items: [], view: 'all', search: '', shown: [], loading: false };
const thumbUrl = (it, s = 320) => `psthumb://t/${it.id}?s=${s}`;
const mediaUrl = (it) => `psmedia://f/${it.id}`;

const dayFmt = new Intl.DateTimeFormat(undefined, { weekday: 'short', day: 'numeric', month: 'long', year: 'numeric' });
const fullFmt = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' });
function dayLabel(t) {
  const d = new Date(t); const today = new Date(); const y = new Date(); y.setDate(today.getDate() - 1);
  if (d.toDateString() === today.toDateString()) return 'Today';
  if (d.toDateString() === y.toDateString()) return 'Yesterday';
  return dayFmt.format(d);
}
const bytes = (n) => n >= 1 << 30 ? `${(n / (1 << 30)).toFixed(2)} GB` : n >= 1 << 20 ? `${(n / (1 << 20)).toFixed(1)} MB` : `${Math.max(1, Math.round(n / 1024))} KB`;
const plural = (n, one, many = one + 's') => `${n.toLocaleString()} ${n === 1 ? one : many}`;

// ---------- Drive and library ----------
async function init() {
  const s = await window.photos.state();
  state.vault = s.vault; state.driveName = s.driveName; state.autoIndex = s.autoIndex;
  $('fastAuto').checked = s.autoIndex;
  const sel = $('iParallel');
  for (let n = 1; n <= s.maxParallel; n++) { const o = el('option', null, String(n)); o.value = n; sel.append(o); }
  sel.value = s.parallel;
  sel.onchange = () => window.photos.setParallel(Number(sel.value));
  document.body.dataset.platform = s.platform;
  $('back').title = s.platform === 'darwin' ? 'Back (⌘[)' : 'Back (Alt+Left)';
  try { const z = localStorage.getItem('zoom'); if (z) { $('zoom').value = z; setZoom(z); } } catch { /* storage unavailable */ }
  await load();
  updateBack();
}

async function load() {
  showDrive();
  if (!state.vault) return renderEmpty('drive');
  // First scan: skeleton grid. Rescans keep the current grid and show a thin progress bar.
  const first = !state.items.length;
  state.loading = true; setBusy(true);
  if (first) renderEmpty('loading');
  try {
    const r = await window.photos.scan();
    state.items = r.items; state.missing = !!r.missing; state.driveName = r.driveName ?? null; state.indexed = r.indexed ?? 0; showDrive(); showFast();
  } catch (e) { state.items = []; state.error = e.message; }
  state.loading = false; setBusy(false);
  state.loadedAt = Date.now();
  renderNav(); render(true);
  maybeAutoIndex();
}
function setBusy(on) { $('topProgress').hidden = !on || !state.items.length; $('refresh').classList.toggle('spinning', on); $('refresh').disabled = on; }

function showDrive() {
  const v = state.vault;
  $('add').disabled = !v;
  if (!v) { $('driveName').textContent = 'No drive'; $('drivePath').textContent = 'Choose your backup drive'; return; }
  const parts = v.split(/[\\/]/).filter(Boolean);
  const drive = parts.length >= 2 ? parts[parts.length - 2] : parts[0] ?? v;
  // The name given in the phone app wins; otherwise the drive letter or folder name.
  $('driveName').textContent = state.driveName || (/^[A-Za-z]:$/.test(drive) ? `Drive ${drive}` : drive);
  $('drivePath').textContent = v;
  $('drive').title = `${v}\nClick to choose a different drive`;
}

$('drive').onclick = async () => { const v = await window.photos.chooseDrive(); if (v) { state.vault = v; state.driveName = null; state.items = []; backStack.length = 0; forwardStack.length = 0; scrolls.clear(); state.view = 'all'; await load(); } };
$('refresh').onclick = () => load();
// Capture dates arrive in batches after the grid is already showing; re-sort once per batch, keeping the scroll position.
let resortTimer = null;
window.photos.onDates((p) => {
  if (p.updates.length) {
    const byId = new Map(state.items.map((it) => [it.id, it]));
    for (const [id, when] of p.updates) { const it = byId.get(id); if (it) it.date = when; }
    clearTimeout(resortTimer);
    resortTimer = setTimeout(() => { state.items.sort((a, b) => b.date - a.date || a.name.localeCompare(b.name)); if ($('viewer').hidden) render(true); }, 300);
  }
  $('subtitle').dataset.dates = p.finished ? '' : `Reading dates ${p.done.toLocaleString()} of ${p.total.toLocaleString()}`;
  showSubtitle();
});
function showSubtitle() {
  const base = $('subtitle').dataset.base ?? '';
  const extra = $('subtitle').dataset.dates;
  $('subtitle').textContent = extra ? `${base} · ${extra}` : base;
}

function renderNav() {
  const counts = {}; let videos = 0;
  for (const it of state.items) { counts[it.album] = (counts[it.album] ?? 0) + 1; if (it.kind === 'video') videos++; }
  $('countAll').textContent = state.items.length ? state.items.length.toLocaleString() : '';
  $('countVideos').textContent = videos ? videos.toLocaleString() : '';
  const box = $('albums'); box.replaceChildren();
  for (const name of Object.keys(counts).sort((a, b) => a.localeCompare(b, undefined, { sensitivity: 'base' }))) {
    const b = el('button', 'nav'); b.dataset.view = `album:${name}`;
    b.append(el('span', null, name), el('em', null, counts[name].toLocaleString()));
    b.title = name;
    box.append(b);
  }
  const list = $('albumList'); list.replaceChildren(...Object.keys(counts).sort().map((n) => { const o = el('option'); o.value = n; return o; }));
  markNav();
}
function markNav() { for (const b of document.querySelectorAll('.nav')) b.classList.toggle('active', b.dataset.view === state.view); }
$('nav').addEventListener('click', (e) => { const b = e.target.closest('.nav'); if (b) navigate(b.dataset.view); });
$('search').addEventListener('input', (e) => { state.search = e.target.value.trim().toLowerCase(); render(); });
$('search').addEventListener('keydown', (e) => { if (e.key === 'Escape' && e.target.value) { e.stopPropagation(); e.target.value = ''; state.search = ''; render(); } });

// ---------- Navigation: backStack like a browser, and each view remembers where you were ----------
const backStack = [], forwardStack = [];
const scrolls = new Map();
function navigate(view, { record = true } = {}) {
  if (view === state.view) { $('content').scrollTo({ top: 0, behavior: 'smooth' }); return; }
  scrolls.set(state.view, $('content').scrollTop);
  if (record) { backStack.push(state.view); forwardStack.length = 0; }
  state.view = view;
  if (state.search) { state.search = ''; $('search').value = ''; }
  markNav(); render(); restoreScroll(scrolls.get(view) ?? 0);
  updateBack();
}
// Day sections lay out lazily, so the saved position is applied once the page is tall enough.
function restoreScroll(top) {
  const c = $('content'); c.scrollTop = top;
  let tries = 0;
  const again = () => { if (Math.abs(c.scrollTop - top) > 2 && tries++ < 10) { c.scrollTop = top; requestAnimationFrame(again); } };
  requestAnimationFrame(again);
}
function goBack() { if (!backStack.length) return false; forwardStack.push(state.view); navigate(backStack.pop(), { record: false }); return true; }
function goForward() { if (!forwardStack.length) return; backStack.push(state.view); navigate(forwardStack.pop(), { record: false }); }
function updateBack() { $('back').hidden = !backStack.length; }
$('back').onclick = goBack;
// Mouse side buttons (3 = back, 4 = forward).
window.addEventListener('mouseup', (e) => {
  if (e.button !== 3 && e.button !== 4) return;
  e.preventDefault();
  if (!$('viewer').hidden) { if (e.button === 3) closeViewer(); return; }
  if ($('importDialog').open) return;
  e.button === 3 ? goBack() : goForward();
});
document.addEventListener('keydown', (e) => {
  if (!$('viewer').hidden || $('importDialog').open || $('busyDialog').open) return;
  const typing = /INPUT|TEXTAREA/.test(document.activeElement?.tagName ?? '');
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'f') { e.preventDefault(); $('search').focus(); $('search').select(); }
  // Back/forward: Alt+←/→ (Windows) and ⌘[ ⌘] or ⌘←/→ (Mac).
  else if ((e.altKey && e.key === 'ArrowLeft') || (e.metaKey && (e.key === '[' || e.key === 'ArrowLeft'))) { e.preventDefault(); goBack(); }
  else if ((e.altKey && e.key === 'ArrowRight') || (e.metaKey && (e.key === ']' || e.key === 'ArrowRight'))) { e.preventDefault(); goForward(); }
  else if (e.key === 'Backspace' && !typing) { e.preventDefault(); goBack(); }
  else if ((e.key === 'F5' || ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'r')) && !state.loading) { e.preventDefault(); load(); }
});
function setZoom(v) { document.documentElement.style.setProperty('--cell', `${v}px`); }
$('zoom').addEventListener('input', (e) => { setZoom(e.target.value); try { localStorage.setItem('zoom', e.target.value); } catch { /* ignore */ } });

// ---------- Grid ----------
function filtered() {
  const v = state.view, q = state.search;
  // Name or date: "2023" finds photos taken in 2023 as well as names containing 2023.
  const date = q ? DateQuery.parse(q) : null;
  return state.items.filter((it) =>
    (v === 'all' || (v === 'videos' && it.kind === 'video') || (v.startsWith('album:') && it.album === v.slice(6))) &&
    (!q || it.name.toLowerCase().includes(q) || it.album.toLowerCase().includes(q) || (date && DateQuery.matches(date, it.date))));
}

function render(keepScroll = false) {
  if (state.loading && !state.items.length) return;
  const scroll = keepScroll ? $('content').scrollTop : null;
  if (!state.vault) return renderEmpty('drive');
  if (state.missing) return renderEmpty('missing');
  if (state.view === 'duplicates') return renderDuplicates(keepScroll);
  const items = state.shown = filtered();
  const title = state.view === 'all' ? 'All photos' : state.view === 'videos' ? 'Videos' : state.view.slice(6);
  $('title').textContent = title;
  const date = state.search ? DateQuery.parse(state.search) : null;
  $('subtitle').dataset.base = plural(items.length, 'item') + (date ? ` · from ${DateQuery.label(date)}` : ''); showSubtitle();
  if (!state.items.length) return renderEmpty('nothing');
  if (!items.length) return renderEmpty('nomatch');

  const content = $('content'); content.replaceChildren();
  const frag = document.createDocumentFragment();
  let grid = null, day = null, section = null, count = 0, head = null;
  items.forEach((it, index) => {
    const key = new Date(it.date).toDateString();
    if (key !== day) {
      if (head) head.querySelector('small').textContent = plural(count, 'item');
      day = key; count = 0;
      section = el('section', 'day'); head = el('h2', null, dayLabel(it.date)); head.append(el('small'));
      grid = el('div', 'grid'); section.append(head, grid); frag.append(section);
    }
    count++;
    grid.append(cell(it, index));
  });
  if (head) head.querySelector('small').textContent = plural(count, 'item');
  content.append(frag);
  if (scroll != null) content.scrollTop = scroll;
}

function cell(it, index) {
  const b = el('button', 'cell'); b.dataset.index = index; b.title = `${it.name}\n${fullFmt.format(it.date)} · ${bytes(it.size)}`;
  const img = el('img'); img.loading = 'lazy'; img.decoding = 'async'; img.alt = it.name; img.draggable = false;
  img.onload = () => { img.classList.add('ready'); b.classList.add('loaded'); };
  img.onerror = () => { img.remove(); b.classList.add('loaded'); b.prepend(el('div', 'fallback', it.ext.slice(1))); };
  if (it.kind === 'video') frames.observe(img), (img.dataset.id = it.id);
  else img.src = thumbUrl(it);
  b.append(img);
  if (it.kind === 'video') { const badge = el('span', 'badge'); badge.innerHTML = svg('<path d="M8 5.5v13l11-6.5z"/>'); badge.append('Video'); b.append(badge); }
  return b;
}
// Video thumbnails: a cached frame, else a frame grabbed here (2 at a time, only when visible), else the system thumbnail.
const frameQueue = []; let grabbing = 0;
const frames = new IntersectionObserver((entries) => {
  for (const e of entries) if (e.isIntersecting) { frames.unobserve(e.target); videoThumb(e.target); }
}, { rootMargin: '400px' });
async function videoThumb(img) {
  const it = { id: img.dataset.id };
  const cached = await fetch(`${thumbUrl(it)}&cached`).then((r) => (r.ok ? r.blob() : null)).catch(() => null);
  if (cached) { img.src = URL.createObjectURL(cached); return; }
  frameQueue.push(img); pump();
}
function pump() {
  while (grabbing < 2 && frameQueue.length) {
    const img = frameQueue.shift(); grabbing++;
    grabFrame(img.dataset.id).then((url) => {
      if (url) { img.src = url; window.photos.storeThumb(img.dataset.id, 320, url); } else img.src = thumbUrl({ id: img.dataset.id });
    }).finally(() => { grabbing--; pump(); });
  }
}
function grabFrame(id) {
  return new Promise((resolve) => {
    const v = document.createElement('video');
    v.crossOrigin = 'anonymous'; v.muted = true; v.preload = 'metadata'; v.playsInline = true;
    let done = false;
    const finish = (url) => { if (done) return; done = true; clearTimeout(timer); v.removeAttribute('src'); v.load(); resolve(url); };
    const timer = setTimeout(() => finish(null), 8000);
    v.onloadedmetadata = () => { v.currentTime = Math.min(1, (v.duration || 0) / 3); };
    v.onseeked = () => {
      try {
        const scale = 320 / Math.max(v.videoWidth, v.videoHeight, 1);
        const c = document.createElement('canvas'); c.width = Math.max(1, Math.round(v.videoWidth * scale)); c.height = Math.max(1, Math.round(v.videoHeight * scale));
        c.getContext('2d').drawImage(v, 0, 0, c.width, c.height);
        finish(v.videoWidth ? c.toDataURL('image/jpeg', 0.82) : null);
      } catch { finish(null); }
    };
    v.onerror = () => finish(null);
    v.src = mediaUrl({ id });
  });
}
$('content').addEventListener('click', (e) => { const c = e.target.closest('.cell'); if (c) openViewer(Number(c.dataset.index)); });

function renderEmpty(kind) {
  const content = $('content'); content.replaceChildren();
  const box = el('div', 'empty'); const inner = el('div'); box.append(inner);
  const icon = (p) => { const s = el('div'); s.innerHTML = svg(p); inner.append(s.firstChild); };
  const action = (label, fn) => { const b = el('button', 'primary', label); b.onclick = fn; inner.append(b); };
  if (kind === 'loading') {
    $('title').textContent = 'All photos'; $('subtitle').textContent = '';
    const sk = el('div', 'skeleton');
    const note = el('div', 'scan-note'); note.id = 'scanNote'; note.append(el('div', 'spinner'), el('span', null, 'Reading the backup drive…'));
    sk.append(note);
    for (let d = 0; d < 3; d++) {
      sk.append(el('div', 'skeleton-title shimmer'));
      const g = el('div', 'grid'); for (let i = 0; i < 12; i++) g.append(el('div', 'cell')); sk.append(g);
    }
    content.append(sk); return;
  }
  else if (kind === 'drive') {
    icon('<rect x="3" y="6" width="18" height="12" rx="2.5"/><path d="M7 14h.01M10.5 14H17"/>');
    inner.append(el('h2', null, 'Connect your backup drive'), el('p', null, 'Plug in the USB drive you back up to from your phone, then choose the drive (or its PhotoS folder).'));
    action('Choose backup drive', () => $('drive').click());
    $('title').textContent = 'PhotoS'; $('subtitle').textContent = '';
  } else if (kind === 'missing') {
    icon('<rect x="3" y="6" width="18" height="12" rx="2.5"/><path d="M4 4l16 16"/>');
    inner.append(el('h2', null, 'Backup drive not found'), el('p', null, `${state.vault} is not available. Plug the drive in and try again, or choose another drive.`));
    action('Try again', load);
    const other = el('button', 'ghost', 'Choose another drive'); other.onclick = () => $('drive').click(); inner.append(other);
  } else if (kind === 'nothing') {
    icon('<rect x="3" y="5" width="18" height="14" rx="2.5"/><circle cx="9" cy="10" r="1.6"/><path d="M21 16l-5-5-8 8"/>');
    inner.append(el('h2', null, 'No photos on this drive yet'), el('p', null, 'Back up from the phone app, or add photos from this computer.'));
    action('Add photos', () => $('add').click());
  } else if (kind === 'nomatch') {
    inner.append(el('h2', null, 'Nothing matches'), el('p', null, 'Try a different name or album.'));
  }
  content.append(box);
}

// ---------- Viewer ----------
let current = -1;
function openViewer(index) { current = index; $('viewer').hidden = false; show(); }
function closeViewer() { $('vSpinner').hidden = true; $('viewer').hidden = true; $('stage').replaceChildren(); $('infoPanel').hidden = true; $('viewer').classList.remove('with-info'); current = -1; }
function show() {
  const it = state.shown[current]; if (!it) return closeViewer();
  $('vName').textContent = it.name;
  $('vMeta').textContent = `${fullFmt.format(it.date)}  ·  ${current + 1} of ${state.shown.length}`;
  $('vPrev').disabled = current === 0; $('vNext').disabled = current === state.shown.length - 1;
  const stage = $('stage'); stage.replaceChildren();
  const spin = $('vSpinner'); spin.hidden = false;
  const ready = () => { if (state.shown[current] === it) spin.hidden = true; };
  if (it.kind === 'video') {
    const v = el('video'); v.controls = true; v.autoplay = true; v.src = mediaUrl(it);
    v.onloadeddata = ready; v.onwaiting = () => (spin.hidden = false); v.onplaying = ready;
    v.onerror = () => { ready(); stage.replaceChildren(el('p', 'note', 'This video format cannot play here. Use “Open in default app”.')); };
    stage.append(v);
  } else {
    const img = el('img'); img.alt = it.name; img.draggable = false;
    // Formats the window cannot decode (HEIC, TIFF, RAW) are shown through the system's large thumbnail.
    img.src = it.web ? mediaUrl(it) : thumbUrl(it, 2560);
    img.onload = ready;
    img.onerror = () => { if (it.web && !img.dataset.retry) { img.dataset.retry = 1; img.src = thumbUrl(it, 2560); } else { ready(); stage.replaceChildren(el('p', 'note', 'No preview available. Use “Open in default app”.')); } };
    img.onclick = () => img.classList.toggle('zoomed') ? (img.style.transform = 'scale(2)') : (img.style.transform = '');
    stage.append(img);
  }
  if (!$('infoPanel').hidden) fillInfo();
  // Warm up the neighbours so arrowing through is instant.
  for (const n of [state.shown[current + 1], state.shown[current - 1]]) if (n && n.kind === 'image') new Image().src = n.web ? mediaUrl(n) : thumbUrl(n, 2560);
}
function fillInfo() {
  const it = state.shown[current]; const p = $('infoPanel'); p.replaceChildren();
  const h = el('h3', null, it.name); h.style.margin = '0 0 16px'; h.style.wordBreak = 'break-word';
  const dl = el('dl');
  for (const [k, v] of [['Taken', fullFmt.format(it.date)], ['Album', it.album], ['Type', it.ext.slice(1).toUpperCase() + (it.kind === 'video' ? ' video' : ' photo')], ['Size', bytes(it.size)]]) dl.append(el('dt', null, k), el('dd', null, v));
  p.append(h, dl);
}
const step = (d) => { const n = current + d; if (n >= 0 && n < state.shown.length) { current = n; show(); } };
$('vPrev').onclick = () => step(-1); $('vNext').onclick = () => step(1); $('vClose').onclick = closeViewer;
$('vInfo').onclick = () => { const p = $('infoPanel'); p.hidden = !p.hidden; $('viewer').classList.toggle('with-info', !p.hidden); if (!p.hidden) fillInfo(); };
$('vReveal').onclick = () => window.photos.reveal(state.shown[current].id);
$('vOpen').onclick = () => window.photos.openExternal(state.shown[current].id);
document.addEventListener('keydown', (e) => {
  if ($('viewer').hidden || $('importDialog').open) return;
  if (e.key === 'ArrowRight') step(1); else if (e.key === 'ArrowLeft') step(-1);
  else if (e.key === 'Escape') closeViewer(); else if (e.key.toLowerCase() === 'i') $('vInfo').click();
  else if (e.key === 'Home') { current = 0; show(); } else if (e.key === 'End') { current = state.shown.length - 1; show(); }
});

// ---------- Add photos ----------
let plan = null, importing = false;
$('add').onclick = async () => {
  // One button: Windows can't offer files and folders in the same picker, so ask which (same on every system for consistency).
  const menu = await chooseSource(); if (!menu) return;
  const paths = await window.photos.pickSources(menu); if (paths) prepareFrom(paths);
};
async function prepareFrom(paths) {
  if (!paths.length) return;
  $('busyText').textContent = 'Finding photos…';
  const timer = setTimeout(() => $('busyDialog').showModal(), 250); // only for folders that take a moment
  try { prepare(await window.photos.prepareSources(paths)); }
  catch (e) { alert(`Could not read those files: ${e.message}`); }
  finally { clearTimeout(timer); if ($('busyDialog').open) $('busyDialog').close(); }
}
$('busyDialog').addEventListener('cancel', (e) => e.preventDefault());
function chooseSource() {
  return new Promise((resolve) => {
    const d = el('dialog');
    d.innerHTML = '<h2>Add photos</h2><p class="muted">Choose what to add. You can also drag photos or folders onto this window.</p>';
    const row = el('div', 'actions'); row.style.justifyContent = 'stretch';
    const pick = (label, value, cls) => { const b = el('button', cls, label); b.style.flex = 1; b.onclick = () => { d.close(); resolve(value); }; return b; };
    row.append(pick('Choose folders', 'folder', 'primary'), pick('Choose files', 'files', 'primary'));
    const cancel = el('div', 'actions'); cancel.append(pick('Cancel', null, 'ghost'));
    d.append(row, cancel); d.addEventListener('close', () => { d.remove(); resolve(null); });
    document.body.append(d); d.showModal();
  });
}
function prepare(r) {
  if (!r.count) { alert('No photos or videos were found there.'); return; }
  plan = r;
  $('iTitle').textContent = 'Add photos to the backup drive';
  $('iSummary').textContent = `${plural(r.count, 'file')}${r.videos ? ` (${plural(r.videos, 'video')})` : ''}, ${bytes(r.bytes)} → ${state.vault}`;
  const albums = Object.entries(r.albums).sort((a, b) => b[1] - a[1]);
  $('iAlbums').textContent = albums.slice(0, 4).map(([n, c]) => `${n} (${c})`).join(', ') + (albums.length > 4 ? `, +${albums.length - 4} more` : '');
  $('iAlbum').value = state.view.startsWith('album:') ? state.view.slice(6) : '';
  if ($('iAlbum').value) document.querySelector('input[name=mode][value=one]').checked = true;
  $('iChoose').hidden = false; $('iProgress').hidden = true; $('iFailed').replaceChildren();
  $('iStart').hidden = false; $('iStart').disabled = false; $('iCancel').textContent = 'Cancel';
  $('importDialog').showModal();
}
$('iAlbum').addEventListener('focus', () => { document.querySelector('input[name=mode][value=one]').checked = true; });
$('iStart').onclick = async () => {
  const one = document.querySelector('input[name=mode]:checked').value === 'one';
  const album = one ? $('iAlbum').value.trim() : null;
  if (one && !album) { $('iAlbum').focus(); return; }
  importing = true; paused = false; $('iPause').hidden = false; $('iPause').textContent = 'Pause';
  $('iChoose').hidden = true; $('iProgress').hidden = false; $('iStart').hidden = true; $('iCancel').textContent = 'Stop';
  $('iTitle').textContent = 'Adding to the backup drive…';
  $('iBar').style.width = '0'; $('iCounts').textContent = '';
  tally = { added: 0, duplicate: 0, failed: 0 };
  try {
    const result = await window.photos.import(plan.token, album);
    $('iTitle').textContent = result.failed.length ? 'Finished with problems' : 'Done';
    $('iCounts').innerHTML = '';
    $('iCounts').append(el('span', 'ok', `${plural(result.added.length, 'file')} added`), `  ·  ${result.duplicates.length.toLocaleString()} already on the drive`,
      ...(result.failed.length ? ['  ·  ', el('span', 'warn', `${result.failed.length.toLocaleString()} not added`)] : []));
    $('iNow').textContent = result.failed.length ? 'Files that were not added stay unchanged on this computer. Fix the problem below and add them again.' : 'Every added file was verified on the drive.';
    $('iFailed').replaceChildren(...result.failed.slice(0, 200).map((f) => el('li', null, `${f.file.split(/[\\/]/).pop()}: ${f.error}`)));
    if (result.added.length && album) pendingView = `album:${album}`;
  } catch (e) {
    $('iTitle').textContent = 'Could not add photos'; $('iNow').textContent = e.message;
  }
  importing = false; plan = null; paused = false; $('iPause').hidden = true;
  $('iCancel').textContent = 'Close';
};
let tally = { added: 0, duplicate: 0, failed: 0 };
window.photos.onImportProgress((p) => {
  if (p.status in tally) tally[p.status]++;
  $('iBar').style.width = `${(p.done / p.total) * 100}%`;
  if (!paused) $('iNow').textContent = `${p.done.toLocaleString()} of ${p.total.toLocaleString()} · ${p.file}`;
  $('iCounts').textContent = `${tally.added} added · ${tally.duplicate} already on the drive${tally.failed ? ` · ${tally.failed} not added` : ''}`;
});
let paused = false;
$('iPause').onclick = async () => {
  if (!importing) return;
  paused = !paused;
  await (paused ? window.photos.pauseImport() : window.photos.resumeImport());
  $('iPause').textContent = paused ? 'Resume' : 'Pause';
  $('iTitle').textContent = paused ? 'Paused' : 'Adding to the backup drive…';
  $('iBar').parentElement.classList.toggle('paused', paused);
  if (paused) $('iNow').textContent = 'Paused. Files already copied are kept and verified. Resume to continue, or Stop.';
};
const cancelImportHandler = () => {
  if (importing) { window.photos.cancelImport(); $('iCancel').disabled = true; setTimeout(() => ($('iCancel').disabled = false), 800); return; }
  const changed = $('iCancel').textContent === 'Close';
  $('importDialog').close(); plan = null;
  if (changed) load().then(() => { if (pendingView) { navigate(pendingView); pendingView = null; } });
};
let pendingView = null;
$('iCancel').onclick = cancelImportHandler;
$('importDialog').addEventListener('cancel', (e) => { if (importing || dupes.removing) e.preventDefault(); });

// Drag and drop from Explorer or Finder.
let dragDepth = 0;
const hasFiles = (e) => Array.from(e.dataTransfer?.types ?? []).includes('Files');
window.addEventListener('dragenter', (e) => { if (!hasFiles(e) || !state.vault) return; e.preventDefault(); dragDepth++; $('drop').hidden = false; });
window.addEventListener('dragover', (e) => { if (hasFiles(e)) e.preventDefault(); });
window.addEventListener('dragleave', () => { if (--dragDepth <= 0) { dragDepth = 0; $('drop').hidden = true; } });
window.addEventListener('drop', async (e) => {
  e.preventDefault(); dragDepth = 0; $('drop').hidden = true;
  if (!state.vault || importing || !e.dataTransfer.files.length) return;
  prepareFrom(window.photos.dropPaths(e.dataTransfer.files));
});

init();

// ---------- Fast index (catalog + thumbnails stored on the drive) ----------
// Kept up to date automatically: after the drive is read, missing photos are indexed in the background (two at a
// time), and the drive is re-read every 10 minutes and when you come back to the window. Thumbnails already in the
// index are used immediately; the rest appear as the index fills.
const REFRESH_MS = 10 * 60 * 1000;
let indexing = false, indexPaused = false, autoStopAt = -1;
const missingCount = () => Math.max(0, state.items.length - Math.min(state.indexed ?? 0, state.items.length));
function showFast() {
  const box = $('fast');
  box.hidden = !state.vault || state.missing || !state.items.length;
  if (box.hidden || indexing) return;
  $('fastUpdate').hidden = false; $('fastRebuild').hidden = false; // always available, also when the index is complete
  const total = state.items.length, missing = missingCount(), have = total - missing;
  box.classList.toggle('ready', missing === 0);
  $('fastText').textContent = missing === 0 ? `Fast index ready ✓ · ${total.toLocaleString()} photos` : have === 0
    ? 'Speed up this drive: store thumbnails on it so it opens fast on any phone or computer.'
    : `Fast index: ${have.toLocaleString()} of ${total.toLocaleString()} · ${missing.toLocaleString()} new`;
  $('fastBuild').textContent = have === 0 ? 'Build fast index' : `Update index (${missing.toLocaleString()})`;
}
function setIndexing(on) {
  indexing = on;
  for (const id of ['fastBuild', 'fastUpdate', 'fastRebuild']) $(id).hidden = on;
  $('fastPause').hidden = !on; $('fastStop').hidden = !on; $('fastBar').hidden = !on;
  if (!on) { $('fastBar').classList.remove('paused'); $('fastUpdate').hidden = false; $('fastRebuild').hidden = false; }
}
async function runIndex({ background = false, rebuild = false } = {}) {
  if (indexing || !state.vault || state.missing) return;
  setIndexing(true); indexPaused = false;
  $('fastPause').textContent = 'Pause';
  $('fastText').textContent = rebuild ? 'Rebuilding fast index…' : background ? 'Updating fast index in the background…' : 'Building fast index…';
  $('fastBar').querySelector('i').style.width = '0';
  const r = await window.photos.buildIndex({ background, rebuild });
  setIndexing(false);
  if (r?.error) { $('fastText').textContent = r.error; autoStopAt = Infinity; return; }
  // Files that could not be read stay "missing"; remember the count so the automatic update doesn't loop on them.
  if (r) autoStopAt = r.failed ?? 0;
  await load();
  if (r?.failed) $('fastText').textContent += ` · ${r.failed} could not be read`;
}
function maybeAutoIndex() {
  if (!state.autoIndex || indexing || importing || state.missing || !state.items.length) return;
  const missing = missingCount();
  if (missing > 0 && missing > autoStopAt) runIndex({ background: true });
}
$('fastBuild').onclick = () => runIndex();
$('fastUpdate').onclick = async () => { autoStopAt = -1; await load(); if (missingCount() > 0 && !indexing) runIndex(); };
$('fastRebuild').onclick = () => {
  if (confirm('Rebuild the fast index from scratch? Every photo on the drive is read again; this can take a while for a large drive. Photos are not changed.')) runIndex({ rebuild: true });
};
$('fastAuto').onchange = async (e) => { state.autoIndex = await window.photos.setAutoIndex(e.target.checked); if (state.autoIndex) { autoStopAt = -1; maybeAutoIndex(); } };
$('fastPause').onclick = async () => {
  indexPaused = !indexPaused;
  await (indexPaused ? window.photos.pauseIndex() : window.photos.resumeIndex());
  $('fastPause').textContent = indexPaused ? 'Resume' : 'Pause';
  $('fastBar').classList.toggle('paused', indexPaused);
  if (indexPaused) $('fastText').textContent = 'Paused. Everything indexed so far is kept.';
};
$('fastStop').onclick = () => { window.photos.cancelIndex(); autoStopAt = Infinity; $('fastText').textContent = 'Stopping…'; };
window.photos.onIndexProgress((p) => {
  if (!indexing) return;
  $('fastBar').querySelector('i').style.width = p.total ? `${(p.done / p.total) * 100}%` : '100%';
  if (!indexPaused) $('fastText').textContent = `${$('fastText').textContent.startsWith('Updating') ? 'Updating fast index' : 'Building fast index'} ${p.done.toLocaleString()} of ${p.total.toLocaleString()}`;
});
// Re-read the drive on a timer and when the window comes back, so photos added elsewhere show up and get indexed.
function quietRefresh() {
  if (!state.vault || state.loading || indexing || importing || !$('viewer').hidden || $('importDialog').open || dupes.removing) return;
  load();
}
setInterval(() => { if (!document.hidden) quietRefresh(); }, REFRESH_MS);
window.addEventListener('focus', () => { if (Date.now() - (state.loadedAt ?? 0) > 2 * 60 * 1000) quietRefresh(); });

// ---------- Duplicates ----------
const dupes = { data: null, off: new Set(), keep: new Map(), loading: false, removing: false };
async function findDupes() {
  dupes.loading = true; render();
  const r = await window.photos.findDuplicates();
  dupes.loading = false; dupes.data = r; dupes.off.clear(); dupes.keep.clear();
  $('countDupes').textContent = r?.extra ? r.extra.toLocaleString() : '';
  if (state.view === 'duplicates') render();
}
function selectedDupes() {
  const groups = (dupes.data?.groups ?? []).filter((g) => !dupes.off.has(g.hash));
  return { groups, extra: groups.reduce((n, g) => n + g.files.length - 1, 0), bytes: groups.reduce((n, g) => n + g.size * (g.files.length - 1), 0) };
}
function renderDuplicates() {
  $('title').textContent = 'Duplicates';
  const content = $('content'); content.replaceChildren();
  if (dupes.loading || !dupes.data) {
    $('subtitle').dataset.base = ''; showSubtitle();
    const box = el('div', 'empty'); const inner = el('div'); box.append(inner);
    inner.append(el('div', 'spinner'), el('p', null, 'Comparing fingerprints…'));
    content.append(box);
    if (!dupes.loading) findDupes();
    return;
  }
  const d = dupes.data, sel = selectedDupes();
  $('subtitle').dataset.base = d.groups.length ? `${plural(d.groups.length, 'set')} of identical files` : ''; showSubtitle();
  const head = el('div', 'dupes-head');
  const stat = el('div', 'stat'); stat.innerHTML = '';
  stat.append(el('b', null, bytes(sel.bytes)), ` can be freed · ${plural(sel.extra, 'extra copy', 'extra copies')}`);
  head.append(stat, el('div', 'spacer'));
  const again = el('button', 'ghost', 'Search again'); again.onclick = findDupes;
  const go = el('button', 'primary', `Remove ${plural(sel.extra, 'extra copy', 'extra copies')}`); go.disabled = !sel.extra; go.onclick = confirmRemoveDupes;
  head.append(again, go);
  content.append(head);
  if (d.unchecked) {
    const note = el('p', 'muted small', `${plural(d.unchecked, 'photo')} not in the fast index yet, so not compared. `);
    const b = el('button', 'ghost small', 'Index them now'); b.onclick = async () => { await runIndex(); findDupes(); };
    note.append(b); content.append(note);
  }
  if (!d.groups.length) {
    const box = el('div', 'empty'); const inner = el('div'); box.append(inner);
    inner.append(el('h2', null, 'No duplicates'), el('p', null, d.unchecked ? 'None among the indexed photos.' : 'Every photo on this drive is stored once.'));
    content.append(box); return;
  }
  content.append(el('p', 'muted small', 'Copies are byte-for-byte identical. Click a copy to keep it instead; untick a set to leave it alone. Each copy is checked again just before it is removed.'));
  for (const g of d.groups) {
    const keep = dupes.keep.get(g.hash) ?? g.keep;
    const card = el('section', 'dupe' + (dupes.off.has(g.hash) ? ' off' : ''));
    const top = el('label', 'dupe-top');
    const box = el('input'); box.type = 'checkbox'; box.checked = !dupes.off.has(g.hash);
    box.onchange = () => { box.checked ? dupes.off.delete(g.hash) : dupes.off.add(g.hash); render(true); };
    top.append(box, el('b', null, `${g.files.length} copies`), el('span', 'muted', `${bytes(g.size)} each · frees ${bytes(g.size * (g.files.length - 1))}`));
    card.append(top);
    const list = el('div', 'dupe-files');
    for (const f of g.files) {
      const isKeep = f.id === keep;
      const b = el('button', 'dupe-file' + (isKeep ? ' keep' : ''));
      b.title = `${f.folder}/${f.name}`;
      const t = el('div', 'thumb'); const img = el('img'); img.loading = 'lazy'; img.src = thumbUrl(f); img.onerror = () => img.remove(); t.append(img);
      b.append(t, el('span', 'tag', isKeep ? 'Keep' : 'Remove'), el('b', null, f.name), el('small', null, f.folder));
      b.onclick = () => { dupes.keep.set(g.hash, f.id); render(true); };
      list.append(b);
    }
    card.append(list); content.append(card);
  }
}
async function confirmRemoveDupes() {
  const sel = selectedDupes(); if (!sel.extra) return;
  if (!confirm(`Remove ${plural(sel.extra, 'extra copy', 'extra copies')} (${bytes(sel.bytes)}) from the backup drive?\n\nOne copy of each photo is kept. Every copy is re-read and compared just before it is removed. ${document.body.dataset.platform === 'darwin' ? "Removed copies go to the Trash; empty the Trash (with the drive connected) to free the space." : "Removed copies go to the Recycle Bin where the drive has one; on most USB drives they are deleted straight away."}`)) return;
  dupes.removing = true;
  const d = $('importDialog');
  $('iTitle').textContent = 'Removing extra copies…'; $('iSummary').textContent = `${plural(sel.extra, 'copy', 'copies')} · ${bytes(sel.bytes)}`;
  $('iChoose').hidden = true; $('iProgress').hidden = false; $('iStart').hidden = true; $('iPause').hidden = true;
  $('iBar').style.width = '0'; $('iCounts').textContent = ''; $('iNow').textContent = ''; $('iFailed').replaceChildren();
  $('iCancel').textContent = 'Stop';
  $('iCancel').onclick = () => window.photos.cancelDuplicates();
  d.showModal();
  const r = await window.photos.removeDuplicates(sel.groups.map((g) => ({ hash: g.hash, keep: dupes.keep.get(g.hash) ?? g.keep })));
  dupes.removing = false;
  $('iTitle').textContent = r?.failed?.length ? 'Finished with problems' : 'Done';
  $('iCounts').textContent = r ? `${plural(r.removed, 'copy', 'copies')} removed · ${bytes(r.freed)} freed` : '';
  const trashNote = document.body.dataset.platform === 'darwin' && r?.removed ? ' They are in the Trash: empty it, with the drive connected, to free the space.' : '';
  $('iNow').textContent = (r?.failed?.length ? 'These were kept:' : 'One copy of each photo is still on the drive.') + trashNote;
  $('iFailed').replaceChildren(...(r?.failed ?? []).slice(0, 200).map((f) => el('li', null, `${f.name}: ${f.error}`)));
  $('iCancel').textContent = 'Close';
  $('iCancel').onclick = () => { d.close(); $('iCancel').onclick = cancelImportHandler; findDupes(); load(); };
}
window.photos.onDuplicateProgress((p) => {
  if (!dupes.removing) return;
  $('iBar').style.width = `${(p.done / p.total) * 100}%`;
  $('iNow').textContent = `${p.done.toLocaleString()} of ${p.total.toLocaleString()} · ${p.file}`;
});
