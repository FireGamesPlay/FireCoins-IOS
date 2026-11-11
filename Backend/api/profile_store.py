from __future__ import annotations

import contextlib
import fcntl
import hashlib
import json
import os
import tempfile
import uuid
from pathlib import Path
from typing import Any, Iterator

from .proof_store import detect_image_content_type

MAX_AVATAR_BYTES = 4 * 1024 * 1024


class ProfileStoreError(RuntimeError):
    pass


class ProfileStore:
    """Private account avatars stored separately from SQLite business data."""

    def __init__(self, root: Path | str):
        self.root = Path(root).expanduser().resolve()
        self.index_path = self.root / "avatars.json"
        self.lock_path = self.root / ".avatars.lock"

    def _prepare(self) -> None:
        self.root.mkdir(parents=True, exist_ok=True, mode=0o700)
        with contextlib.suppress(OSError):
            os.chmod(self.root, 0o700)

    def _load(self) -> dict[str, Any]:
        if not self.index_path.exists():
            return {"version": 1, "avatars": {}}
        try:
            value = json.loads(self.index_path.read_text(encoding="utf-8"))
        except (OSError, UnicodeError, json.JSONDecodeError) as exc:
            raise ProfileStoreError("Хранилище аватаров недоступно.") from exc
        if not isinstance(value, dict) or value.get("version") != 1 or not isinstance(value.get("avatars"), dict):
            raise ProfileStoreError("Хранилище аватаров имеет неизвестный формат.")
        return value

    def _save(self, value: dict[str, Any]) -> None:
        fd, temporary = tempfile.mkstemp(prefix=".avatars-", suffix=".tmp", dir=str(self.root))
        try:
            os.fchmod(fd, 0o600)
            with os.fdopen(fd, "w", encoding="utf-8") as stream:
                json.dump(value, stream, separators=(",", ":"))
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temporary, self.index_path)
            os.chmod(self.index_path, 0o600)
        except Exception:
            with contextlib.suppress(OSError):
                os.unlink(temporary)
            raise

    @contextlib.contextmanager
    def _locked(self, write: bool = False) -> Iterator[dict[str, Any]]:
        self._prepare()
        fd = os.open(self.lock_path, os.O_CREAT | os.O_RDWR, 0o600)
        try:
            os.fchmod(fd, 0o600)
            fcntl.flock(fd, fcntl.LOCK_EX if write else fcntl.LOCK_SH)
            data = self._load()
            yield data
            if write:
                self._save(data)
        finally:
            with contextlib.suppress(OSError):
                fcntl.flock(fd, fcntl.LOCK_UN)
            os.close(fd)

    def save(self, user_id: int, data: bytes) -> dict[str, Any]:
        body = bytes(data or b"")
        if not body or len(body) > MAX_AVATAR_BYTES:
            raise ProfileStoreError("Аватар должен быть изображением размером не более 4 МБ.")
        content_type = detect_image_content_type(body)
        if content_type not in {"image/jpeg", "image/png", "image/webp"}:
            raise ProfileStoreError("Поддерживаются JPEG, PNG и WebP.")
        key = uuid.uuid4().hex
        fd, temporary = tempfile.mkstemp(prefix=".avatar-", suffix=".tmp", dir=str(self.root))
        destination = self.root / f"{key}.blob"
        try:
            os.fchmod(fd, 0o600)
            with os.fdopen(fd, "wb") as stream:
                stream.write(body)
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temporary, destination)
            os.chmod(destination, 0o600)
        except Exception:
            with contextlib.suppress(OSError):
                os.unlink(temporary)
            raise
        with self._locked(write=True) as state:
            old = state["avatars"].get(str(int(user_id)))
            state["avatars"][str(int(user_id))] = {
                "key": key,
                "content_type": content_type,
                "size": len(body),
                "sha256": hashlib.sha256(body).hexdigest(),
            }
        if isinstance(old, dict):
            old_key = old.get("key")
            if isinstance(old_key, str) and len(old_key) == 32 and all(ch in "0123456789abcdef" for ch in old_key):
                with contextlib.suppress(OSError):
                    (self.root / f"{old_key}.blob").unlink()
        return {"content_type": content_type, "size": len(body)}

    def load(self, user_id: int) -> tuple[bytes, str]:
        with self._locked() as state:
            entry = state["avatars"].get(str(int(user_id)))
            if not isinstance(entry, dict):
                raise ProfileStoreError("Аватар ещё не установлен.")
            key = entry.get("key")
            content_type = entry.get("content_type")
        if not isinstance(key, str) or len(key) != 32 or any(ch not in "0123456789abcdef" for ch in key):
            raise ProfileStoreError("Запись аватара повреждена.")
        path = self.root / f"{key}.blob"
        try:
            body = path.read_bytes()
        except OSError as exc:
            raise ProfileStoreError("Файл аватара не найден.") from exc
        detected = detect_image_content_type(body)
        if not body or len(body) > MAX_AVATAR_BYTES or detected != content_type:
            raise ProfileStoreError("Файл аватара повреждён.")
        return body, detected

    def has(self, user_id: int) -> bool:
        try:
            self.load(user_id)
            return True
        except ProfileStoreError:
            return False


from .config import MOBILE_AUTH_STORE_PATH
from .environment import current_sidecar_root

_PROFILE_ROOT = MOBILE_AUTH_STORE_PATH.parent / "profiles"
_PROFILE_STORES: dict[Path, ProfileStore] = {}


def get_profile_store() -> ProfileStore:
    active_root = current_sidecar_root(MOBILE_AUTH_STORE_PATH.parent)
    root = _PROFILE_ROOT if active_root == MOBILE_AUTH_STORE_PATH.parent else active_root / "profiles"
    store = _PROFILE_STORES.get(root)
    if store is None:
        store = ProfileStore(root)
        _PROFILE_STORES[root] = store
    return store
