from __future__ import annotations

import os
import shutil
import tempfile
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path.cwd()
SERVER = ROOT / "api" / "server.py"
if not SERVER.is_file():
    raise SystemExit("Run this script from the webapp directory; api/server.py is missing.")

source = SERVER.read_text(encoding="utf-8")
if "/api/mobile/invitations/reissue" in source:
    raise SystemExit("Invitation reissue route is already present. No files were changed.")

handler_anchor = """async def mobile_auth_refresh(request: web.Request) -> web.Response:
"""
handler = '''async def mobile_invitation_reissue(request: web.Request, user: MiniAppUser) -> web.Response:
    if user.role not in {"father", "artemiy"}:
        raise ApiError("Access denied.", status=403, code="forbidden")

    try:
        code = await asyncio.to_thread(mobile_auth_store.reissue_invitation, user.role)
    except MobileAuthError as exc:
        raise ApiError(str(exc), status=exc.status, code=exc.code) from exc
    except (OSError, RuntimeError) as exc:
        logger.exception("Mobile invitation reissue failed")
        raise ApiError("Invitation service is temporarily unavailable.", status=503, code="mobile_auth_unavailable") from exc

    # The plaintext code is returned exactly once; only its salted hash is stored.
    return json_response({
        "ok": True,
        "invitation": {"role": user.role, "code": code},
    })


'''
if source.count(handler_anchor) != 1:
    raise SystemExit("Patch precondition failed: expected one mobile_auth_refresh handler. No files were changed.")
source = source.replace(handler_anchor, handler + handler_anchor, 1)

route_anchor = '    app.router.add_post("/api/mobile/logout", mobile_auth_logout)\n'
route = '    app.router.add_post("/api/mobile/invitations/reissue", authed(mobile_invitation_reissue))\n'
if source.count(route_anchor) != 1:
    raise SystemExit("Patch precondition failed: expected one mobile logout route. No files were changed.")
source = source.replace(route_anchor, route_anchor + route, 1)

backup_root = ROOT.parent / ".backups"
backup_root.mkdir(parents=True, exist_ok=True, mode=0o700)
os.chmod(backup_root, 0o700)
stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
backup_dir = backup_root / f"firecoins-invitation-reissue-{stamp}"
backup_dir.mkdir(mode=0o700)
shutil.copy2(SERVER, backup_dir / "server.py")

fd, temporary_name = tempfile.mkstemp(prefix=".server.py.", dir=str(SERVER.parent))
try:
    with os.fdopen(fd, "w", encoding="utf-8") as stream:
        stream.write(source)
        stream.flush()
        os.fsync(stream.fileno())
    os.chmod(temporary_name, SERVER.stat().st_mode & 0o777)
    os.replace(temporary_name, SERVER)
except Exception:
    try:
        os.unlink(temporary_name)
    except OSError:
        pass
    raise

print("Invitation reissue API route installed.")
print(f"Backup: {backup_dir / 'server.py'}")
print("No database or invitation store was changed by this patch.")
print("Restart the API service only after tests pass.")
