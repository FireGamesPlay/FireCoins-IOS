from __future__ import annotations

import os
import shutil
import tempfile
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path.cwd()
API = ROOT / "api"


def load_file(name: str) -> str:
    path = API / name
    if not path.is_file():
        raise SystemExit(f"Missing {path}. Run this script from the webapp directory.")
    return path.read_text(encoding="utf-8")


def replace_once(source: str, old: str, new: str, label: str) -> str:
    count = source.count(old)
    if count != 1:
        raise SystemExit(f"Patch precondition failed for {label}: expected one exact match, found {count}. No files were changed.")
    return source.replace(old, new, 1)


config = load_file("config.py")
services = load_file("services.py")
server = load_file("server.py")

if "MOBILE_STATE_STORE_PATH" in config or "mobile_state_store" in services or "/api/notes" in server:
    raise SystemExit("Mobile workflow patch appears to be already applied. No files were changed.")

if not (API / "mobile_state.py").is_file():
    raise SystemExit("Missing api/mobile_state.py. Copy the V2 module before applying this patch.")

config = replace_once(
    config,
    'MOBILE_AUTH_STORE_PATH = Path(os.environ.get("MINI_APP_MOBILE_AUTH_STORE_PATH", PROJECT_ROOT / ".private" / "mobile_auth.json")).resolve()\n',
    'MOBILE_AUTH_STORE_PATH = Path(os.environ.get("MINI_APP_MOBILE_AUTH_STORE_PATH", PROJECT_ROOT / ".private" / "mobile_auth.json")).resolve()\n'
    'MOBILE_STATE_STORE_PATH = Path(os.environ.get("MINI_APP_MOBILE_STATE_STORE_PATH", PROJECT_ROOT / ".private" / "mobile_state.json")).resolve()\n',
    "config mobile state path",
)

services = replace_once(
    services,
    "from .telegram_auth import MiniAppUser\n",
    "from .telegram_auth import MiniAppUser\nfrom .mobile_state import mobile_state_store\n",
    "services mobile state import",
)

helpers = '''def _decorate_dispute_status(items: list[dict[str, Any]]) -> list[dict[str, Any]]:
    for item in items:
        status = mobile_state_store.get_dispute_status(int(item["id"]))
        if status == "none" and item.get("disputed"):
            status = "pending"
        item["dispute_status"] = status
    return items


'''
services = replace_once(
    services,
    "def public_reasons() -> dict[str, Any]:\n",
    helpers + "def public_reasons() -> dict[str, Any]:\n",
    "services dispute metadata helper",
)

services = replace_once(
    services,
    "    return [_transaction_dict(row) for row in rows]\n\n\nasync def get_dashboard",
    "    return _decorate_dispute_status([_transaction_dict(row) for row in rows])\n\n\nasync def get_dashboard",
    "services history dispute status",
)

services = replace_once(
    services,
    "    return [_transaction_dict(row) for row in rows]\n\n\nasync def create_dispute",
    "    items = _decorate_dispute_status([_transaction_dict(row) for row in rows])\n"
    "    return [item for item in items if item.get('dispute_status') == 'none']\n\n\nasync def create_dispute",
    "services disputable transaction filtering",
)

services = replace_once(
    services,
    "    return [_transaction_dict(row) for row in rows]\n\n\nasync def resolve_dispute",
    "    return _decorate_dispute_status([_transaction_dict(row) for row in rows])\n\n\nasync def resolve_dispute",
    "services pending dispute metadata",
)

services = replace_once(
    services,
    "        await db.execute(\"UPDATE transactions SET disputed = 1 WHERE id = ?\", (transaction_id,))\n        await db.commit()\n\n    await _notify(",
    "        await db.execute(\"UPDATE transactions SET disputed = 1 WHERE id = ?\", (transaction_id,))\n"
    "        await db.commit()\n\n"
    "    try:\n"
    "        mobile_state_store.set_dispute_status(transaction_id, \"pending\")\n"
    "    except Exception:\n"
    "        logger.exception(\"Could not persist dispute metadata\")\n\n"
    "    await _notify(",
    "services persist pending dispute metadata",
)

