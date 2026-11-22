# Signed release builds

The delivered `PhotoS-release.apk` is a release-mode APK signed with a new private RSA-3072 certificate. Signing establishes update identity; it does not remove the preview limitations described in README.md or constitute Play Store approval.

The signing key and credentials are stored separately in the sibling `DualVault-signing` folder, **not in this project or its source ZIP**. Back up that private folder securely. Never upload the keystore or credentials to GitHub.

With JDK 17 and the Android SDK configured, run from this folder:

```sh
python3 tools/build_release.py /absolute/path/to/DualVault-signing/signing-credentials.json
```

The helper supplies signing credentials via environment variables. Gradle produces `app/build/outputs/apk/release/app-release.apk`. Without signing environment variables, a plain `assembleRelease` produces an unsigned APK. The GitHub workflow continues to produce a debug APK; release secrets have not been uploaded to GitHub.

Increment `versionCode` for later versions, and keep using the same signing key for direct APK updates. If you later use Google Play App Signing, review its signing-key enrollment process before the first store release.

## Switching from the earlier debug APK

Android cannot update the debug installation with this different release certificate. Uninstall the debug version first if prompted, then install the release APK. Uninstalling resets local drive permissions and transfer history, so grant folder access again. Existing backup files are preserved. In version 0.3.0, use Check existing files on drive to recognize them by content and rebuild the status catalog.

## Vault preservation in 0.6

Upgrade signed releases in place. Uninstalling or clearing app data deletes the private vault; export needed files using Restore before uninstalling. Never publish the signing folder or credentials.
