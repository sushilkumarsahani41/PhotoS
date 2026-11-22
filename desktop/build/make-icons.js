// Renders build/icon.svg to PNGs with Electron and writes build/icon.ico (16–256 px) and src/icon.png.
// Run: npx electron build/make-icons.js
const { app, BrowserWindow, nativeImage } = require('electron');
const fs = require('fs'); const path = require('path');
const svg = fs.readFileSync(path.join(__dirname, 'icon.svg'), 'utf8');
async function render(size) {
  const win = new BrowserWindow({ width: size, height: size, show: false, frame: false, transparent: true, webPreferences: { offscreen: true } });
  const html = `<html><body style="margin:0;background:transparent"><img src="data:image/svg+xml;base64,${Buffer.from(svg).toString('base64')}" width="${size}" height="${size}"></body></html>`;
  await win.loadURL('data:text/html;base64,' + Buffer.from(html).toString('base64'));
  await new Promise((r) => setTimeout(r, 150));
  const img = await win.webContents.capturePage({ x: 0, y: 0, width: size, height: size });
  win.destroy();
  return img.resize({ width: size, height: size, quality: 'best' }).toPNG();
}
function ico(pngs) {
  const header = Buffer.alloc(6); header.writeUInt16LE(0, 0); header.writeUInt16LE(1, 2); header.writeUInt16LE(pngs.length, 4);
  const dir = Buffer.alloc(16 * pngs.length); let offset = 6 + dir.length;
  pngs.forEach(([size, png], i) => {
    const o = i * 16; dir[o] = size >= 256 ? 0 : size; dir[o + 1] = size >= 256 ? 0 : size;
    dir.writeUInt16LE(1, o + 4); dir.writeUInt16LE(32, o + 6); dir.writeUInt32LE(png.length, o + 8); dir.writeUInt32LE(offset, o + 12); offset += png.length;
  });
  return Buffer.concat([header, dir, ...pngs.map(([, p]) => p)]);
}
app.disableHardwareAcceleration();
app.whenReady().then(async () => {
  try {
    // Render once at full size, then scale down for each icon size.
    const big = nativeImage.createFromBuffer(await render(1024));
    const at = (s) => big.resize({ width: s, height: s, quality: 'best' }).toPNG();
    const sizes = [16, 20, 24, 32, 40, 48, 64, 128, 256];
    fs.writeFileSync(path.join(__dirname, 'icon.ico'), ico(sizes.map((s) => [s, at(s)])));
    fs.writeFileSync(path.join(__dirname, 'icon.png'), big.toPNG());
    fs.writeFileSync(path.join(__dirname, '..', 'src', 'icon.png'), at(256));
    console.log('icons written');
  } catch (e) { console.error('FAILED', e); }
  app.quit();
});