old_resolution = '''async def resolve_dispute(user: MiniAppUser, transaction_id_raw: Any, action: str) -> dict[str, Any]:
    if user.role != "father":
        raise ApiError("Only Father can resolve disputes.", status=403, code="forbidden")

    transaction_id = _validate_amount(transaction_id_raw)
    action = str(action or "").lower()
    if action not in {"approve", "reject"}:
        raise ApiError("Dispute action must be approve or reject.", status=400, code="invalid_action")

    async with db_connection() as db:
        await db.execute("BEGIN IMMEDIATE")
        cursor = await db.execute(
            "SELECT id, type, amount, disputed FROM transactions WHERE id = ?",
            (transaction_id,),
        )
        row = await cursor.fetchone()
        if not row:
            raise ApiError("Transaction was not found.", status=404, code="not_found")
        if not bool(row["disputed"]):
            raise ApiError("This transaction is not pending dispute.", status=409, code="not_pending")

        if action == "approve":
            amount = int(row["amount"])
            trans_type = str(row["type"])
            await ensure_balance(db, ARTEMIY_ID)
            await db.execute("DELETE FROM transactions WHERE id = ?", (transaction_id,))
            if trans_type == "remove":
                await db.execute("UPDATE balance SET balance = balance + ? WHERE user_id = ?", (amount, ARTEMIY_ID))
            elif trans_type == "add":
                await db.execute("UPDATE balance SET balance = balance - ? WHERE user_id = ?", (amount, ARTEMIY_ID))
            status = "approved"
        else:
            await db.execute("UPDATE transactions SET disputed = 0 WHERE id = ?", (transaction_id,))
            status = "rejected"

        await db.commit()

    if action == "approve":
        await _notify(ARTEMIY_ID, f"Папа принял оспаривание транзакции #{transaction_id}. Баланс восстановлен.")
    else:
        await _notify(ARTEMIY_ID, f"Папа отклонил оспаривание транзакции #{transaction_id}.")

    return {"id": transaction_id, "status": status}
'''
new_resolution = '''async def resolve_dispute(user: MiniAppUser, transaction_id_raw: Any, action: str) -> dict[str, Any]:
    if user.role != "father":
        raise ApiError("Only Father can resolve disputes.", status=403, code="forbidden")

    transaction_id = _validate_amount(transaction_id_raw)
    action = str(action or "").lower()
    if action not in {"approve", "reject"}:
        raise ApiError("Dispute action must be approve or reject.", status=400, code="invalid_action")

    async with db_connection() as db:
        await db.execute("BEGIN IMMEDIATE")
        cursor = await db.execute(
            "SELECT id, type, amount, reason, disputed FROM transactions WHERE id = ? AND to_id = ?",
            (transaction_id, ARTEMIY_ID),
        )
        row = await cursor.fetchone()
        if not row:
            raise ApiError("Transaction was not found.", status=404, code="not_found")
        if not bool(row["disputed"]):
            raise ApiError("This transaction is not pending dispute.", status=409, code="not_pending")

        amount = int(row["amount"])
        trans_type = str(row["type"])
        original_reason = str(row["reason"] or "")
        await db.execute("UPDATE transactions SET disputed = 0 WHERE id = ?", (transaction_id,))

        if action == "approve":
            reverse_type = "remove" if trans_type == "add" else "add"
            await ensure_balance(db, ARTEMIY_ID)
            if reverse_type == "add":
                await db.execute("UPDATE balance SET balance = balance + ? WHERE user_id = ?", (amount, ARTEMIY_ID))
            else:
                await db.execute("UPDATE balance SET balance = balance - ? WHERE user_id = ?", (amount, ARTEMIY_ID))
            await db.execute(
                """
                INSERT INTO transactions (from_id, to_id, type, amount, reason)
                VALUES (?, ?, ?, ?, ?)
                """,
                (FATHER_ID, ARTEMIY_ID, reverse_type, amount, f"Компенсация по спору #{transaction_id}: {original_reason}"[:240]),
            )
            status = "approved"
        else:
            status = "rejected"

        await db.commit()

    try:
        mobile_state_store.set_dispute_status(transaction_id, status)
    except Exception:
        logger.exception("Could not persist final dispute metadata")

    if action == "approve":
        await _notify(ARTEMIY_ID, f"Папа принял оспаривание транзакции #{transaction_id}. Баланс восстановлен.")
    else:
        await _notify(ARTEMIY_ID, f"Папа отклонил оспаривание транзакции #{transaction_id}.")

    return {"id": transaction_id, "status": status}
'''
services = replace_once(services, old_resolution, new_resolution, "services dispute resolution")

