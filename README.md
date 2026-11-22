# PhotoS

PhotoS (S for Sync), formerly DualVault. Package `app.photos`. Backups live in a `PhotoS` folder on the drive; a drive backed up by DualVault has its `DualVault` folder renamed to `PhotoS` automatically on the first backup (phone) or when chosen (Windows app in `desktop/`).

An Android 15+ photo/video gallery with verified backup to a user-selected USB-drive folder and restore to the phone. **Version 0.6.0 is a working preview, not a finished replacement for Google Photos.**

See [0.7 release notes](VERSION-0.7.md) for organised per-album backup folders, faster scanning, and the redesigned interface.

## What works

- Phone gallery using Android MediaStore, newest capture dates first, falling back to modification dates.
- Photo/video grid, filename search, folder/type filters, multi-selection.
- Black and charcoal interface with a continuous photo grid, compact filters, and long-press selection.
- Full-screen images with pinch zoom and pan; in-app video playback and file details.
- Separate controls for the gallery source and USB backup destination.
- Multiple remembered drive folders via Android's Storage Access Framework.
- Refresh on app resume and USB attachment/detachment; manual refresh for slow mounts.
- Back up selected phone photos/videos, or filter a folder and select all.
- Byte-for-byte copy of the original accessible file with SHA-256 read-back verification.
- Persistent SQLite journal. Re-running a transfer verifies and skips completed copies; interrupted files restart from byte zero.
- Foreground transfer notification, cancellation, Android 15 service timeout handling.
- Restore selected USB media into `Pictures/PhotoS Restored` or `Movies/PhotoS Restored`, with verification before publishing to the phone gallery.
- No server, analytics, account, or internet permission. No delete mirroring.

## Install the included test APK

Install `PhotoS-release.apk` on your Android 15+ phone. It is signed with the existing release key and can update previous signed releases without uninstalling. `PhotoS-debug.apk` is for development and uses a different key. This preview is not a Play Store release.

Start with a small set of nonessential sample files. A real USB drive test is still required for your phone, adapter, drive filesystem, and available power.

## First backup

1. Connect your USB drive to the phone. Confirm Android's Files app can read and write it. Some hard drives need a powered USB hub.
2. Open **PhotoS → Photos → Allow** (or **⋮ → Manage photo access**). Choose full photo/video access, or explicitly select the files you want available. Original metadata access is required for backup so Android does not silently strip embedded location metadata.
3. Open **Drives → Add drive folder**. Choose a writable folder on the physical USB drive and tap **Use this folder**. Android's picker may also show cloud providers; select your actual physical drive. Android may restrict selecting certain volume roots; choose a subfolder.
4. Set that folder as the **Backup destination**. Adding the first drive sets the default; browsing another drive does not change it.
5. In **Photos**, choose **This phone** using the source selector. Filter a folder/type if desired. Long-press a photo, or choose **⋮ → Select photos**, then use checkboxes or **Select all**.
6. Tap **Back up selected**, review the destination, and start. **Backup** shows progress and the last result. Enable notifications there if desired.
7. Files go into `PhotoS/<album>` on the drive, matching the phone folder (Camera, Screenshots, WhatsApp Images…). Original names are kept; a content-hash prefix is added only when a different file already has that name. Original bytes and embedded metadata are preserved. Filesystem creation/modification times and full nested folder paths are not preserved.
8. To restore, browse a drive, select files, and tap **Restore to phone**. Nothing is removed from the drive.

Keep Google Photos backup enabled in Google's app separately. PhotoS cannot browse or audit your full existing Google Photos library, does not upload to Google, and never treats USB verification as proof of a cloud copy. Cloud-only media must first be downloaded/exported to accessible local files.

## Build locally

Requirements: JDK 17, Android SDK Platform 35, Android SDK Build Tools 35.0.0, and internet access to fetch dependencies. Open this folder in Android Studio or:

```sh
# Point ANDROID_HOME at your Android SDK and JAVA_HOME at your JDK 17.
./gradlew testDebugUnitTest lintDebug assembleDebug
```

On Windows use `gradlew.bat`. Android Studio can create `local.properties` with your SDK path; do not commit it. Output: `app/build/outputs/apk/debug/app-debug.apk`.

Pinned toolchain: Gradle 8.11.1, Android Gradle Plugin 8.9.2, Kotlin 2.1.20, Compose, Media3, Coil; min/target/compile SDK 35. Minimum Android version is 15. Later versions are intended to work, but need device testing; Play publishing requirements should be reviewed at release time.

## Upload to your GitHub

Create an empty repository, then run from this project folder:

```sh
git init
git add .
git commit -m "Add PhotoS Android gallery and USB backup preview"
git branch -M main
git remote add origin https://github.com/YOUR_USERNAME/PhotoS.git
git push -u origin main
```

