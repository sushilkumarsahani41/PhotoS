# Version 0.5.0 — first-run permissions

On first launch, DualVault opens a setup checklist for:

1. Full photo/video access and original metadata access, needed for complete gallery, original-byte backups, and reliable deleted-phone-file review.
2. Android **Manage media** special access, to reduce repeated Android prompts for supported media operations.
3. Transfer notifications.
4. A user-selected USB drive folder. Additional drives still need their own folder grants.

Tap **Grant photo permissions** and choose **Allow all photos and videos**. After the runtime requests, if full media access was granted, the app opens Android's media-management settings. Enable its Allow switch, return, and confirm the checklist reads **Media management enabled**. The app reads actual OS state when returning; opening Settings alone does not count as approval.

You can continue with missing permissions and reopen setup through **Photos → ⋮ → Permissions & setup**. If a permission was denied permanently, use Open app settings. Setup is also shown once after updating from versions before 0.5.0, without clearing backup history.

The app continues to use Android's MediaStore Trash/Delete request APIs. With the appropriate read permissions and Manage media enabled, Android can process those requests without its repeated per-operation prompt. DualVault keeps its own action confirmation, once for the selected batch (up to 500 phone files), and always confirms permanent USB deletion. If special access is denied/revoked or the device adds extra restrictions, Android may still prompt. USB folder permissions are independent of Manage media and remain scoped to folders you select. No MANAGE_EXTERNAL_STORAGE/all-files-access permission is requested.

Official Android reference: https://developer.android.com/training/data-storage/shared/media#media-management-permission

Version code 5; same release signing key. Install over an earlier signed release without uninstalling. Media-management settings are user-controlled and cannot be silently granted by the app.
