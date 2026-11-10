from __future__ import annotations

import os
import shutil
import tempfile
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path.cwd()
API = ROOT / "api"


def read_target(name: str) -> str:
    path = API / name
    if not path.is_file():
        raise SystemExit(f"Missing {path}. Run this script from the webapp directory.")
    return path.read_text(encoding="utf-8")


def replace_exact(source: str, old: str, new: str, label: str) -> str:
    count = source.count(old)
    if count != 1:
        raise SystemExit(f"Patch precondition failed for {label}: expected one exact match, found {count}. No files were changed.")
    return source.replace(old, new, 1)


config = read_target("config.py")
telegram_auth = read_target("telegram_auth.py")
server = read_target("server.py")

if "MOBILE_AUTH_STORE_PATH" in config or "mobile_auth_store" in telegram_auth or "/api/mobile/auth" in server:
    raise SystemExit("Mobile authentication patch appears to be already applied. No files were changed.")

if not (API / "mobile_auth.py").is_file():
    raise SystemExit("Missing api/mobile_auth.py. Copy the V2 mobile_auth.py module before applying this patch.")

config = replace_exact(
    config,
    'POINTS_DB_PATH = Path(os.environ.get("POINTS_DB_PATH", PROJECT_ROOT / "points.db")).resolve()\n',
    'POINTS_DB_PATH = Path(os.environ.get("POINTS_DB_PATH", PROJECT_ROOT / "points.db")).resolve()\n'
    'MOBILE_AUTH_STORE_PATH = Path(os.environ.get("MINI_APP_MOBILE_AUTH_STORE_PATH", PROJECT_ROOT / ".private" / "mobile_auth.json")).resolve()\n',
    "config mobile auth path",
)

telegram_auth = replace_exact(
    telegram_auth,
    "import hashlib\n",
    "import asyncio\nimport hashlib\n",
    "telegram_auth asyncio import",
)

telegram_auth = replace_exact(
    telegram_auth,
    "    get_role,\n)\n\n\nclass AuthError",
    "    get_role,\n)\nfrom .mobile_auth import mobile_auth_store\n\n\nclass AuthError",
    "telegram_auth mobile auth import",
)

old_auth = '''async def authenticate_request(request: web.Request) -> MiniAppUser:
    init_data = request.headers.get("X-Telegram-Init-Data", "")
    if init_data:
        return verify_init_data(init_data)

    dev_user = _dev_user_from_request(request)
    if dev_user:
        return dev_user

    raise AuthError("Telegram initData is required.")
'''
new_auth = '''async def authenticate_request(request: web.Request) -> MiniAppUser:
    init_data = request.headers.get("X-Telegram-Init-Data", "")
    if init_data:
        return verify_init_data(init_data)

    authorization = request.headers.get("Authorization", "").strip()
    if authorization:
        scheme, separator, credential = authorization.partition(" ")
        if scheme.lower() != "bearer" or not separator or not credential.strip():
            raise AuthError("Authorization header must use Bearer authentication.")
        try:
            identity = await asyncio.to_thread(
                mobile_auth_store.authenticate_access_token,
                credential.strip(),
            )
        except (OSError, RuntimeError) as exc:
            raise AuthError("Mobile authentication is temporarily unavailable.", status=503) from exc
        if identity is None:
            raise AuthError("Mobile session is invalid or expired.")
        user_id, role = identity
        return MiniAppUser(
            id=user_id,
            role=role,
            first_name="Father" if role == "father" else "Artemiy",
        )

    dev_user = _dev_user_from_request(request)
    if dev_user:
        return dev_user

    raise AuthError("Telegram initData or a mobile Bearer token is required.")
'''
telegram_auth = replace_exact(telegram_auth, old_auth, new_auth, "telegram request authentication")

server = replace_exact(
    server,
    "from .telegram_auth import AuthError, MiniAppUser, authenticate_request\n",
    "from .telegram_auth import AuthError, MiniAppUser, authenticate_request\nfrom .mobile_auth import MobileAuthError, mobile_auth_store\n",
    "server mobile auth import",
)

