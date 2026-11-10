# Fire Coins Android

Native Android client built with Kotlin and Jetpack Compose.

## Open and build

1. Open this `Android/` directory in Android Studio.
2. Use JDK 17 for Gradle.
3. Let Android Studio install the Android SDK platform/build tools required by `app/build.gradle.kts`.
4. Select the app configuration and run it on an Android 8.0+ device or emulator.

The GitHub Actions workflow `.github/workflows/android-build.yml` builds a debug APK. The APK is uploaded as the `fire-coins-android-debug` artifact on a successful run.

## Authentication

The app uses the same one-time invitation exchange as the iOS client:
- `POST /api/mobile/auth`
- `POST /api/mobile/refresh`
- `POST /api/mobile/logout`

Session tokens are stored using encrypted preferences backed by Android Keystore. No invitation code is bundled in the application.

## API

The API base URL is configured in `data/ApiClient.kt` and currently points to the Fire Coins HTTPS endpoint. Do not point release builds to a plain HTTP or development endpoint.

## Signed release APK

The manual workflow `.github/workflows/android-release.yml` produces a signed release APK. It requires these repository Actions secrets:

- `ANDROID_KEYSTORE_BASE64` — Base64 contents of the private JKS/PKCS12 keystore.
- `ANDROID_KEYSTORE_PASSWORD` — keystore password.
- `ANDROID_KEY_ALIAS` — signing key alias.
- `ANDROID_KEY_PASSWORD` — signing key password.

Generate the keystore once on a trusted local Windows machine (not in the repository). Run this in PowerShell; `keytool` prompts for the passwords without putting them in command history:

```powershell
$keyStorePath = Join-Path $env:USERPROFILE "firecoins-release.jks"
keytool -genkeypair -v -keystore $keyStorePath -alias firecoins -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=Fire Coins"
[Convert]::ToBase64String([IO.File]::ReadAllBytes($keyStorePath)) | Set-Clipboard
```

When prompted for the key password, press Enter to reuse the keystore password. Then add the four secrets above under GitHub repository Settings → Secrets and variables → Actions. Paste the Base64 value from the clipboard only into the `ANDROID_KEYSTORE_BASE64` secret. Do not commit or share the keystore, its Base64 contents, or passwords.

After configuring the secrets, open Actions → Build signed Android APK → Run workflow. The artifact is named `fire-coins-android-release`.

Keep this keystore permanently backed up in a private secure location. Future app updates must use the same signing key.
