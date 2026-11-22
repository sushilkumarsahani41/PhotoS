# Delivery validation

Date: 2026-11-15. Version: 0.1.0.

## Build and static checks

- Built with JDK 17, Gradle 8.11.1, Android SDK 35.
- `testDebugUnitTest`: **6 tests passed**, 0 failed.
- `lintDebug`: **0 errors**. Remaining warnings concern pinned older dependencies/target API and optional Kotlin extension style. These versions are deliberately pinned for this preview; this is not a claim of Play Store release readiness.
- `assembleDebug`: **passed**, installable debug APK produced.
- Gradle distribution SHA-256 is pinned in wrapper properties.

## Emulator checks

Used an isolated Android emulator reporting Android 17 / API 37. The app minimum is API 35, but Android 15 hardware was **not** tested here. The emulator's local Documents provider folder simulated the drive destination; this does **not** test USB hardware, power, cables, filesystem limits, or disconnect behavior.

Confirmed:

- Installation and launch.
- Photo/video permission flow and gallery showing generated PNG and MP4 fixtures.
- User-selected folder access persisted through Android's folder picker.
- Backup of both fixtures completed successfully.
- Independently computed `sha256sum` values matched originals and backup copies.
- Repeating the same two-file backup left exactly two destination files, with no duplicate copies.
- Restore into Pictures/Movies completed, with hashes matching originals.
- Corrupting a backup externally and rerunning produced a new matching copy while preserving the altered file.
- Full-screen photo viewer opened successfully.
- Latest rebuilt APK installed successfully after UI and backup-rule refinements.

Fixture hashes:

```
sample.png  896dacf56bded64797be85455ec115013992df759665c31c81f07bd59e8e71bc
sample.mp4  37beb7e20177099a0c034f42412b0f52a358aa37426175cfc044ef50d1d454d8
```

## Still requiring real-device verification

Physical USB removal/reconnection, read-only/full-drive recovery, files larger than 4 GB, service timeout, overnight operation, process death during provider writes, EXIF location preservation with real camera photos, codec diversity, and very large libraries. Run the checklist in TESTING.md before relying on this preview for valuable files. No changes were made to a connected physical phone.

## Signed release

Release assembly and release vital lint passed. The delivered release APK passed apksigner verification (v2, RSA-3072), ZIP alignment checks, and a non-debuggable manifest check. The new key is held outside the project; no release credentials are included in the source ZIP.

## Version 0.2.0 UI update

Debug unit tests (6/6), full debug lint (0 errors), and debug/release assembly passed after the black-theme redesign. An emulator accepted the new signed release over the prior signed release without uninstalling. Permission flow, two-item photo/video grid, date heading, selection menu, select-all, and backup navigation were exercised. The APK signature is checked against the previous release certificate before delivery. Existing physical-drive limitations still apply.

## Version 0.3.0

- Eleven JVM tests passed (integrity, metadata invalidation, missing/changed status rules, timeline header offsets and endpoint clamping).
- Debug lint passed with zero errors; release assembly and vital lint passed.
- Emulator database migration from schema 1 to schema 2 preserved an existing legacy copy record.
- A differently named pre-existing PNG on the selected folder was recognized by hash. First backup copied only the missing video; second backup reported zero copies and two skips.
- Changed source bytes were reported as Changed, including after a second check.
- A second empty drive folder reported both items missing without altering the first drive's records.
- Making the selected folder unavailable showed offline/saved history and Last verified for the unchanged photo/video copy. This simulates unavailable storage; it is not a physical USB disconnection test.
- Favorites and a two-item custom album persisted across an app update/restart.
- The right rail was dragged through twelve dated monthly fixtures; the date bubble was visually inspected and the oldest month became visible.
- Final release signature and ZIP alignment are verified during packaging; release certificate matches earlier versions.

Physical Android 15+ USB hardware, very large libraries and long-running transfers still require testing on your device. People/face grouping is not implemented.

## Version 0.4.0 — 2026-11-16

- Sixteen JVM tests passed; full debug lint completed with zero errors; signed release assembly and vital lint passed. New tests cover unknown access, empty queries under limited permission, active shared-source protection, and review eligibility.
- An emulator upgraded from the existing v0.3.0 APK after making two recorded backups. Schema 3 retained those copies and the review found the trashed original's backup.
- Cancelling Android's phone Trash prompt left the original available; approving the subsequent prompt removed it from the active phone gallery while its backup remained.
- Keep on drive persisted across app update/restart and was visible with Show kept copies.
- Cancelling permanent-drive confirmation retained the copy. Changing the backup's contents caused the confirmed deletion to stop with zero files deleted. Restoring the fixture bytes allowed the explicitly confirmed deletion to complete.
- The successful reviewed deletion removed only the selected backup and changed its status to missing; the other backup remained. Direct drive-gallery deletion was exercised separately and left its phone original available.
- Android's permanent phone-deletion prompt was exercised for the remaining video fixture.
- All destructive tests used generated fixtures inside an isolated emulator. Physical USB disconnects during deletion, OEM Trash implementations, and large batches still require disposable-file tests on the user's hardware.

## Version 0.6.0 — 2026-11-16

- 20 JVM tests passed, including password-derived key separation, randomized encryption, incorrect keys/purposes, tampering, multi-frame round-trip, truncated end marker and trailing-data rejection.
- Full debug lint: zero errors; debug and signed release assembly passed.
- Android emulator: created password-protected vault, rejected wrong password, moved a generated PNG, verified original removal, unlocked again, viewed private photo, restored it to the gallery, moved restored photo to Trash, and restored it through Recently Deleted.
- Pulled the restored file and compared SHA-256 with the original fixture: identical.
- Media-management-enabled operation completed without an extra Android per-file prompt. First-run permission onboarding was exercised during 0.5 development.
- Physical phone/USB testing and independent cryptographic review remain outstanding. Test actions used generated emulator fixtures only.
- Final signed build installed as an in-place update and retained the existing encrypted vault. Repeated import with the final original-file recheck succeeded for both PNG and MP4 fixtures. Restored MP4 SHA-256 also matched the original.
- The private video viewer initialized and released Media3 without a reported playback exception; automated UI inspection could not obtain an idle snapshot of that viewer, so visual playback remains a manual device check.
