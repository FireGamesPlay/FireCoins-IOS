# Fire Coins V2 — Architecture

**Status:** implementation specification  
**Branch:** `v2-development`  
**Baseline:** current Fire Coins iOS V1 + deployed `ScoringSystemBot/webapp/api`  
**Primary constraint:** preserve the existing `/root/ScoringSystemBot/points.db` file and all existing rows. No database recreation, destructive migration, table alteration, or bot downtime as part of the initial rollout.

---

## 1. V2 objectives

V2 turns the V1 SwiftUI prototype into a real, synchronized two-user application and adds a native Android client.

- Two accounts only: `artemiy` and `father`.
- Invitation-only activation. Each invitation is generated on the server, belongs to one role, and can be redeemed once.
- iOS and Android use the same HTTPS API and the same existing SQLite database as the Telegram bot.
- Server is authoritative for identity, roles, balances, transaction outcomes, roulette results, disputes, withdrawals and proof ownership.
- Existing SwiftUI visual system and screen composition are retained; the local in-memory repository is replaced by a remote implementation.
- Android is a native Kotlin + Jetpack Compose application with visual parity, not a WebView wrapper.
- Telegram Mini App authentication remains supported for the existing web client.

## 2. System overview

```text
                  ┌───────────────────────────┐
                  │       Existing bot        │
                  │       Bot.py               │
                  └─────────────┬─────────────┘
                                │
                                ▼
                     ┌──────────────────────┐
                     │ Existing points.db  │
                     │ balance              │
                     │ transactions         │
                     │ withdrawals          │
                     └──────────┬───────────┘
                                ▲
                                │ same file; no copy
                 ┌──────────────┴──────────────┐
                 │       aiohttp API           │
                 │ Telegram initData auth      │
                 │ Mobile bearer sessions      │
                 │ Role and domain validation  │
                 │ Transactions / proof / notes│
                 └──────────┬───────────┬──────┘
                            │           │
                 HTTPS      │           │ HTTPS
             ┌──────────────┘           └──────────────┐
             ▼                                         ▼
    ┌─────────────────────┐                 ┌─────────────────────┐
    │ iOS — SwiftUI       │                 │ Android — Kotlin    │
    │ Keychain            │                 │ Keystore-backed     │
    │ URLSession          │                 │ encrypted storage   │
    │ Remote repository   │                 │ Retrofit/OkHttp     │
    └─────────────────────┘                 └─────────────────────┘
```

The existing bot and API remain the only writers to the business database. Mobile clients never connect to SQLite and never calculate a trusted balance locally.

## 3. Existing production contract

The deployed service is Python `aiohttp`, not FastAPI. The current API already exposes:

| Method | Endpoint | Purpose |
|---|---|---|
| GET | `/health`, `/api/health` | health |
| GET | `/api/me` | authenticated user and public reasons |
| GET | `/api/dashboard` | dashboard aggregate |
| GET | `/api/balance` | balance |
| GET | `/api/history?period=week&limit=100` | transaction history |
| POST | `/api/add`, `/api/remove` | administrator transaction |
| POST | `/api/upload-proof` | upload proof image to Telegram |
| GET | `/api/proof/{transaction_id}` | retrieve proof image |
| GET | `/api/disputes/recent`, `/api/disputes/pending` | disputes |
| POST | `/api/dispute`, `/api/disputes` | open dispute |
| POST | `/api/disputes/{transaction_id}/resolve` | resolve dispute |
| GET | `/api/withdrawals` | withdrawal requests |
| POST | `/api/withdraw` | create withdrawal request |
| POST | `/api/withdrawals/{withdrawal_id}/resolve` | resolve withdrawal |
| GET | `/api/casino/status` | roulette quota |
| POST | `/api/casino` | play roulette |
| GET | `/api/notes` | synchronized private note history |
| POST | `/api/note` | send and persist a note |

All business endpoints currently authenticate through `X-Telegram-Init-Data`; the dev-auth path is disabled in the inspected production process. V2 adds bearer-session authentication without removing or changing Telegram authentication.

