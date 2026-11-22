# Device acceptance tests

Use disposable samples and keep originals elsewhere until these pass on your phone.

1. **Permissions:** deny access, grant selected items, grant full access, revoke permission in Settings. Gallery should reflect accessible items after returning to the app; no inaccessible items should be offered for backup. Original metadata permission denial should explain why backup cannot proceed.
2. **Gallery:** browse photos and videos; filter folders/types; search filenames; zoom/reset a photo; play/pause/seek a video. Lock the phone during playback: playback should pause. Unsupported formats must not be mistaken for deleted files.
3. **Drive selection:** add two USB folders, browse one, set the other as destination. Confirm the transfer dialog names the destination and browsing never changes it. Disconnect the selected drive and refresh; reconnect and refresh again.
4. **Copy fidelity:** back up JPG with EXIF, HEIC if supported, PNG, MP4, a zero-byte sample, and a file larger than 4 GB on a filesystem supporting it. Compare SHA-256 on a computer against originals. Check originals are untouched.
5. **Incremental:** rerun the same selection. Completed copies should be verified and skipped without growing the file count. Change source bytes, rerun, and ensure the new version is copied without overwriting the old copy.
6. **Corruption:** alter a completed USB file externally, then rerun backup. The app must not count that altered file as verified. It should create a new correct copy and preserve the altered one.
7. **Interruptions:** unplug the USB drive during copying; cancel; force-stop the app; reconnect/relaunch and reselect the same source files. Verified files remain intact. Partial files restart and are not displayed as gallery media.
8. **Storage errors:** try a read-only or full drive. Progress must stop with an error; no failed file may be marked verified. A partially successful batch must report its verified count.
9. **Restore:** select USB media and restore. Verify SHA-256 of restored originals. Repeat: no additional copy while the earlier verified restore exists. Delete a restored file and repeat: restore should recreate it.
10. **Large/long session:** try thousands of files, rotation, returning from another app, screen off, notification cancellation, Android service timeout. Monitor memory and responsiveness. The app must never silently continue to report a completed full batch after interruption.
11. **Metadata:** compare EXIF including location for original and backup; verify denial of ACCESS_MEDIA_LOCATION does not silently produce a redacted backup.
12. **No destructive sync:** delete a phone sample after verified backup. The USB file must remain.

## Automated checks

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```

The JVM suite covers the streaming integrity primitive, cancellation, simulated full-drive errors, known SHA-256 vectors, corruption detection, and name sanitization. It does not substitute for document-provider and physical USB testing.

## Version 0.3 acceptance additions

- Copy a photo onto the drive outside the app and rename it. Check: it must be recognized by content and skipped during backup.
- Repeat Back up missing only: expect zero new copies when all source content already exists.
- Edit a verified source and check twice: it should remain Changed until the new version is backed up.
- Switch to a second empty drive: the first drive's badges must not carry across. Switch back: its history should still exist.
- Disconnect a drive: retain history, show offline/last verified, never claim fresh verification.
- Create an album, add favorites, restart the app, and check persistence. Source files must not be duplicated or moved.
- Populate multiple months/years; drag the right date rail to both ends, then repeat under folder/video/search filters.

## Version 0.4 deletion acceptance

Use disposable fixtures only. Cancel both app and Android deletion prompts and verify bytes remain. Trash a backed-up phone file while the drive is disconnected, reconnect, and review it. Keep it, restart, and confirm Show kept copies still lists it. Confirm permanent deletion removes only the selected drive file and invalidates its status. Restore a phone original before confirming drive deletion: the reviewed deletion must stop. Revoke photo permissions: inaccessible originals must not be inferred deleted. Change drive-file bytes after recording it: reviewed deletion must stop. Test two source duplicates sharing one target: while either source is active, the automatic review must not propose deleting their shared file. Test a read-only drive, unplug during a batch, cancel, and restart; unfinished deletes must never retry automatically. Check both direct drive deletion and Android permanent phone deletion.