The included `.github/workflows/android.yml` runs unit tests, Android lint, and APK compilation on pushes, pull requests, or manual runs. On GitHub, open **Actions → Build Android APK → completed run → Artifacts → PhotoS-debug-apk**. Download and unzip the artifact to get the APK. Upload the **contents of this project folder**, including `.github` and `gradle`, as the repository root.

CI debug signing keys may differ between clean runners and local builds. For consistent app updates, set up a private release keystore and GitHub secrets. Never commit a signing key. An APK signed with a different key cannot update an existing installation; uninstalling resets local permissions and transfer history.

## Current boundaries

- Manual transfer start; no unattended attach-triggered backup or scheduling yet. Android may limit background execution and stops long data-sync services after its time allowance. Restart from the app to continue.
- Photos and videos only. No documents, contacts, messages, or full-phone/system backup.
- Source selection is one phone library or one drive folder at a time. No merged all-drive view, offline thumbnail catalog, editing, face recognition, recycle bin, or cloud login yet.
- The phone gallery reads the primary shared-storage media library. Additional SD cards can be added as drive folders; direct backup currently starts from the phone gallery.
- Playback/decoding depends on the phone's supported formats and codecs. DRM media is unsupported.
- The first scan reads the selected drive folder recursively. Very large libraries need further indexing/paging optimization. Android folder permissions can expire and need granting again.
- Completed files are re-read to verify before skipping; this favors integrity over speed and consumes battery/drive I/O. Duplicate suppression compares exact content hashes across the selected drive folder, including copies made outside this app. It does not remove existing duplicates.
- Restored files retain embedded metadata, but names can include backup hash prefixes; restored filesystem dates are new.
- Removing app data/uninstalling loses the private transfer journal and remembered permissions; files on the drive remain intact, but the next full content scan can recognize matching copies again.
- Sudden process death can leave a partial file or, in the small interval between rename and journal commit, an untracked verified copy. Rerunning recovers journaled partial files; untracked files are preserved instead of guessed/deleted. Hidden `.photos-part-*` files belong to interrupted copies; do not remove them during an active transfer.
- A physically damaged drive, bad cable, unsupported filesystem, insufficient power, or full drive may interrupt a transfer. Only files reported verified are complete; retain the phone originals.

## Source layout

- `MainActivity.kt`: Compose gallery, drive/destination controls, permission flow, image/video viewer.
- `MediaRepository.kt`: MediaStore and document-tree enumeration, persisted drive permissions.
- `TransferService.kt`: foreground backup/restore, cancellation, streaming verification and recovery.
- `Journal.kt`: durable copy records (SQLite).
- `Integrity.kt`: streaming SHA-256/copy primitive and filename sanitization.
- `app/src/test`: corruption, copy fidelity, cancellation, full-drive failure, and filename tests.

## Privacy

All work takes place on your phone and the storage provider you explicitly select. The app itself has no internet permission or telemetry. If you choose a cloud-backed folder in Android's picker, that provider may transfer data under its own account/settings. Media location access is used only to preserve original photo bytes, not to track the device. Android app data backup is disabled.

See [TESTING.md](TESTING.md) for checks to run before trusting a large backup. See [VALIDATION.md](VALIDATION.md) for checks performed for this delivery.

## Signed release

A separately delivered `PhotoS-release.apk` is available. See [RELEASE.md](RELEASE.md) for signing, future updates, and switching from the debug APK.

## Version 0.2.0 — black gallery redesign

True black backgrounds, neutral charcoal surfaces, white controls, monochrome icons, a photo-first grid grouped by modification date, compact source/folder controls, expandable filename search, and selection on long-press. Backup and drive screens use the same dark theme. The release uses versionCode 2 and the existing release certificate, so it can update version 0.1.0 signed with that certificate without uninstalling.

## Version 0.3.0 — status, collections, timeline

Per-photo drive status with historical verification dates, content-based matching for externally copied files, Back up missing photos only, Collections, Favorites, custom albums, and a draggable date rail. See [VERSION-0.3.md](VERSION-0.3.md) for behavior and limitations. People/face grouping is not included yet.

## Version 0.4.0 — delete and review

Phone Trash/permanent deletion with Android confirmation; permanent deletion of selected drive files; and a reconnect-aware review for recorded backups whose phone originals are trashed or missing. Keep choices persist. Deletion never cascades automatically. See [VERSION-0.4.md](VERSION-0.4.md).

## Version 0.6.0 — permissions setup

First launch (and the first upgrade to this version) guides photo/video, original metadata, notifications, Android media-management access, and USB-folder permission. Enable media management to avoid repeated Android operation prompts where supported. Your app confirmation remains once per selected batch. See [VERSION-0.5.md](VERSION-0.5.md).
