# PhotoS for Windows and macOS

Browse the photos and videos on a PhotoS backup drive (the `PhotoS` folder), and add photos from your PC to it.

## Use
1. Plug in the backup drive and open **PhotoS**.
2. Click the drive card at the top left (it shows the name you gave the drive in the PhotoS phone app) and choose the drive (or its `PhotoS` folder).
3. Browse **All photos**, **Videos**, or an album. Click a photo to open it: arrow keys move between items, **I** shows details, Esc closes.
4. **Add photos** (or drag files and folders onto the window) copies them to the drive:
   - **Keep folder names as albums**: `Pictures\Family Trip\a.jpg` goes to `PhotoS\Family Trip\a.jpg`, the same rule the phone uses.
   - **Put everything in one album**: pick an existing album or type a new name.
   - Files already on the drive are skipped by content (SHA-256), even if renamed.
   - Each copy is written to a hidden `.photos-part-*` file, re-read and compared, then renamed. A different file with the same name is saved as `<hash>_name`, like the phone.
   - Files on the PC are never changed or deleted.
   - **Copy N files at a time** (1–10, default 4): higher is faster on SSDs and fast USB 3 drives; use 1–2 for slow USB sticks.
   - **Pause / Resume** at any time; files already copying finish and are verified. **Stop** ends the import; what was copied is kept.

HEIC photos and some video formats are shown through Windows' own thumbnails, so they appear when Windows itself can preview them (install *HEIF Image Extensions* / *HEVC Video Extensions* from the Microsoft Store if needed).

A drive backed up before the rename has a `DualVault` folder; PhotoS renames it to `PhotoS` the first time you choose the drive.

## Search
Type a name, an album, or a date the photo was taken (day first): `2023`, `May 2023`, `12/05/2023`, `12 May`, `yesterday`, `last month`…

## Fast index
The panel under the drive card shows whether the drive has a fast index (catalog + thumbnails stored on the drive, shared
with the PhotoS phone app). With **Keep updated automatically** on (default), missing photos are indexed in the background
whenever the drive is opened, and the drive is re-read every 10 minutes and when you return to the window.
**Update now** re-reads the drive and indexes anything new; **Rebuild** starts the index from scratch. Pause and Stop keep
what is done. Photos added from this computer are indexed while they are copied.

## Duplicates
**Duplicates** in the sidebar lists byte-identical photos and videos on the drive and how much space the extra copies use.
Pick which copy to keep (or untick a set), then **Remove extra copies**. Each copy is re-checked just before removal.
On a Mac removed copies go to the Trash: empty it with the drive connected to free the space.

## Shortcuts
- Back / Forward: mouse side buttons, Alt+Left / Alt+Right, or Backspace
- Ctrl+F: search, Esc: clear search, F5: rescan the drive
- In the viewer: Left/Right arrows, I for details, Esc to close

## Install
- `PhotoS-Setup-<version>.exe`: installer. Installs for all users in `C:\Program Files\PhotoS` and asks for administrator permission once. Running a newer installer upgrades in place (PhotoS is closed automatically); an older PhotoS that was removed by hand does not block it.
- `PhotoS-<version>-portable.exe`: runs without installing.

## macOS
- `PhotoS-<version>-arm64.dmg` for Apple Silicon Macs (M1 and later), `PhotoS-<version>-x64.dmg` for Intel Macs. Open the `.dmg` and drag **PhotoS** to **Applications**. (`.zip` versions contain the same app.)
- The app is not notarized by Apple (that needs a paid Apple Developer ID), so the first launch is blocked: open **System Settings → Privacy & Security**, scroll down and click **Open Anyway** next to PhotoS, then confirm. If macOS says the app "is damaged", run `xattr -dr com.apple.quarantine /Applications/PhotoS.app` in Terminal once.
- Choose your drive from `/Volumes`. macOS may ask once to allow access to removable volumes; click **Allow**.
- Shortcuts: ⌘[ / ⌘] for back/forward, ⌘F search, ⌘R rescan.
- Uninstall: quit PhotoS and move it from Applications to the Bin. Its settings and thumbnail cache are in `~/Library/Application Support/PhotoS`.

## Uninstall (Windows)
- Installed version: **Start → PhotoS → Uninstall PhotoS**, or **Settings → Apps → Installed apps → PhotoS → Uninstall**. This removes the app, its shortcuts and its own settings and thumbnail cache (`%APPDATA%\PhotoS`).
- Portable version: delete the `.exe`. Its settings and thumbnail cache stay in `%APPDATA%\PhotoS`; delete that folder too if you want.
- Uninstalling never touches the photos on your backup drive or on your PC.

The builds are not code-signed, so Windows SmartScreen shows "Windows protected your PC" the first time: click **More info → Run anyway**.

## Build
```
npm install
npm test
npm run dist:win     # writes dist/*.exe (works from macOS or Windows)
npm run dist:mac     # writes dist/*.dmg and *.zip for Apple Silicon and Intel (run on a Mac)
npm start            # run from source
```
