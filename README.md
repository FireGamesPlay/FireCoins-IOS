# Fire Coins V2

Private two-account points system with native iOS and Android clients, a shared API and the existing Telegram bot.

**Active development branch:** \`v2-development\`  
**Production baseline:** current Telegram bot + aiohttp Mini App API  
**Database constraint:** keep the existing \`/root/ScoringSystemBot/points.db\` and its schema intact.

## Product

Fire Coins is intentionally limited to two server-configured accounts:

- **Artemiy** — member account.
- **Father** — administrator account.

V2 replaces the V1 demo repository with a server-authoritative implementation. The UI keeps the original charcoal/bronze visual language and navigation structure.

### Features

- Invitation-only account activation with one-time server-issued invitation codes.
- Server-side balance and transaction history.
- Admin credit/debit operations and photo evidence for selected reasons.
- Dispute requests, approval/rejection and compensating transaction history.
- Withdrawal requests and admin decisions.
- Server-authoritative 50/50 roulette, rolling seven-day limit and transaction-backed round history.
- Persistent notes visible to both accounts, with Telegram notifications.
- Authenticated proof image downloads.
- Persistent mobile sessions with refresh-token rotation and logout revocation.
- Existing Telegram Mini App authentication remains supported.

## Repository layout

- \`App/\`, \`Core/\`, \`Features/\` — iOS SwiftUI app.
- \`Android/\` — native Kotlin + Jetpack Compose Android app.
- \`Backend/api/\` — backend modules and sidecar stores prepared for deployment.
- \`Backend/patches/\` — guarded, backup-first scripts to apply server changes.
- \`Backend/tests/\` — isolated Python tests.
- \`docs/FireCoins_V2_Architecture.md\` — architecture and rollout requirements.
- \`.github/workflows/\` — backend tests, iOS simulator build, Android APK build and existing signed IPA workflow.

## Invitation security

The invitation strings once embedded in the V1 prototype were published in the repository and must be considered compromised. They have been removed from the V2 application source and must never be reused.

V2 invitations are generated only on the server. The server stores salted password hashes, binds each code to one role and accepts it once. Codes are not committed to GitHub or embedded in iOS/Android binaries. The bootstrap command prints new codes once on the VPS; keep them private.

## Backend and database safety

The deployed backend is Python \`aiohttp\`. The Telegram bot and API use the same SQLite file:

\`/root/ScoringSystemBot/points.db\`

V2 intentionally does not introduce a new business database or change the SQLite schema. Mobile authentication and shared note history use separate private files under \`.private/\`, outside the SQLite database. The business database remains the authority for balances, transactions and withdrawals.

Do not run deployment patches against production until:
1. the bot token has been rotated if the previously hardcoded token was still active;
2. a verified backup and integrity check of \`points.db\` exist;
3. backend tests and staging checks pass;
4. the current service environment has been checked without exposing secrets;
5. an operator has approved the production switch.

The patch scripts are not self-deploying and do not restart services. They create backups and modify source files only. Production changes must be carried out as a separate, reviewed deployment.

## iOS build

Open \`FireCoins.xcodeproj\` in Xcode or use the GitHub Actions iOS build. The project targets iOS 17+. Mobile credentials are stored in Keychain; the local app role is not used as server authorization.

The existing \`.github/workflows/build-ipa.yml\` remains available for signed IPA distribution. Its Apple signing credentials belong in GitHub Actions Secrets, never in the repository or chat.

## Android build

Open the \`Android/\` directory in Android Studio. The Android app uses Kotlin, Jetpack Compose and encrypted session storage backed by Android Keystore. The GitHub Actions workflow builds a debug APK and uploads it as an artifact.

The debug APK is for installation/testing. A production release needs a separately managed signing key and an explicit release workflow.

## Testing

- Backend tests: \`python -m unittest discover -s Backend/tests -v\` with \`PYTHONPATH=Backend\`.
- Backend syntax: \`python -m compileall -q Backend\`.
- iOS: GitHub Actions builds the \`FireCoins\` scheme for the iOS Simulator.
- Android: GitHub Actions runs \`assembleDebug\` and uploads the APK.

A green CI run proves the code in that commit compiles/tests in the runner; it does not prove production API connectivity or authorize deployment.

## Security notes

- Never commit bot tokens, new invitation codes, session tokens, signing keys or private certificates.
- The Telegram bot token must be read from the \`TG_BOT_TOKEN\` service environment variable.
- Mobile roles and balances are derived from the server, not client-supplied fields.
- The API continues to accept verified Telegram \`X-Telegram-Init-Data\` requests.
- Do not share \`.env\`, process environments, credentials or private auth-store files.