### Current database schema (must be preserved)

- `balance(user_id, balance)`
- `transactions(id, from_id, to_id, type, amount, reason, timestamp, disputed, proof_file_id)`
- `withdrawals(id, user_id, amount, timestamp, status)`

The API configuration resolves `POINTS_DB_PATH` to `/root/ScoringSystemBot/points.db`; the bot also opens `points.db` from the project root. This exact file is shared. V2 must verify the resolved path before startup and must never point either service at a newly created database.

## 4. Mobile authentication

### 4.1 Invitation lifecycle

- Invitation codes are created only by a server-side bootstrap command.
- The two codes are never hardcoded into Swift/Kotlin, committed to Git, written to build logs, or included in a release artifact.
- Codes are high entropy, role-bound, and one-time use.
- The currently published V1 local invitation constants are compromised and must not be reused.
- The bootstrap command prompts for/generated codes once and writes only salted password hashes to a private server-side state file.
- The invitation store lives outside the Git checkout and outside `points.db`, under a directory with mode `0700`; files use mode `0600`.
- The bootstrap process refuses to overwrite an existing invitation store unless the operator explicitly uses a documented rotation operation.

### 4.2 Exchange and sessions

| Method | Endpoint | Authentication | Request | Result |
|---|---|---|---|---|
| POST | `/api/mobile/auth` | invite code | `{ "invitation_code": "…" }` | user + access token + refresh token |
| POST | `/api/mobile/refresh` | refresh token | `{ "refresh_token": "…" }` | rotated access + refresh tokens |
| POST | `/api/mobile/logout` | refresh token | `{ "refresh_token": "…" }` | session revoked |

- Access token lifetime: 15 minutes.
- Refresh token lifetime: 90 days, rotated on every refresh.
- Session tokens are cryptographically random opaque values; only SHA-256 hashes are stored server-side.
- Invitation code hashes use a per-code random salt and a deliberately expensive password hash.
- A session's user ID is revalidated against server configuration on every authenticated request. The client cannot submit a role or user ID to select its identity.
- Refresh rotation invalidates the previous refresh token. Logout revokes the session.
- Expired sessions are pruned during auth-store operations.
- The auth store uses atomic file replacement and an inter-process lock; no new SQLite tables are required.
- Failed invitation attempts are rate-limited. Do not trust arbitrary `X-Forwarded-For` values until the reverse-proxy chain is explicitly configured.
- The server never returns invitation hashes or token hashes.

### 4.3 Request authentication precedence

1. Valid `X-Telegram-Init-Data` continues to authenticate Telegram Mini App requests.
2. `Authorization: Bearer <access_token>` authenticates mobile requests.
3. Development auth remains disabled in production and is not accepted as a mobile login mechanism.

## 5. Shared API contracts

All JSON responses use UTF-8 and the existing envelope shape:

- Success: `{ "ok": true, ... }`
- Error: `{ "ok": false, "error": { "code": "...", "message": "..." } }`

Mobile DTOs map backend integer transaction IDs to stable string identifiers in the domain layer. Amounts are integers (FC); ruble equivalent remains server-provided and is not used to calculate authorization or balance.

### Authentication and identity

`GET /api/me` returns the server role and display identity after authentication. Clients must discard any locally cached role when logout occurs or when the server returns 401/403.

### Dashboard and history

Dashboard and balance are fetched from the server. History uses the existing day/week/month/all period vocabulary. Pull-to-refresh and post-mutation refresh always re-read server state.

### Add/remove points

Father-only. The server validates positive integer amount, reason length and required photo proof. Existing administrator behavior that allows a deduction to make the balance negative is retained unless a separately approved product change says otherwise.

### Proof images

- iOS/Android upload image bytes as multipart field `photo`; `reason` is a text field.
- The API uploads proof to Telegram and returns `proof_file_id`.
- The transaction write sends that ID as `proof_file_id`.
- Proof download is authenticated and remains served through the existing Telegram client.
- File size and MIME checks remain enforced server-side.