old_note = '''async def send_note(user: MiniAppUser, text_raw: Any) -> dict[str, Any]:
    text = str(text_raw or "").strip()
    if not text:
        raise ApiError("Note text is required.", status=400, code="invalid_note")
    if len(text) > 1200:
        raise ApiError("Note text is too long.", status=400, code="invalid_note")

    if user.role == "father":
        target_id = ARTEMIY_ID
    elif user.role == "artemiy":
        target_id = FATHER_ID
    else:
        raise ApiError("Access denied.", status=403, code="forbidden")

    try:
        await telegram.send_message(
            chat_id=target_id,
            text=f"Новая заметка:\\n\\n{text}\\n\\n— От: {user.display_name}",
        )
    except TelegramApiError as exc:
        raise ApiError("Telegram could not deliver this note.", status=502, code="telegram_error") from exc

    return {
        "delivered": True,
        "target_user_id": target_id,
        "text": text,
        "timestamp": _utc_now().strftime("%Y-%m-%d %H:%M:%S"),
    }
'''
new_note = '''async def send_note(user: MiniAppUser, text_raw: Any) -> dict[str, Any]:
    text = str(text_raw or "").strip()
    if not text:
        raise ApiError("Note text is required.", status=400, code="invalid_note")
    if len(text) > 1200:
        raise ApiError("Note text is too long.", status=400, code="invalid_note")

    if user.role == "father":
        target_id = ARTEMIY_ID
    elif user.role == "artemiy":
        target_id = FATHER_ID
    else:
        raise ApiError("Access denied.", status=403, code="forbidden")

    try:
        note = await __import__("asyncio").to_thread(
            mobile_state_store.append_note,
            user.id,
            target_id,
            user.display_name,
            text,
        )
    except Exception as exc:
        logger.exception("Could not persist private note")
        raise ApiError("The note could not be saved.", status=503, code="note_store_unavailable") from exc

    try:
        await telegram.send_message(
            chat_id=target_id,
            text=f"Новая заметка:\\n\\n{text}\\n\\n— От: {user.display_name}",
        )
    except TelegramApiError:
        logger.exception("Telegram note notification failed; note remains in the shared feed")

    return {
        "id": note["id"],
        "delivered": True,
        "target_user_id": target_id,
        "text": text,
        "timestamp": _utc_now().strftime("%Y-%m-%d %H:%M:%S"),
    }


async def get_notes(user: MiniAppUser, limit: int = 100) -> list[dict[str, Any]]:
    try:
        notes = await __import__("asyncio").to_thread(mobile_state_store.get_notes, user.id, limit)
    except Exception as exc:
        logger.exception("Could not read private note feed")
        raise ApiError("Notes are temporarily unavailable.", status=503, code="note_store_unavailable") from exc

    return [
        {
            "id": item["id"],
            "sender_id": int(item["sender_id"]),
            "recipient_id": int(item["recipient_id"]),
            "sender_name": str(item.get("sender_name") or ""),
            "text": str(item["text"]),
            "timestamp": dt.datetime.fromtimestamp(int(item["timestamp"]), tz=dt.timezone.utc).strftime("%Y-%m-%d %H:%M:%S"),
        }
        for item in notes
    ]


async def get_casino_history(user: MiniAppUser, limit: int = 20) -> list[dict[str, Any]]:
    if user.role != "artemiy":
        raise ApiError("Only Artemiy can view casino history.", status=403, code="forbidden")
    limit = max(1, min(int(limit or 20), 100))
    async with db_connection() as db:
        cursor = await db.execute(
            """
            SELECT id, amount, reason, timestamp
            FROM transactions
            WHERE (from_id = ? OR to_id = ?)
              AND reason IN (?, ?)
            ORDER BY timestamp DESC
            LIMIT ?
            """,
            (user.id, user.id, CASINO_WIN_REASON, CASINO_LOSS_REASON, limit),
        )
        rows = await cursor.fetchall()

    rounds: list[dict[str, Any]] = []
    for row in rows:
        reason = str(row["reason"])
        won = reason == CASINO_WIN_REASON
        amount = int(row["amount"])
        rounds.append({
            "id": int(row["id"]),
            "won": won,
            "bet": amount // 2 if won else amount,
            "payout": amount if won else 0,
            "timestamp": _timestamp(row["timestamp"]),
        })
    return rounds
'''
services = replace_once(services, old_note, new_note, "services persistent notes")

