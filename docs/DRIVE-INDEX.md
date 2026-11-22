# Drive index (`PhotoS/.index`)

A small catalog and packed thumbnails stored on the backup drive, so a drive opens fast on any phone or PC,
including the first time. Written and read by both the Android app and the Windows app. Optional: when it is
missing, stale or unwritable, both apps fall back to listing the drive and making thumbnails themselves.

## Files
```
<drive>/PhotoS/.index/catalog.json        the catalog (replaced atomically)
<drive>/PhotoS/.index/catalog.json.new    temporary while saving; read only if catalog.json is missing
<drive>/PhotoS/.index/thumbs-000.bin      thumbnail pack 0 (append-only, up to 64 MB)
<drive>/PhotoS/.index/thumbs-001.bin      …
```
The folder name starts with `.`, so photo listings on both apps skip it. Private vault items never go here.

## catalog.json
```json
{
  "version": 1,
  "updated": 1763600000000,
  "items": [["Camera/IMG_1.jpg", 4123456, 1763500000000, 1690000000000, "<sha256 hex>", "image/jpeg"]],
  "thumbs": { "<sha256 hex>": [0, 0, 41234] }
}
```
- `items`: `[path, size, modified, date, hash, mime]`
  - `path`: relative to the `PhotoS` folder, `/` separators.
  - `size`: bytes. An entry is used only if the file on the drive still has this size.
  - `modified`: file time in ms when indexed (informational).
  - `date`: capture time in ms (EXIF / MediaStore date taken / video header), `0` if unknown.
  - `hash`: SHA-256 of the file content, `""` if not computed yet.
  - `mime`: e.g. `image/jpeg`, `video/mp4`.
- `thumbs`: content hash → `[pack number, byte offset, byte length]`. Each record is one JPEG (≤ 512 px on the
  long side). Keyed by content, so renamed or duplicate files share one thumbnail. Readers check the JPEG starts
  with `FF D8` and otherwise ignore it.

## Writing rules
- Append thumbnails to the highest-numbered pack; start the next pack when it would pass 64 MB.
- Save the catalog by writing `catalog.json.new`, then replacing `catalog.json` with it.
- Bytes appended to a pack but not yet listed in a saved catalog are harmless (unreferenced).
- Only one device writes at a time (the drive is connected to one device).