handlers = '''async def mobile_auth_login(request: web.Request) -> web.Response:
    body = await read_json(request)
    invitation_code = body.get("invitation_code")
    if not isinstance(invitation_code, str) or not invitation_code.strip():
        raise ApiError("Invitation code is required.", status=400, code="invalid_invitation")

    try:
        user_id, role, tokens = await asyncio.to_thread(
            mobile_auth_store.redeem_invitation,
            invitation_code,
            request.remote or "unknown",
        )
    except MobileAuthError as exc:
        raise AuthError(str(exc), status=exc.status) from exc
    except (OSError, RuntimeError) as exc:
        logger.exception("Mobile auth store is unavailable")
        raise ApiError("Mobile authentication is temporarily unavailable.", status=503, code="mobile_auth_unavailable") from exc

    user = MiniAppUser(
        id=user_id,
        role=role,
        first_name="Father" if role == "father" else "Artemiy",
    )
    return json_response({"ok": True, "user": user.to_public_dict(), "session": tokens})


async def mobile_auth_refresh(request: web.Request) -> web.Response:
    body = await read_json(request)
    refresh_token = body.get("refresh_token")
    if not isinstance(refresh_token, str) or not refresh_token.strip():
        raise ApiError("Refresh token is required.", status=400, code="invalid_refresh_token")

    try:
        tokens = await asyncio.to_thread(mobile_auth_store.refresh_session, refresh_token.strip())
    except MobileAuthError as exc:
        raise AuthError(str(exc), status=exc.status) from exc
    except (OSError, RuntimeError) as exc:
        logger.exception("Mobile auth store is unavailable")
        raise ApiError("Mobile authentication is temporarily unavailable.", status=503, code="mobile_auth_unavailable") from exc

    return json_response({"ok": True, "session": tokens})


async def mobile_auth_logout(request: web.Request) -> web.Response:
    body = await read_json(request)
    refresh_token = body.get("refresh_token")
    if not isinstance(refresh_token, str) or not refresh_token.strip():
        raise ApiError("Refresh token is required.", status=400, code="invalid_refresh_token")

    try:
        await asyncio.to_thread(mobile_auth_store.revoke_refresh_token, refresh_token.strip())
    except (OSError, RuntimeError) as exc:
        logger.exception("Mobile auth store is unavailable")
        raise ApiError("Mobile authentication is temporarily unavailable.", status=503, code="mobile_auth_unavailable") from exc

    # Logout is intentionally idempotent and does not reveal whether a token existed.
    return json_response({"ok": True})


'''
server = replace_exact(
    server,
    "async def me(_: web.Request, user: MiniAppUser) -> web.Response:\n",
    handlers + "async def me(_: web.Request, user: MiniAppUser) -> web.Response:\n",
    "server mobile auth handlers",
)

server = replace_exact(
    server,
    '    app.router.add_get("/api/health", health)\n',
    '    app.router.add_get("/api/health", health)\n'
    '    app.router.add_post("/api/mobile/auth", mobile_auth_login)\n'
    '    app.router.add_post("/api/mobile/refresh", mobile_auth_refresh)\n'
    '    app.router.add_post("/api/mobile/logout", mobile_auth_logout)\n',
    "server mobile auth routes",
)

changes = {
    API / "config.py": config,
    API / "telegram_auth.py": telegram_auth,
    API / "server.py": server,
}

backup_root = ROOT.parent / ".backups"
backup_root.mkdir(parents=True, exist_ok=True, mode=0o700)
os.chmod(backup_root, 0o700)
stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
backup_dir = backup_root / f"firecoins-mobile-auth-{stamp}"
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
        try:
            os.unlink(temporary_name)
        except OSError:
            pass
        raise

try:
    for path, value in changes.items():
        atomic_write(path, value)
except Exception:
    for path in changes:
        relative = path.relative_to(ROOT)
        shutil.copy2(backup_dir / relative, path)
    raise

print("Mobile authentication code patch applied.")
print(f"Backups: {backup_dir}")
print("No database file or table was touched.")
print("No service was restarted.")
print("Next: run unit tests and initialize invitations only after review.")
