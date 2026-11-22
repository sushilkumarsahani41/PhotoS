# Version 0.3.0

## Backup status and avoiding duplicate copies

Choose the backup drive folder on **Backup**. Phone gallery badges refer to that exact folder, independently of other drives/folders:

- **Backed up ✓**: matching bytes were verified during a previous check or transfer.
- **Not backed up**: no matching content was found during the last completed check for that item.
- **Changed**: the phone item differs from the last verified version, or its size/modification time has changed since that verification. A fresh content check can resolve metadata-only changes.
- **Not checked**: no usable result yet for this source item and selected drive folder.
- **Last verified ✓** with an offline banner: historical verification only; current availability is unknown.

Open a photo, tap **Info**, and read the last checked/last verified timestamps. Even for an attached drive, badges are labelled as last-checked status. Press **Check** or **Backup → Check existing files on drive** to update them. Disconnecting/permission loss does not erase previous verification history or claim the copy was deleted.

**Back up missing photos only** first reads the selected drive folder recursively, hashes its media, and compares original phone bytes. Exact content matches are skipped even if the filename differs, another app made the copy, or multiple phone items have identical contents. Nonmatching files are copied and verified. Source versions and previous drive files are preserved. Nothing is deleted as part of synchronization.

Checks and backups require original-media metadata access. The current implementation hashes the entire selected drive's media every time; large libraries can take significant time and drive I/O, but matching files are not written again. The check runs with transfer progress and cancellation. It covers accessible local photos/videos, not Google Photos cloud-only media. Select the actual physical USB folder in Android's picker; choosing a phone folder is not an independent physical backup.

## Collections

The new **Collections** tab shows Favorites, Videos, custom albums, and folders for the selected source. Tap the heart in the full-screen viewer to favorite/unfavorite an item. Long-press/select photos, tap **Album**, then create an album or add to an existing one. Albums store references, not extra file copies; moving/renaming source files can invalidate those references. Counts refer to currently accessible items on the chosen source. Album removal/renaming and automatic People/face grouping are not implemented in this version.

## Right-edge timeline

When the filtered gallery has multiple dates, drag or tap the rail at its right edge to jump through dates. A date bubble appears while dragging. The rail follows the current source, folder, media type, search, and collection filters. Phone dates use MediaStore capture dates when available and modification time otherwise. Drive-folder dates currently use file modification time; the app does not yet extract capture dates from all USB file formats.

## Update

Version code 3, signed with the same release key as 0.1.0 and 0.2.0. Install over an earlier signed release without uninstalling. The journal schema upgrades in place and preserves legacy copy records. Existing old backups become visible in the new status catalog after the first check. No signing secrets are included in the source ZIP.
