# DualVault 0.6.0

## Private vault

Open Collections → Private vault and create a secret password of at least 10 characters. Select phone media → Move to private vault to import. The app encrypts and verifies every copy before requesting permanent removal of the selected phone originals. If encryption or removal fails, originals are kept; successfully encrypted copies may already be present. Existing USB/cloud/other copies remain separate.

Files and catalog metadata use AES-256-GCM. A random master key is wrapped with a password-derived key (PBKDF2-HMAC-SHA256, random salt, 600,000 iterations). Media uses authenticated 64 KiB frames and an authenticated end marker, supporting large files without decrypting entire videos in memory during transfer. The vault locks on backgrounding or closing; its windows block screenshots. Photos and videos up to 64 MiB can be previewed from memory. Larger files must be restored to the phone to view. Restore verifies the output before publishing it to the gallery and keeps the vault copy.

There is no password recovery or vault backup/export in this version. Uninstalling or clearing app data destroys the local vault. Keep your password safe. Vault permanent deletion bypasses Recently Deleted. This is a preview implementation, not an independently audited secure-storage product.

## Recently Deleted

Collections → Recently deleted lists accessible MediaStore phone Trash items, with selection, restore and permanent deletion. Android controls retention and accessible items depend on photo permissions. This is not a recovery tool for already permanently deleted files. USB copies remain independent.

## First-run permission setup

Includes the 0.5 setup flow: full photo/video access, original metadata, optional media-management access, notifications and USB folder selection. The app still confirms destructive actions. Android may ask again if special access is not granted or has been revoked. See [Android MediaStore documentation](https://developer.android.com/reference/android/provider/MediaStore).

## Validation

20 unit tests passed; debug lint has zero errors. Signed release uses the existing signing certificate. See VALIDATION.md for device-testing scope. Install the release APK over the previous signed release; do not uninstall to upgrade.
