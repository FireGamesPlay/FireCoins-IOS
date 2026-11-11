from __future__ import annotations

import ast
import os
import shutil
import tempfile
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path.cwd()
API = ROOT / "api"
SERVER = API / "server.py"
SERVICES = API / "services.py"


def read(path: Path) -> str:
    if not path.is_file():
        raise SystemExit(f"Missing {path}. Run from the deployed webapp directory.")
    return path.read_text(encoding="utf-8")


def replace_once(source: str, old: str, new: str, label: str) -> str:
    count = source.count(old)
    if count != 1:
        raise SystemExit(f"Expected one exact match for {label}, found {count}.")
    return source.replace(old, new, 1)


def replace_function(source: str, name: str, replacement: str, async_expected: bool = True) -> str:
    tree = ast.parse(source)
    nodes = [
        node for node in tree.body
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)) and node.name == name
    ]
    if len(nodes) != 1:
        raise SystemExit(f"Expected exactly one top-level function {name!r}; found {len(nodes)}.")
    node = nodes[0]
    if async_expected != isinstance(node, ast.AsyncFunctionDef):
        raise SystemExit(f"Unexpected sync/async form for {name!r}.")
    lines = source.splitlines(keepends=True)
    lines[node.lineno - 1:node.end_lineno] = [replacement.rstrip() + "\n\n"]
    return "".join(lines)


def build_services_source(source: str) -> str:
    if "from .environment import current_database_path" in source:
        raise SystemExit("Services environment routing already appears to be installed.")
    source = replace_once(
        source,
        "from .telegram_client import TelegramApiError, telegram\nfrom .workflow_store import workflow_store\n",
        "from .telegram_client import TelegramApiError, telegram\n"
        "from .workflow_store import get_workflow_store\n"
        "from .environment import current_database_path\n"
        "from .push_service import notify_account\n",
        "services environment and notification imports",
    )
    source = replace_once(
        source,
        "aiosqlite.connect(str(POINTS_DB_PATH))",
        "aiosqlite.connect(str(current_database_path(POINTS_DB_PATH)))",
        "environment-aware database connection",
    )
    source = source.replace("workflow_store.append_note", "get_workflow_store().append_note")
    source = source.replace("workflow_store.list_notes", "get_workflow_store().list_notes")

    notify = '''async def _notify(chat_id: int, text: str, reply_markup: dict[str, Any] | None = None) -> int:
    # Recipient is selected by the server from the two configured account IDs.
    delivered = 0
    try:
        delivered = await notify_account(int(chat_id), "Fire Coins", text, "account_event")
    except Exception:
        logger.exception("Push notification failed")

    # Temporary V1 compatibility channel. Mobile workflows keep working when
    # TG_BOT_TOKEN is removed and the Telegram bot is retired.
    if telegram.available:
        try:
            await telegram.send_message(chat_id=chat_id, text=text, reply_markup=reply_markup)
        except TelegramApiError:
            logger.exception("Legacy Telegram notification failed")
    return delivered'''
    source = replace_function(source, "_notify", notify)

    send_note = '''async def send_note(user: MiniAppUser, text_raw: Any) -> dict[str, Any]:
    note_text = str(text_raw or "").strip()
    if not note_text:
        raise ApiError("Note text is required.", status=400, code="invalid_note")
    if len(note_text) > 1200:
        raise ApiError("Note text is too long.", status=400, code="invalid_note")

    if user.role == "father":
        target_id = ARTEMIY_ID
    elif user.role == "artemiy":
        target_id = FATHER_ID
    else:
        raise ApiError("Access denied.", status=403, code="forbidden")

    try:
        note = await __import__("asyncio").to_thread(
            get_workflow_store().append_note,
            user.id,
            user.role,
            target_id,
            user.display_name,
            note_text,
        )
    except (OSError, RuntimeError, ValueError) as exc:
        logger.exception("Could not persist Fire Coins note")
        raise ApiError("Note could not be saved to shared history.", status=503, code="note_persistence_failed") from exc

    push_delivered = await _notify(target_id, f"Новая заметка от {user.display_name}:\\\\n{note_text}")
    return {
        "id": note["id"],
        "sender_id": note["sender_id"],
        "sender_role": note["sender_role"],
        "recipient_id": note["recipient_id"],
        "sender_name": note["sender_name"],
        "text": note["text"],
        "timestamp": dt.datetime.fromtimestamp(note["created_at"], tz=dt.timezone.utc).strftime("%Y-%m-%d %H:%M:%S"),
        "delivered": True,
        "push_delivered": bool(push_delivered),
    }'''
    source = replace_function(source, "send_note", send_note)
    ast.parse(source)
    if "workflow_store.append_note" in source or "workflow_store.list_notes" in source:
        raise SystemExit("A static production note-store reference remains.")
    return source


