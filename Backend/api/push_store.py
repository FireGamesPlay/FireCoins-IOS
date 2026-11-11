from __future__ import annotations

import contextlib
import fcntl
import hashlib
import json
import os
import tempfile
from contextlib import contextmanager
from pathlib import Path
from typing import Any, Iterator


class PushStoreError(RuntimeError):
    pass


class PushStore:
    """Private device-token registry; FCM tokens are never stored in points.db."""

    def __init__(self, path: Path | str):
        self.path = Path(path).expanduser().resolve()
        self.lock_path = self.path.with_name(self.path.name + ".lock")

    def _prepare(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        with contextlib.suppress(OSError):
            os.chmod(self.path.parent, 0o700)

    def _load(self) -> dict[str, Any]:
        if not self.path.exists():
            return {"version": 1, "devices": {}}
        try:
            value = json.loads(self.path.read_text(encoding="utf-8"))
        except (OSError, UnicodeError, json.JSONDecodeError) as exc:
            raise PushStoreError("Push token store is unreadable.") from exc
        if not isinstance(value, dict) or value.get("version") != 1 or not isinstance(value.get("devices"), dict):
            raise PushStoreError("Push token store has an unsupported format.")
        return value

    def _save(self, value: dict[str, Any]) -> None:
        fd, temporary = tempfile.mkstemp(prefix=".push-", suffix=".tmp", dir=str(self.path.parent))
        try:
            os.fchmod(fd, 0o600)
            with os.fdopen(fd, "w", encoding="utf-8") as stream:
                json.dump(value, stream, separators=(",", ":"))
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temporary, self.path)
            os.chmod(self.path, 0o600)
        except Exception:
            with contextlib.suppress(OSError):
                os.unlink(temporary)
            raise

    @contextmanager
    def _locked(self, write: bool = False) -> Iterator[dict[str, Any]]:
        self._prepare()
        fd = os.open(self.lock_path, os.O_CREAT | os.O_RDWR, 0o600)
        try:
            os.fchmod(fd, 0o600)
            fcntl.flock(fd, fcntl.LOCK_EX if write else fcntl.LOCK_SH)
            value = self._load()
            yield value
            if write:
                self._save(value)
        finally:
            with contextlib.suppress(OSError):
                fcntl.flock(fd, fcntl.LOCK_UN)
            os.close(fd)

    def register(self, user_id: int, role: str, token: str, platform: str, device_id: str) -> None:
        token = str(token or "").strip()
        platform = str(platform or "").lower().strip()
        device_id = str(device_id or "").strip()
        if not token or len(token) > 4096:
            raise PushStoreError("Invalid push token.")
        if role not in {"father", "artemiy"} or platform not in {"ios", "android"}:
            raise PushStoreError("Invalid push device identity.")
        if not device_id or len(device_id) > 128:
            raise PushStoreError("Invalid device identifier.")
        digest = hashlib.sha256(token.encode("utf-8")).hexdigest()
        with self._locked(write=True) as state:
            state["devices"][digest] = {
                "token": token,
                "user_id": int(user_id),
                "role": role,
                "platform": platform,
                "device_id": device_id,
            }

    def unregister(self, user_id: int, token: str) -> bool:
        digest = hashlib.sha256(str(token or "").strip().encode("utf-8")).hexdigest()
        removed = False
        with self._locked(write=True) as state:
            entry = state["devices"].get(digest)
            if isinstance(entry, dict) and int(entry.get("user_id", 0)) == int(user_id):
                del state["devices"][digest]
                removed = True
        return removed

    def devices_for(self, user_id: int) -> list[dict[str, str]]:
        with self._locked() as state:
            result = []
            for digest, entry in state["devices"].items():
                if isinstance(entry, dict) and int(entry.get("user_id", 0)) == int(user_id):
                    result.append({
                        "token": str(entry.get("token", "")),
                        "platform": str(entry.get("platform", "")),
                        "device_id": str(entry.get("device_id", "")),
                        "key": str(digest),
                    })
            return result

    def remove_key(self, key: str) -> bool:
        with self._locked(write=True) as state:
            return state["devices"].pop(str(key), None) is not None



from .config import POINTS_DB_PATH

_PRIVATE_ROOT = Path(POINTS_DB_PATH).expanduser().resolve().parent / ".private"
push_store = PushStore(_PRIVATE_ROOT / "push_devices.json")
