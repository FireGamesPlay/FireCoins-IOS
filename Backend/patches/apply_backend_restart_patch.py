from __future__ import annotations

import ast
import contextlib
import os
import shutil
import tempfile
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path.cwd()
SERVER = ROOT / "api" / "server.py"


def build_server_source(source: str) -> str:
    if '"/api/settings/backend/restart"' in source:
        raise SystemExit("Backend restart endpoint is already installed.")

    source = source.replace(
        "import logging\n",
        "import logging\nimport subprocess\n",
        1,
    )
    if "import subprocess\n" not in source:
        raise SystemExit("Could not add subprocess import.")

    handler = '''async def backend_restart(request: web.Request, user: MiniAppUser) -> web.Response:
    if user.role != "father":
        raise ApiError("Перезапуск Backend доступен только администратору.", status=403, code="forbidden")

    # Fixed, allow-listed systemd unit. No command, path, or unit name comes
    # from the request. A transient timer lets this response reach the client
    # before systemd restarts the API process.
    try:
        result = subprocess.run(
            [
                "/usr/bin/systemd-run",
                "--quiet",
                "--no-block",
                "--collect",
                "--unit=firecoins-backend-restart",
                "--on-active=3s",
                "/usr/bin/systemctl",
                "restart",
                "miniapp.service",
            ],
            capture_output=True,
            text=True,
            timeout=5,
            check=False,
        )
    except subprocess.TimeoutExpired as exc:
        logger.exception("Could not schedule Backend restart: systemd-run timed out")
        raise ApiError(
            "Не удалось запланировать перезапуск Backend: systemd не ответил вовремя.",
            status=503,
            code="backend_restart_timeout",
        ) from exc
    except OSError as exc:
        logger.exception("Could not execute systemd-run for Backend restart")
        raise ApiError(
            "Не удалось запустить systemd для перезапуска Backend.",
            status=503,
            code="backend_restart_unavailable",
        ) from exc

    if result.returncode != 0:
        logger.error("Backend restart scheduling failed: %s", (result.stderr or result.stdout or "")[-500:])
        raise ApiError(
            "Не удалось запланировать перезапуск Backend. Проверьте состояние systemd.",
            status=503,
            code="backend_restart_schedule_failed",
        )

    logger.warning("Backend API restart scheduled by admin account.")
    return json_response(
        {
            "ok": True,
            "restart_scheduled": True,
            "message": "Перезапуск Backend запланирован.",
        },
        status=202,
    )


'''
    anchor = "def create_app() -> web.Application:\n"
    if source.count(anchor) != 1:
        raise SystemExit("Expected exactly one create_app() function.")
    source = source.replace(anchor, handler + anchor, 1)

    route_anchor = '    app.router.add_get("/api/health", health)\n'
    route = '    app.router.add_post("/api/settings/backend/restart", authed_unscoped(backend_restart))\n'
    if source.count(route_anchor) != 1:
        raise SystemExit("Expected exactly one health route.")
    source = source.replace(route_anchor, route_anchor + route, 1)

    ast.parse(source)
    if "subprocess.run(" not in source or '"miniapp.service"' not in source:
        raise SystemExit("Backend restart handler did not pass its safety checks.")
    return source


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
    if not SERVER.is_file():
        raise SystemExit(f"Missing {SERVER}. Run from the deployed webapp directory.")
    source = SERVER.read_text(encoding="utf-8")
    patched = build_server_source(source)

    backup_root = ROOT.parent / ".backups"
    backup_root.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(backup_root, 0o700)
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    backup = backup_root / f"firecoins-backend-restart-{stamp}"
    backup.mkdir(mode=0o700)
    shutil.copy2(SERVER, backup / SERVER.name)

    _atomic_write(SERVER, patched)
    print("Installed authenticated Backend restart endpoint.")
    print(f"Backup: {backup}")
    print("The endpoint schedules only: systemctl restart miniapp.service")
    print("No production database or business data was modified.")
    return backup


if __name__ == "__main__":
    apply()
