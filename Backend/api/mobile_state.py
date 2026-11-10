from __future__ import annotations

import contextlib
import json
import os
import tempfile
import time
from pathlib import Path
from typing import Any, Iterator

from .config import ARTEMIY_ID, FATHER_ID, MOBILE_STATE_STORE_PATH, POINTS_DB_PATH, POINTS_DB_PATH


class MobileStateStore:
    """Small cross-process sidecar for workflow metadata that does not fit the legacy DB schema."""

    def __init__(self, path: Path | str = MOBILE_STATE_STORE_PATH):
        self.path = Path(path).expanduser().resolve()
        if self.path == Path(POINTS_DB_PATH).expanduser().resolve():
            raise ValueError("Mobile state store must never point to the business SQLite database.")
        self.lock_path = self.path.with_name(self.path.name + ".lock")

    def _prepare(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        os.chmod(self.path.parent, 0o700)

    def _load(self) -> dict[str, Any]:
        if not self.path.exists():
            return {"version": 1, "notes": [], "disputes": {}}
        try:
            state = json.loads(self.path.read_text(encoding="utf-8"))
        except (OSError, UnicodeError, json.JSONDecodeError) as exc:
            raise RuntimeError("Mobile workflow store is unreadable; refusing to reset it.") from exc
        if (
            not isinstance(state, dict)
            or state.get("version") != 1
            or not isinstance(state.get("notes"), list)
            or not isinstance(state.get("disputes"), dict)
        ):
            raise RuntimeError("Mobile workflow store has an unsupported format; refusing to reset it.")
        return state

    def _save(self, state: dict[str, Any]) -> None:
        fd, temp_name = tempfile.mkstemp(prefix=".mobile-state-", dir=str(self.path.parent))
        try:
            os.fchmod(fd, 0o600)
            with os.fdopen(fd, "w", encoding="utf-8") as stream:
                json.dump(state, stream, ensure_ascii=False, separators=(",", ":"))
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temp_name, self.path)
            os.chmod(self.path, 0o600)
            directory_fd = os.open(str(self.path.parent), os.O_RDONLY)
            try:
                os.fsync(directory_fd)
            finally:
                os.close(directory_fd)
        except Exception:
            with contextlib.suppress(OSError):
                os.unlink(temp_name)
            raise

    @contextlib.contextmanager
    def _locked(self, write: bool = False) -> Iterator[dict[str, Any]]:
        self._prepare()
        fd = os.open(str(self.lock_path), os.O_CREAT | os.O_RDWR, 0o600)
        try:
            os.fchmod(fd, 0o600)
            import fcntl
            fcntl.flock(fd, fcntl.LOCK_EX if write else fcntl.LOCK_SH)
            state = self._load()
            try:
                yield state
            except BaseException:
                if write:
                    self._save(state)
                raise
            else:
                if write:
                    self._save(state)
        finally:
            with contextlib.suppress(OSError):
                import fcntl
                fcntl.flock(fd, fcntl.LOCK_UN)
            os.close(fd)

    def set_dispute_status(self, transaction_id: int, status: str) -> None:
        if status not in {"pending", "approved", "rejected"}:
            raise ValueError("Unsupported dispute status.")
        now = int(time.time())
        with self._locked(write=True) as state:
            state["disputes"][str(int(transaction_id))] = {"status": status, "updated_at": now}

    def get_dispute_status(self, transaction_id: int) -> str:
        with self._locked() as state:
            record = state["disputes"].get(str(int(transaction_id)))
            if not isinstance(record, dict):
                return "none"
            status = str(record.get("status", "none"))
            return status if status in {"pending", "approved", "rejected"} else "none"

    def append_note(self, sender_id: int, recipient_id: int, sender_name: str, text: str) -> dict[str, Any]:
        sender_id = int(sender_id)
        recipient_id = int(recipient_id)
        allowed_users = {int(FATHER_ID), int(ARTEMIY_ID)}
        if sender_id not in allowed_users or recipient_id not in allowed_users or sender_id == recipient_id:
            raise ValueError("Notes are restricted to the two configured Fire Coins accounts.")
        clean = str(text or "").strip()
        if not clean:
            raise ValueError("Note text is required.")
        if len(clean) > 1200:
            raise ValueError("Note text is too long.")
        record = {
            "id": os.urandom(16).hex(),
            "sender_id": sender_id,
            "recipient_id": recipient_id,
            "sender_name": str(sender_name or "").strip()[:80],
            "text": clean,
            "timestamp": int(time.time()),
        }
        with self._locked(write=True) as state:
            notes = state["notes"]
            notes.append(record)
            if len(notes) > 500:
                del notes[:-500]
        return record

    def get_notes(self, user_id: int, limit: int = 100) -> list[dict[str, Any]]:
        user_id = int(user_id)
        allowed_users = {int(FATHER_ID), int(ARTEMIY_ID)}
        if user_id not in allowed_users:
            return []
        limit = max(1, min(int(limit or 100), 200))
        with self._locked() as state:
            notes = [
                dict(note)
                for note in state["notes"]
                if isinstance(note, dict)
                and int(note.get("sender_id", 0)) in allowed_users
                and int(note.get("recipient_id", 0)) in allowed_users
                and (
                    int(note.get("sender_id", 0)) == user_id
                    or int(note.get("recipient_id", 0)) == user_id
                )
            ]
        notes.sort(key=lambda item: int(item.get("timestamp", 0)), reverse=True)
        return notes[:limit]


mobile_state_store = MobileStateStore()