### Disputes

- Artemiy can dispute only one of the ten most recent eligible transactions.
- Father can approve/reject.
- Approval must not erase the original historical transaction. It creates an opposite compensating transaction and restores the correct balance effect.
- Rejected/approved state must remain visible in both mobile clients.
- Existing bot callback behavior must be inspected and brought into agreement before enabling the new dispute semantics. A migration must not rewrite historical rows.

### Withdrawals

- Artemiy can create a withdrawal request; the request itself does not immediately deduct balance.
- Father can approve/reject once.
- Approval rechecks available balance inside `BEGIN IMMEDIATE`, deducts and creates the corresponding transaction atomically.
- Duplicate resolution attempts return 409.
- Pending requests are displayed on both devices.

### Roulette

- Artemiy only.
- Two spins in a rolling seven-day period.
- Bet is positive and cannot exceed available balance.
- Outcome is generated on the server; client animation is cosmetic and cannot influence the outcome.
- The server response includes win/loss, bet, payout, balance and current quota.
- The client refreshes history and balance after a round.

### Notes

The existing `POST /api/note` only sends a Telegram message and does not persist a note feed. V2 adds a small private JSON sidecar store outside `points.db` for durable two-device note history, while keeping Telegram delivery as a notification. This avoids altering the production business schema. Notes are bounded in size, stored with sender/recipient IDs and timestamps, and returned only to the two permitted users.

## 6. Dispute state and notes without changing points.db

The existing `disputed` column is declared BOOLEAN, but SQLite stores integer values and accepts values beyond 0/1 without a schema change. V2 uses this existing field as a compact state:

| Stored value | Meaning |
|---:|---|
| 0 | no dispute |
| 1 | pending |
| 2 | approved |
| 3 | rejected |

- Existing rows already containing 0/1 remain valid.
- Approving a dispute updates the original row to 2, retains it in history, and inserts an opposite compensating transaction in the same `BEGIN IMMEDIATE` transaction.
- Rejecting a dispute updates the row to 3. It is no longer eligible for another dispute, matching V1 domain behavior.
- Both the API and Telegram bot must implement the same transition and balance correction. A second callback/request for an already resolved dispute returns a conflict/no-op rather than applying a second balance change.

Notes need durable shared history, but the existing business schema has no notes table. V2 stores notes in a private JSON sidecar at `.private/firecoins_workflow.json`, outside `points.db`. The API and bot share the same module/path. The sidecar is not a replacement for `points.db`; it uses atomic file replacement, an inter-process lock and restrictive permissions. The API exposes `GET /api/notes`; `POST /api/note` sends the Telegram notification and persists the note. The bot also persists notes after successful Telegram delivery.

No existing business rows are bulk-read, rewritten, or migrated during deployment.

## 7. iOS implementation

- Preserve V1 SwiftUI view layout, typography, colors, spacing and components.
- Replace `InMemoryFireCoinsRepository` as the production dependency with `RemoteFireCoinsRepository`.
- Introduce `APIClient`, typed API DTOs, `SessionManager` and `KeychainTokenStore`.
- Invitation screen calls `/api/mobile/auth`; only a successful server response opens the app.
- Store tokens in Keychain; do not use `@AppStorage` for credentials or role authorization.
- On app launch, refresh the access token, then load `/api/me` and dashboard.
- A 401 triggers a single refresh attempt; failed refresh clears the session and returns to invitation screen.
- Map API/network errors to existing user-readable error surfaces.
- Use server integer IDs, UTC timestamps and server values for display.
- Proof images are compressed to a safe size before upload; server still enforces final byte limit.
- The in-memory repository remains only for previews/tests, never for production.

## 8. Android implementation

Native Android project:

