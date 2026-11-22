# PhotoS 1.5.0

## Search by date (and album)
The search box (phone and Windows/Mac) now finds photos by the date they were taken, as well as by name and album.
Dates are read day first: `2023` · `May` · `May 2023` · `05/2023` · `2023-05` · `12/05/2023` · `12-05-23` · `12 May` ·
`12 May 2023` · `2023-05-12` · `today` · `yesterday` · `this month` · `last month` · `this year` · `last year`.
A missing part means "any": `12 May` finds 12 May of every year. The app shows what it understood ("Photos from 11 June 2022").
- Desktop fix: **Update now** and **Rebuild** are shown in the drive panel even when the index is complete.

# PhotoS 1.4.0 (Android)
- **Drives** screen shows each drive's fast index status ("Fast index: N of M" / "ready ✓"), with **Update index** and,
  in the ⋮ menu, **Rebuild fast index** and **Rename**.
- **Keep fast index updated automatically** (on by default): browsing a drive indexes new photos in the background, two at
  a time (progress on the Backup screen, with Pause/Stop). While the app is open on a drive, it is re-read every 10 minutes.
- **Duplicates** (per drive): lists byte-identical copies with the space they use; pick the copy to keep, untick sets to
  skip, then remove. Runs in the background; each copy and the kept file are re-read and compared just before deletion.
  Android has no Trash for USB drives, so removed copies are deleted permanently (the app says so before you confirm).

# PhotoS 1.4.0 (desktop: Windows and Mac)

## Automatic fast index, and Duplicates
- **Keeps itself up to date:** when a drive is opened, photos missing from the fast index are indexed in the
  background (two at a time, so browsing stays smooth). The drive is re-read every 10 minutes and when you return to the
  window, so photos added elsewhere appear and get indexed. Thumbnails already indexed are used straight away; the rest
  appear as indexing goes. Turn off with **Keep updated automatically** in the drive panel.
- **Update now** and **Rebuild** are always available in the drive panel (Rebuild re-reads every photo; photos are not changed).
- **Duplicates:** finds byte-identical photos and videos on the drive (using the fast index fingerprints) and shows how
  much space they take. One copy of each set is kept: by default the one in a named album with the earliest date; click
  another copy to keep it instead, or untick a set to leave it. Before each removal, the kept copy and the extra are
  re-read and compared, so a file that changed is never removed. Removed copies go to the Trash / Recycle Bin when the
  drive has one (on a Mac, empty the Trash with the drive connected to free the space); on most USB drives under
  Windows they are deleted straight away.
- The phone app works with the result: its next check finds each photo's remaining copy by content, so nothing is copied again.

# PhotoS 1.3.2 (desktop)
- Mac app: hidden "._" files are now removed automatically whenever a drive is opened, not only when building the
  index (the Build button is hidden once the index is complete).

# PhotoS 1.3.1

## Fix: photos added from a Mac showed as broken on the phone
When a Mac copies files to an exFAT/FAT drive, macOS writes a hidden "._name" companion file (Mac-only file
information, not a picture) next to every file and folder. The phone app listed these as photos that never loaded.
- **Phone:** hidden files (names starting with ".") are never listed as photos, so existing drives show correctly.
- **Mac app:** removes the "._" files after adding photos, and **Build fast index** also cleans any left in the PhotoS
  folder from earlier copies (same as Apple's `dot_clean`). The photos themselves are not changed.

# PhotoS 1.3.0

## Fast index on the drive (phone and Windows)
A hidden `PhotoS/.index` folder on the drive holds a catalog (name, size, capture date, fingerprint of every photo) and
packed thumbnails (512 px JPEGs in a few large files). Both apps read and write the same format (docs/DRIVE-INDEX.md),
so a drive indexed by one opens fast on the other, including the first time on a new phone or PC.
- **Opening a drive:** the timeline appears at once from the catalog, with real capture dates; thumbnails are one small
  read each (about 40 KB) instead of the whole photo. The drive is still listed in the background to pick up changes.
- **Kept up to date automatically:** phone backups and checks add each photo using Android's own thumbnail (almost no
  extra time); photos added from Windows are indexed while copying; the Windows app also saves capture dates it reads.
- **Build fast index:** for photos already on the drive. Phone: **Drives → Build fast index** (progress on the Backup
  screen, with Pause/Stop). Windows: the panel under the drive card shows "Fast index: N of M" and **Build/Update
  index**, with Pause/Stop. Only new or changed photos are processed each time.
- About 40 KB per photo on the drive (≈ 400 MB per 10,000 photos). Private vault items are never included. A read-only
  drive keeps working without an index.

# PhotoS 1.2.1

