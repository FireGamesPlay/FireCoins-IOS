# Fire Coins V2 — Backend deployment package

This directory contains additive API modules, guarded patch scripts and isolated tests for the existing Fire Coins VPS.

**These files do not deploy themselves and do not restart services.** The existing bot token appeared in a development terminal transcript. The owner has chosen to retain it for now; that leaves residual credential-exposure risk. This rollout preserves the existing token without repeating it in source code or chat.

## Production invariants

- The existing database remains /root/ScoringSystemBot/points.db.
- No table is created, altered, dropped, renamed or migrated by these patches.
- The Telegram bot and API continue to use the same business database file.
- Mobile auth and note history are stored in private sidecar files under /root/ScoringSystemBot/.private/.
- The patch scripts create backups under /root/ScoringSystemBot/.backups/.
- Do not copy an example database into the project and do not point POINTS_DB_PATH at a new file.

## Prerequisites

1. Verify that /root/ScoringSystemBot/webapp/.env contains a non-empty TG_BOT_TOKEN without printing or copying its value. The current production file already contains this setting.
2. Before exposing the file to the bot service, restrict it to root: `chmod 600 /root/ScoringSystemBot/webapp/.env`. The current file is more permissive than necessary.
3. The API loads webapp/.env through api/config.py. The current tgbot.service does not load that file, so after reviewing the unit create a systemd drop-in containing:
   
   ~~~ini
   [Service]
   EnvironmentFile=/root/ScoringSystemBot/webapp/.env
   ~~~

   This keeps the existing token unchanged while allowing Bot.py to read it from the process environment. Do not print the EnvironmentFile contents or systemd environment values.
4. Confirm that POINTS_DB_PATH still resolves to /root/ScoringSystemBot/points.db.
5. Ensure a database backup and PRAGMA integrity_check have been completed separately.
6. Do not revoke or rotate the token as part of this rollout unless the owner separately chooses to do so. The decision to retain it does not eliminate the residual exposure risk.

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