server = replace_once(
    server,
    "    get_history,\n",
    "    get_history,\n    get_notes,\n    get_casino_history,\n",
    "server services imports",
)

handlers = '''async def notes(request: web.Request, user: MiniAppUser) -> web.Response:
    limit = int(request.query.get("limit", "100"))
    return json_response({"ok": True, "items": await get_notes(user, limit=limit)})


async def casino_history(request: web.Request, user: MiniAppUser) -> web.Response:
    limit = int(request.query.get("limit", "20"))
    return json_response({"ok": True, "items": await get_casino_history(user, limit=limit)})


'''
server = replace_once(
    server,
    "async def note(request: web.Request, user: MiniAppUser) -> web.Response:\n",
    handlers + "async def note(request: web.Request, user: MiniAppUser) -> web.Response:\n",
    "server notes and casino history handlers",
)

server = replace_once(
    server,
    '    app.router.add_post("/api/note", authed(note))\n',
    '    app.router.add_get("/api/notes", authed(notes))\n'
    '    app.router.add_get("/api/casino/history", authed(casino_history))\n'
    '    app.router.add_post("/api/note", authed(note))\n',
    "server notes and casino history routes",
)

changes = {
    API / "config.py": config,
    API / "services.py": services,
    API / "server.py": server,
}

backup_root = ROOT.parent / ".backups"
backup_root.mkdir(parents=True, exist_ok=True, mode=0o700)
os.chmod(backup_root, 0o700)
stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
backup_dir = backup_root / f"firecoins-mobile-state-{stamp}"
backup_dir.mkdir(mode=0o700)

for path in changes:
    relative = path.relative_to(ROOT)
    backup_path = backup_dir / relative
    backup_path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    shutil.copy2(path, backup_path)


def atomic_write(path: Path, value: str) -> None:
    fd, temporary_name = tempfile.mkstemp(prefix=f".{path.name}.", dir=str(path.parent))
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as stream:
            stream.write(value)
            stream.flush()
            os.fsync(stream.fileno())
        os.chmod(temporary_name, path.stat().st_mode & 0o777)
        os.replace(temporary_name, path)
    except Exception:
        with contextlib.suppress(OSError):
            os.unlink(temporary_name)
        raise


import contextlib

try:
    for path, value in changes.items():
        atomic_write(path, value)
except Exception:
    for path in changes:
        relative = path.relative_to(ROOT)
        shutil.copy2(backup_dir / relative, path)
    raise

print("Mobile notes and dispute metadata patch applied.")
print(f"Backups: {backup_dir}")
print("The points.db schema and rows were not modified.")
print("No service was restarted.")