def build_server_source(source: str) -> str:
    if "from .environment import DatabaseModeError, database_mode" in source:
        raise SystemExit("Settings/avatar/sandbox API patch already appears to be installed.")

    source = replace_once(
        source,
        "from .config import (\n    API_HOST,\n    API_PORT,\n    FATHER_ID,\n",
        "from .config import (\n    API_HOST,\n    API_PORT,\n    ARTEMIY_ID,\n    FATHER_ID,\n",
        "configured account IDs import",
    )
    source = replace_once(
        source,
        "from .proof_store import ProofStoreError, proof_store\n",
        "from .proof_store import ProofStoreError, get_proof_store\n"
        "from .environment import DatabaseModeError, database_mode, active_environment_status, set_sandbox_mode\n"
        "from .profile_store import ProfileStoreError, get_profile_store, MAX_AVATAR_BYTES\n"
        "from .push_store import PushStoreError, push_store\n"
        "from .push_service import push_delivery\n"
        "from .reset_challenges import ResetChallengeError, ResetChallengeStore\n",
        "environment/profile/push imports",
    )
    source = replace_once(
        source,
        "logger = logging.getLogger(__name__)\n",
        "logger = logging.getLogger(__name__)\nreset_challenges = ResetChallengeStore()\n",
        "reset challenge store",
    )

    source = replace_once(
        source,
        "except ApiError as exc:\n        return error_response(str(exc), status=exc.status, code=exc.code)\n",
        "except ApiError as exc:\n        return error_response(str(exc), status=exc.status, code=exc.code)\n"
        "    except DatabaseModeError as exc:\n"
        "        logger.exception(\"Database environment is unavailable\")\n"
        "        return error_response(str(exc), status=503, code=\"database_environment_unavailable\")\n"
        "    except ResetChallengeError as exc:\n"
        "        return error_response(str(exc), status=400, code=\"reset_confirmation_invalid\")\n"
        "    except PushStoreError as exc:\n"
        "        return error_response(str(exc), status=400, code=\"push_registration_invalid\")\n",
        "domain middleware error mapping",
    )

    authed = '''def authed(handler: Handler) -> Callable[[web.Request], Awaitable[web.StreamResponse]]:
    async def wrapper(request: web.Request) -> web.StreamResponse:
        user = await authenticate_request(request)
        async with database_mode.operation():
            return await handler(request, user)

    return wrapper


def authed_unscoped(handler: Handler) -> Callable[[web.Request], Awaitable[web.StreamResponse]]:
    """Authentication-only wrapper for environment switches and destructive operations."""
    async def wrapper(request: web.Request) -> web.StreamResponse:
        user = await authenticate_request(request)
        return await handler(request, user)

    return wrapper'''
    source = replace_function(source, "authed", authed)

    health = '''async def health(_: web.Request) -> web.Response:
    return json_response(
        {
            "ok": True,
            "service": "points-mini-app",
            "public_base_url": PUBLIC_BASE_URL,
            "father_id": FATHER_ID,
            "push_configured": push_delivery.configured,
            "telegram_configured": telegram.available,
        }
    )'''
    source = replace_function(source, "health", health)

    handlers = '''async def settings(request: web.Request, user: MiniAppUser) -> web.Response:
    environment = await active_environment_status()
    return json_response({
        "ok": True,
        "environment": environment,
        "can_reset_database": user.role == "father",
        "push_configured": push_delivery.configured,
    })


async def sandbox_switch(request: web.Request, user: MiniAppUser) -> web.Response:
    body = await read_json(request)
    enabled = body.get("enabled")
    if not isinstance(enabled, bool):
        raise ApiError("Поле enabled должно быть true или false.", status=400, code="invalid_environment")
    result = await set_sandbox_mode(enabled)
    logger.info("Database environment switched: actor_role=%s mode=%s", user.role, result["mode"])
    return json_response({"ok": True, "environment": result})


async def database_reset_challenge(request: web.Request, user: MiniAppUser) -> web.Response:
    if user.role != "father":
        raise ApiError("Сброс базы доступен только администратору.", status=403, code="forbidden")
    challenge = reset_challenges.issue(user.id)
    return json_response({"ok": True, **challenge})


async def database_reset_execute(request: web.Request, user: MiniAppUser) -> web.Response:
    if user.role != "father":
        raise ApiError("Сброс базы доступен только администратору.", status=403, code="forbidden")
    body = await read_json(request)
    if body.get("confirmation") != "УДАЛИТЬ БД":
        raise ApiError("Введите точную фразу «УДАЛИТЬ БД».", status=400, code="confirmation_phrase_mismatch")
    reset_challenges.consume(user.id, str(body.get("reset_token") or ""))
    result = await database_mode.reset_current()
    logger.warning(
        "DATABASE RESET COMPLETED actor_role=%s mode=%s removed_tables=%s",
        user.role,
        result["mode"],
        ",".join(sorted(result["removed_rows"].keys())),
    )
    return json_response({"ok": True, "reset": result})


async def upload_avatar(request: web.Request, user: MiniAppUser) -> web.Response:
    reader = await request.multipart()
    image_bytes = b""
    async for field in reader:
        if field.name != "photo":
            continue
        chunks: list[bytes] = []
        total = 0
        while True:
            chunk = await field.read_chunk()
            if not chunk:
                break
            total += len(chunk)
            if total > MAX_AVATAR_BYTES:
                raise ApiError("Аватар должен быть не больше 4 МБ.", status=413, code="avatar_too_large")
            chunks.append(chunk)
        image_bytes = b"".join(chunks)

    try:
        result = get_profile_store().save(user.id, image_bytes)
    except ProfileStoreError as exc:
        raise ApiError(str(exc), status=400, code="invalid_avatar") from exc
    return json_response({"ok": True, "avatar": result})


async def avatar_file(request: web.Request, user: MiniAppUser) -> web.StreamResponse:
    role = str(request.match_info.get("role") or "")
    account_id = {"father": FATHER_ID, "artemiy": ARTEMIY_ID}.get(role)
    if account_id is None:
        raise ApiError("Неизвестный профиль.", status=404, code="profile_not_found")
    try:
        body, content_type = get_profile_store().load(account_id)
    except ProfileStoreError as exc:
        raise ApiError(str(exc), status=404, code="avatar_not_found") from exc
    return web.Response(
        body=body,
        content_type=content_type,
        headers={
            "Cache-Control": "private, max-age=300",
            "Content-Disposition": 'inline; filename="avatar"',
            "X-Content-Type-Options": "nosniff",
        },
    )


async def mobile_push_register(request: web.Request, user: MiniAppUser) -> web.Response:
    body = await read_json(request)
    try:
        push_store.register(
            user.id,
            user.role,
            str(body.get("token") or ""),
            str(body.get("platform") or ""),
            str(body.get("device_id") or ""),
        )
    except PushStoreError as exc:
        raise ApiError(str(exc), status=400, code="invalid_push_device") from exc
    return json_response({"ok": True, "registered": True})


async def mobile_push_unregister(request: web.Request, user: MiniAppUser) -> web.Response:
    body = await read_json(request)
    removed = push_store.unregister(user.id, str(body.get("token") or ""))
    return json_response({"ok": True, "removed": removed})
'''
    anchor = "def create_app() -> web.Application:\n"
    source = replace_once(source, anchor, handlers + "\n\n" + anchor, "new settings/avatar/push handlers")

    source = replace_once(
        source,
        '    app.router.add_post("/api/mobile/logout", mobile_auth_logout)\n',
        '    app.router.add_post("/api/mobile/logout", mobile_auth_logout)\n'
        '    app.router.add_post("/api/mobile/push/register", authed(mobile_push_register))\n'
        '    app.router.add_post("/api/mobile/push/unregister", authed(mobile_push_unregister))\n'
        '    app.router.add_get("/api/settings", authed_unscoped(settings))\n'
        '    app.router.add_post("/api/settings/sandbox", authed_unscoped(sandbox_switch))\n'
        '    app.router.add_post("/api/settings/database/reset/challenge", authed_unscoped(database_reset_challenge))\n'
        '    app.router.add_post("/api/settings/database/reset", authed_unscoped(database_reset_execute))\n'
        '    app.router.add_post("/api/profile/avatar", authed(upload_avatar))\n'
        '    app.router.add_get("/api/profile/avatar/{role}", authed(avatar_file))\n',
        "new API routes",
    )
    source = source.replace("proof_store.save(file_bytes", "get_proof_store().save(file_bytes")
    source = source.replace("proof_store.load(proof_reference)", "get_proof_store().load(proof_reference)")

    source = replace_once(
        source,
        "    await init_db()\n    app = create_app()\n",
        "    async with database_mode.operation():\n        await init_db()\n    app = create_app()\n",
        "initialize currently selected database environment",
    )
    ast.parse(source)
    if "proof_store.save" in source or "proof_store.load" in source:
        raise SystemExit("Static production proof store reference remains.")
    return source


