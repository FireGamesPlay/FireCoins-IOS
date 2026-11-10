# Fire Coins V2 — Backend deployment package

This directory contains additive API modules, guarded patch scripts and isolated tests for the existing Fire Coins VPS.

**Do not run the patch scripts on production until the bot token has been rotated and the service environment has been checked.** These files do not deploy themselves and do not restart services.

## Production invariants

- The existing database remains /root/ScoringSystemBot/points.db.
- No table is created, altered, dropped, renamed or migrated by these patches.
- The Telegram bot and API continue to use the same business database file.
- Mobile auth and note history are stored in private sidecar files under /root/ScoringSystemBot/.private/.
- The patch scripts create backups under /root/ScoringSystemBot/.backups/.
- Do not copy an example database into the project and do not point POINTS_DB_PATH at a new file.

## Prerequisites

1. Revoke the Telegram bot token that was exposed in development, if it has not already been revoked.
2. Create a replacement token in BotFather. Do not send it in chat, paste it into Git, or put it in a command that enters shell history.
3. Configure TG_BOT_TOKEN securely for both the API process and tgbot.service. The API currently loads webapp/.env; the bot process must receive the same variable through its systemd environment. Verify the actual service definitions before choosing an EnvironmentFile or systemd drop-in.
4. Confirm that POINTS_DB_PATH still resolves to /root/ScoringSystemBot/points.db.
5. Ensure a database backup and PRAGMA integrity_check have been completed separately.

## Get the V2 files

From the VPS, clone the development branch to a temporary directory (not over the running application):

~~~bash
cd /tmp
git clone --depth 1 --branch v2-development https://github.com/FireGamesPlay/FireCoins-IOS.git FireCoins-IOS-v2
~~~

Copy only the backend files required by the patches:

~~~bash
cd /root/ScoringSystemBot
install -d -m 700 .private .backups

install -m 644 /tmp/FireCoins-IOS-v2/Backend/api/mobile_auth.py webapp/api/mobile_auth.py
install -m 644 /tmp/FireCoins-IOS-v2/Backend/api/workflow_store.py webapp/api/workflow_store.py
~~~

The Backend/api/__init__.py file is not required if the live webapp/api package already exists.

## Offline checks before patching

Run isolated tests on the checkout, not against the production database:

~~~bash
cd /tmp/FireCoins-IOS-v2
PYTHONPATH=Backend python3 -m compileall -q Backend
PYTHONPATH=Backend python3 -m unittest discover -s Backend/tests -v
~~~

Then check the actual config and service environment without printing values:
- POINTS_DB_PATH must remain the existing file.
- Both API and bot must receive the new TG_BOT_TOKEN.
- Never paste .env, systemctl show -p Environment, /proc/*/environ, or private key material into chat.

## Apply source patches — no restart

Only after the prerequisites pass:

~~~bash
cd /root/ScoringSystemBot/webapp
/root/ScoringSystemBot/webapp/venv/bin/python3 /tmp/FireCoins-IOS-v2/Backend/patches/apply_mobile_auth_patch.py
/root/ScoringSystemBot/webapp/venv/bin/python3 /tmp/FireCoins-IOS-v2/Backend/patches/apply_workflow_disputes_patch.py
~~~

Both scripts:
- verify exact source preconditions;
- create timestamped, permission-restricted backups first;
- write source files atomically;
- do not modify points.db;
- do not restart the API or Telegram bot.

If a precondition fails, stop and inspect the diff. Do not force the patch onto a different version of the backend.

Validate syntax:

~~~bash
cd /root/ScoringSystemBot
python3 -m py_compile Bot.py webapp/api/config.py webapp/api/server.py webapp/api/services.py webapp/api/telegram_auth.py webapp/api/mobile_auth.py webapp/api/workflow_store.py
~~~

The two services should not be restarted until source diffs, the actual systemd token configuration, a staging test against a temporary copy of the database, and the rollback plan have all been reviewed.

## Initialize one-time invitations

After the mobile auth source has been installed and reviewed, initialize the two role-bound invitations once:

~~~bash
cd /root/ScoringSystemBot/webapp
./venv/bin/python3 -m api.mobile_auth --bootstrap
~~~

The codes are printed once to the local VPS terminal. Save each privately and deliver it only to its intended account owner. Never put the output into GitHub, chat, shell scripts, logs or a shared terminal recording.

Do not run --rotate unless you intentionally want to invalidate all mobile sessions and replace both invitations.

## Rollback

- Do not restore or overwrite points.db for a code-only rollback.
- Restore the timestamped source backups created under /root/ScoringSystemBot/.backups/.
- Keep the private auth/workflow sidecars unless intentionally revoking mobile sessions or notes. Replacing the mobile auth sidecar invalidates all mobile sessions and invitation codes.
- Restart only after the exact systemd service units, token environment and rollback source have been checked.

This guide deliberately does not include production restart commands. A separate deployment review is required before switching the running API or bot to V2.
