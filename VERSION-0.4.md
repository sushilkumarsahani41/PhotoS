# Version 0.4.0 — delete and review backup copies

## Delete from the phone

Open a photo/video and tap **Delete this file**, or select items in the phone gallery and tap **Delete selected from phone**. The default is **Move to Trash**; Android asks for confirmation. You can choose **Permanently delete from phone instead of moving to Trash**, which also uses Android's confirmation. Up to 500 items can be requested at once.

Deleting from the phone never automatically deletes a drive copy. Trash retention/restoration is managed by Android and your device's gallery; DualVault does not yet have its own Trash browser.

## Review copies left on a backup drive

Connect the drive and open **Backup → Review phone deletions**, or **Drives → Review deleted phone photos**. This review refreshes when the app resumes, when it receives a USB connection event, and through Refresh. If Android mounts a drive slowly, use Refresh after the folder becomes available. The app does not launch background deletion or delete anything just because a drive was connected.

The review lists recorded copies whose linked phone items are now in Trash or missing. Each file offers:

- **Keep on drive:** save the choice and hide that item from the default review. It stays on the drive.
- **Delete permanently:** review a second confirmation, then remove that single drive file. This cannot be undone by DualVault.
- **Show kept copies:** view saved Keep choices and optionally review/delete them later.

“Not found on phone” can also mean a file was moved, reimported, or its MediaStore identity changed. It is not proof of deletion. Keep is the default. If a backup file is shared by multiple recorded phone items, the review excludes it while any of those phone items is still active or its state is unknown.

The app requires full photo and video access to infer phone-file absence for deletions made elsewhere. With selected-photo or denied permissions, it only considers confirmed deletion requests made through DualVault whose Trash state remains readable. An empty query under limited access is treated as unknown, even after an earlier confirmed deletion; unknown/inaccessible items are not treated as deleted. The notice on the review explains this restriction.

The review covers backups recorded in DualVault's catalog, including migrated earlier-version records and externally created copies recognized by a previous check. It cannot reconstruct the origin of arbitrary unrecorded drive files or recover history lost by uninstalling the app.

## Delete directly from a drive

Browse a connected drive, open or select files, and use Delete. These are permanent drive deletions with a separate confirmation. Phone files remain untouched. A directly selected drive file may be the only copy or serve multiple phone items; read the confirmation before deleting it.

Before deletion, the app checks the selected tree, file type, writable access, and SHA-256 content. A review deletion also rechecks linked phone states. Changed files or uncertain phone states are kept and reported as a stopped operation. Successful deletion invalidates all catalog references to that target so the phone gallery no longer labels that recorded copy as present. A subsequent backup can recreate a deleted drive copy if the source remains on the phone.

Deletion requests are not automatically retried after cancellation, disconnect, process death, or restart. A partially completed batch reports how many files were deleted. No other source, drive, or cloud library is included in the deletion action.

## Updates and limitations

Version code 4 uses the same release signing certificate. Install over the previous signed release without uninstalling. Journal schema 3 preserves earlier copy/status records and adds durable source-to-backup links and Keep choices. Signing keys remain outside the source archive.

Only an emulator document-provider folder was available for development tests; verify with disposable files on your real phone and USB drive first. There is no drive recycle bin, Google Photos deletion integration, or automatic face grouping in this version.