## New app icon, and Windows install/uninstall fixes
- **New icon** on the phone (adaptive launcher icon, works with any icon shape and with themed icons) and on Windows (app, taskbar, installer, uninstaller): a photo with a green sync badge.
- **Windows: Task Manager now shows "PhotoS"** with the PhotoS icon instead of "Electron" (the program's name, description and icon are written into PhotoS.exe).
- **Windows: upgrades and uninstall work.** The installer always installs for all users in Program Files (one administrator prompt), closes a running PhotoS first, and no longer stops when an earlier PhotoS's uninstaller is missing or broken (for example after its folder was deleted by hand). The uninstaller also closes PhotoS before removing it.

# PhotoS 1.2.0

## Name your drives
- When you add a drive, PhotoS asks for a name (suggested from the folder, e.g. "FakeUSB"); type something like "Family SSD".
- Rename any drive later: **Drives → Rename**, or tap the drive's name.
- The name is also saved on the drive itself (`PhotoS/.photos-drive.json`), so another phone adding that drive suggests the same name, and the Windows app shows it in its drive card. A read-only drive keeps the name on the phone only.

# PhotoS 1.1.0

## Faster drive loading, parallel work on Windows, and Pause
- **Phone, opening a drive:** folders are read 6 at a time and hidden/system folders (`.Trashes`, `System Volume Information`, `$RECYCLE.BIN`, `LOST.DIR`…) are skipped. Each drive's listing is saved on the phone, so it opens instantly next time while a fresh scan runs in the background; on a first visit photos appear in batches as they are found.
- **Phone, drive thumbnails:** made once (single scaled read, no full-size decode) and kept in the app's cache (up to 300 MB, oldest removed first), instead of re-reading every photo over USB on each visit.
- **Phone, Pause:** Backup and restore can be paused and resumed from the Backup screen or the notification. Files already copying finish and are verified; the phone may sleep while paused.
- **Windows, parallel processing:** fingerprints are computed on a pool of worker threads (one per CPU core, up to 8); copies use the system's native file copy and are verified by fingerprint; choose 1–10 files at a time in the Add dialog (default 4, remembered).
- **Windows, faster opening:** the drive shows at once using quick dates, then capture dates are read 16 at a time in the background and the grid re-sorts once; more thumbnails render in parallel.
- **Windows, Pause:** Pause / Resume while adding photos; Stop works while paused. The PC is kept awake during an import.

# PhotoS 1.0.0

## Renamed from DualVault to PhotoS (S for Sync)
- New app name everywhere, and a new Android package: `app.photos` (was `app.dualvault`), version 1.0.0. It installs as a separate app next to DualVault; it is not an update of it.
- The backup folder on drives is now `PhotoS`. An existing `DualVault` folder is renamed to `PhotoS` automatically on the first backup from the phone, or when the drive is chosen in the Windows app, so no backup is lost or copied twice. Old `.dualvault-part-*` leftovers are still ignored.
- Restores go to `Pictures/PhotoS Restored` and `Movies/PhotoS Restored`.
- Signing is unchanged (same release key); the build script now reads `PHOTOS_*` environment variables.
- The Windows app is now **PhotoS** too (`desktop/`, version 1.0.0).

## Navigation and loading (1.0.0)
- Albums, Favorites, Videos and folders opened from Collections, and drives opened from Drives, show a back arrow with their name. Back returns to the tab you came from (it used to close the app).
- Back order: selection, then search, then the opened view, then filters, then the drive library back to the phone.
- Each tab keeps its place; tapping the current tab returns to the top.
- Shimmer placeholders while the library, thumbnails, collection tiles and vault thumbnails load.
- Windows app: Back/Forward (mouse side buttons, Alt+Left/Right, Backspace) with a back arrow, per-view scroll memory, Ctrl+F for search, F5 to rescan; skeleton grid on first scan, progress bar and spinning refresh during rescans, "Finding photos…" while big folders are read, and a spinner in the viewer.

### Moving from DualVault to PhotoS
1. In **DualVault**, open the private vault and **Restore** every item (they return to the phone gallery, in `DualVault Restored`).
2. Install **PhotoS**, allow photo access, create a new vault PIN, and turn on fingerprint unlock.
3. Choose the backup drive in PhotoS and run **Check** or **Back up**. The drive folder is renamed and existing copies are recognised by content, so nothing is copied again.
4. Move the restored photos into the new vault (select them, long-press **Back up**).
5. Only then uninstall DualVault. Uninstalling it deletes its vault, favourites and albums.

# DualVault 0.8.1

## Fingerprint first, and a photo grid inside the vault (0.8.1)
- With fingerprint unlock on, the vault asks for the fingerprint automatically whenever it opens and whenever you return to the app. **Use PIN** / **Use password** switches to the keypad; **Use fingerprint** switches back.
- The vault is now a photo grid with thumbnails. Tap one to open a full-screen viewer: swipe between items, pinch or double-tap to zoom, play videos, and **Restore**, **Info** or **Delete** from the bottom bar.
- Lock and settings are icons at the top. Settings (fingerprint, hide vault, change PIN or password) open in a sheet.
- Thumbnails are stored encrypted. New imports use the phone's thumbnail; older vault items get one built once from the encrypted original.
- Photos and videos are decrypted in memory only, block by block, so videos of any size play without a decrypted copy ever being written to storage. Screenshots of the vault stay blocked.

# DualVault 0.8.0

## PIN, password and fingerprint unlock for the vault (0.8.0)
- When creating the vault, choose **PIN** (at least 6 digits, number keypad) or **Password** (at least 10 characters).
- Inside the unlocked vault: **Change PIN or password** switches between them at any time. Vault files are not re-encrypted; only the key is re-wrapped. Existing password vaults keep working unchanged until you change them.
- **Unlock with fingerprint**: once on, opening the vault shows the system fingerprint/face sheet straight away, and **Use PIN** / **Use password** falls back. Only strong (Class 3) biometrics are accepted.
- The fingerprint key lives in Android Keystore, needs a fresh fingerprint for every unlock, and is destroyed by Android if a new fingerprint is enrolled or the screen lock is removed. The vault then turns fingerprint unlock off and asks for the PIN/password.
- New and changed PINs/passwords are additionally bound to this phone's Keystore, so a copied key file cannot be guessed on another computer.
- After 5 wrong attempts the vault waits 30 seconds, doubling each time up to 1 hour.
- Forgotten PIN/password still cannot be recovered. Fingerprint unlock is a convenience, not a recovery method.

# DualVault 0.7.5

## Vault gestures (0.7.5)
- Long-press **Photos** title: open the vault.
- Select photos, then long-press **Back up** in the bottom bar: move the selection into the vault. Works even when no backup drive is chosen.
- While viewing a photo, long-press the **heart**: move that photo into the vault. A normal tap still toggles Favorite.
- The "N selected" title no longer has a hidden action.

# DualVault 0.7.4

## Hidden private vault (0.7.3)
Inside the unlocked vault, turn on **Hide private vault**. The vault then disappears from the ⋮ menu, Collections and the selection bar. Open it by long-pressing the **Photos** title (see 0.7.5 for moving photos in). Only someone who has unlocked the vault can switch hiding off. Hiding conceals the entry point only; the vault's protection is still its password and encryption.

## Faster phone-deletion review, with multi-select (0.7.4)
- Phone status for all recorded backups is read in two MediaStore queries instead of one query per photo.
- Each drive file is checked with one provider query instead of three, with up to 8 in parallel; thumbnails use the thumbnail cache.
- Deleting a drive file reuses its saved fingerprint while its size and date are unchanged, instead of re-reading the whole file. New backups save their fingerprint immediately.
- Each photo's phone status is still re-checked individually just before its drive copy is deleted.
- The review has checkboxes, **Select all / Clear**, and bulk **Keep N** / **Delete N** actions.

# DualVault 0.7.2

## Quick check (0.7.2)
"Check what's on the drive" and "Back up missing photos" now use a quick check instead of reading every file:
- A photo whose size and date are unchanged since its recorded backup, and whose drive copy is still listed with the same size, counts as present without being read.
- A photo can only be on the drive if a drive file has exactly the same size; when none does, the photo is not read during a check.
- Otherwise fingerprints (SHA-256) are reused from the cache when size and date are unchanged, and only the remaining files are read.
- Copies themselves always use a freshly read fingerprint and are verified after writing, as before.

"Deep verify" re-reads every phone file and every matching drive file byte for byte (the previous behaviour). Use it occasionally, for example monthly, to detect silent drive corruption that the quick check cannot see.

# DualVault 0.7.1

## Parallel copies (0.7.1)
Backup → Parallel copies sets how many files are checked, copied and verified at once (1–10, default 4). Drive indexing uses the same setting. Higher values help on USB flash drives and SSDs; spinning hard drives are often fastest at 1–2. Identical photos are still copied only once, because files with the same content wait for each other, and every copy is still verified. If one file fails, the others stop and the run can be resumed as before. Deleting drive files remains one at a time.

# DualVault 0.7.0

## Organised backups
Backups are sorted on the drive by the phone folder each file came from: `DualVault/Camera/`, `DualVault/Screenshots/`, `DualVault/WhatsApp Images/`, and so on. Files keep their original names; if a different file with the same name already exists, the copy gets a content-hash prefix instead of replacing it. Backups made by earlier versions stay where they are and are still recognised by content, so they are not copied again.

## Permissions
First launch shows Android's own permission prompts directly. The setup screen is still available from Photos → ⋮ → Permissions & setup. If Android has stopped showing a prompt after repeated denials, tapping Allow opens the app's settings page.

## Faster
- Drive file hashes are cached (keyed by file, size and modification time), so repeat backups and drive checks no longer re-read the whole drive. Any cached match is still re-hashed before a file is skipped.
- Each phone file is read once fewer during backup.
- Grid thumbnails use Android's thumbnail cache instead of decoding full-size originals; one shared image cache survives screen changes.
- Filtering, date grouping and counts are computed only when their inputs change.
- Returning to the app no longer clears the grid or your selection; USB folders are rescanned only when stale or when a drive is attached.
- Release builds are shrunk with R8 (APK about 27 MB → 4.4 MB).

## Interface
Swipeable full-screen viewer with double-tap zoom, an info sheet, and video duration badges. Pinch the grid to change columns. Selection now has a contextual top bar, a bottom action bar and per-day "Select day". Backup status shows as small corner badges plus a summary strip. Collections show cover images. The Backup and Drives tabs show connection state and backup coverage. Fixed invisible (black-on-black) text in full-screen dialogs.