- Kotlin, Jetpack Compose, Material 3 with a custom Fire Coins dark theme.
- ViewModel + repository + domain/data separation.
- Retrofit/OkHttp API client with bearer interceptor and one coordinated refresh authenticator.
- EncryptedSharedPreferences or AndroidX Security backed by Android Keystore for tokens.
- Coil for image rendering; Activity Result API for proof photo selection.
- Same server role gating and same API endpoints as iOS.
- Same screen set: invitation, home, history, roulette/admin, wallet, profile/notes.
- Build variants: debug and release; release APK/AAB signed only with user-controlled CI secrets.
- No bot token, invitation code, session token, server secret or signing key in source.

## 9. Compatibility, rollout and rollback

### Compatibility rules

- Keep Telegram initData auth and all existing web routes functioning.
- Do not change the existing bot command contract.
- Do not change SQLite schema in the first V2 rollout.
- Do not modify or regenerate `points.db`.
- Do not expose port 8080 publicly as a new requirement; mobile clients use the existing HTTPS public endpoint.
- The API can be deployed before mobile clients because bearer auth is additive.

### Rollout gates

1. Capture and verify a backup of `points.db` while the service is in a consistent state.
2. Record the current SHA-256 and database integrity result; do not print row contents.
3. Inspect the bot's transaction/dispute/withdrawal handlers and reconcile behavior.
4. Install code and Python dependencies without restarting the running process.
5. Run unit tests and an offline API/auth-store test suite against a temporary test directory/database only.
6. Initialize the private invitation store and securely deliver the two generated codes to their owners.
7. Start a staging API process on a loopback-only alternate port with a copy of the code and a separate temporary DB; test both mobile roles.
8. Verify existing Telegram Mini App endpoints and bot commands.
9. Schedule the production switch only after all checks pass. Restart only the API service, not the bot, and verify health plus read-only balance/history checks.
10. Enable iOS and Android release builds after production auth works.

### Rollback

- Stop the new API process and restore the previous API source/config from a timestamped backup.
- Keep `points.db` untouched; no restore of the database is required for a code-only rollback.
- Revoke/rotate mobile sessions by replacing the mobile auth sidecar if compromise is suspected.
- Keep bot and web app online throughout code preparation and staging.

## 10. Security requirements

- HTTPS only for mobile APIs.
- Strict server-side role checks for every protected mutation.
- No role or balance trust in client payloads.
- No hardcoded invitations or tokens.
- No logs containing invitation codes, bearer tokens, refresh tokens, Telegram initData, uploaded image bytes or private note contents.
- Constant-time secret comparisons where applicable.
- Atomic sidecar writes, lock file, restrictive permissions.
- Bounded request bodies and bounded strings.
- JSON input type validation and explicit integer parsing.
- Idempotency keys for retryable money-changing client requests are a follow-up hardening item; until implemented, clients must not automatically replay a mutation whose result is unknown.
- Audit logging records action type, actor ID, resource ID, outcome and timestamp without credentials.

## 11. Known gaps to close during implementation

1. Inspect `Bot.py` callbacks before changing dispute resolution, because the bot may currently mutate the same transaction rows independently.
2. Add persistent notes feed; current service only sends Telegram notifications.
3. Add casino-round history DTO; current endpoint exposes quota but not a structured rounds list.
4. Preserve transaction history during dispute approval and display final dispute outcomes without schema changes.
5. Add API contract tests for every iOS/Android operation.
6. Confirm production TLS/proxy routing for `https://app.firegames-it.tech` and confirm direct port 8080 is not relied on by another deployment before considering firewall changes.

---

## Acceptance criteria

- Existing bot and Mini App continue to authenticate and operate.
- Existing `points.db` is byte-for-byte unchanged by staging and no schema migration is run.
- Both mobile clients authenticate only using one-time server invitations.
- The two clients show the same server balance, history, pending requests, dispute states, notes and casino quota after refresh.
- Server rejects unauthorized roles even if the UI is bypassed.
- No local demo balance or local role can reach production.
- iOS and Android builds are reproducible from documented GitHub Actions workflows.
- Production rollout is performed only after a tested backup, staging verification and explicit operator approval.