def build_changes() -> dict[Path, str]:
    services = read(SERVICES)
    server = read(SERVER)
    return {
        SERVICES: build_services_source(services),
        SERVER: build_server_source(server),
    }


def _atomic_write(path: Path, value: str) -> None:
    fd, temporary = tempfile.mkstemp(prefix=f".{path.name}.", dir=str(path.parent))
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as stream:
            stream.write(value)
            stream.flush()
            os.fsync(stream.fileno())
        os.chmod(temporary, path.stat().st_mode & 0o777)
        os.replace(temporary, path)
    except Exception:
        with contextlib.suppress(OSError):
            os.unlink(temporary)
        raise


def apply() -> Path:
    changes = build_changes()
    backup_root = ROOT.parent / ".backups"
    backup_root.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(backup_root, 0o700)
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    backup_dir = backup_root / f"firecoins-settings-sandbox-avatar-{stamp}"
    backup_dir.mkdir(mode=0o700)
    for path in changes:
        shutil.copy2(path, backup_dir / path.name)

    for path, value in changes.items():
        _atomic_write(path, value)
    print("Installed sandbox/settings/avatar/push-token API routes.")
    print(f"Backup: {backup_dir}")
    print("No production database schema or rows were modified.")
    print("Restart the API only after compile and staging verification.")
    return backup_dir


if __name__ == "__main__":
    import contextlib
    apply()
