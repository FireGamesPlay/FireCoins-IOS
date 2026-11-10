# Fire Coins Android

Native Android client built with Kotlin and Jetpack Compose.

## Open and build

1. Open this \`Android/\` directory in Android Studio.
2. Use JDK 17 for Gradle.
3. Let Android Studio install the Android SDK platform/build tools required by \`app/build.gradle.kts\`.
4. Select the app configuration and run it on an Android 8.0+ device or emulator.

The GitHub Actions workflow \`.github/workflows/android-build.yml\` builds a debug APK. The APK is uploaded as the \`fire-coins-android-debug\` artifact on a successful run.

## Authentication

The app uses the same one-time invitation exchange as the iOS client:
- \`POST /api/mobile/auth\`
- \`POST /api/mobile/refresh\`
- \`POST /api/mobile/logout\`

Session tokens are stored using encrypted preferences backed by Android Keystore. No invitation code is bundled in the application.

## API

The API base URL is configured in \`data/ApiClient.kt\` and currently points to the Fire Coins HTTPS endpoint. Do not point release builds to a plain HTTP or development endpoint.

## Release signing

The debug APK is not a signed production release. Keep the release keystore and passwords outside source control. A production signing workflow should use repository/environment secrets and publish a signed APK or AAB only after the backend rollout is approved.
