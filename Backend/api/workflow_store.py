from __future__ import annotations

import contextlib
import fcntl
import json
import os
import secrets
import tempfile
import time
from pathlib import Path
from typing import Any, Iterator


PROJECT_ROOT = Path(__file__).resolve().parents[2]
WORKFLOW_STORE_PATH = Path(
    os.environ.get(
        "FIRECOINS_WORKFLOW_STORE_PATH",
        PROJECT_ROOT / ".private" / "firecoins_workflow.json",
    )
).expanduser().resolve()
MAX_NOTES = 2000
MAX_NOTE_LENGTH = 1200


class WorkflowStore:
    """Durable app metadata that deliberately lives outside points.db."""

    def __init__(self, path: Path | str = WORKFLOW_STORE_PATH):
        self.path = Path(path).expanduser().resolve()
        self.lock_path = self.path.with_name(self.path.name + ".lock")

    def _prepare(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        try:
            os.chmod(self.path.parent, 0o700)
        except OSError:
            pass

    def _load(self) -> dict[str, Any]:
        if not self.path.exists():
            return {"version": 1, "notes": []}
        try:
            data = json.loads(self.path.read_text(encoding="utf-8"))
        except (OSError, UnicodeError, json.JSONDecodeError) as exc:
            raise RuntimeError("Workflow metadata store is unreadable; refusing to reset it.") from exc
        if not isinstance(data, dict) or data.get("version") != 1 or not isinstance(data.get("notes"), list):
            raise RuntimeError("Workflow metadata store has an unsupported format.")
        return data

    def _save(self, data: dict[str, Any]) -> None:
        self._prepare()
        fd, temporary = tempfile.mkstemp(prefix=".workflow-", suffix=".tmp", dir=str(self.path.parent))
        try:
            os.fchmod(fd, 0o600)
            with os.fdopen(fd, "w", encoding="utf-8") as stream:
                json.dump(data, stream, ensure_ascii=False, separators=(",", ":"))
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temporary, self.path)
            os.chmod(self.path, 0o600)
            directory_fd = os.open(str(self.path.parent), os.O_RDONLY)
            try:
                os.fsync(directory_fd)
            finally:
                os.close(directory_fd)
        except Exception:
            with contextlib.suppress(OSError):
                os.unlink(temporary)
            raise

    @contextlib.contextmanager
    def _locked(self, write: bool = False) -> Iterator[dict[str, Any]]:
        self._prepare()
        fd = os.open(str(self.lock_path), os.O_CREAT | os.O_RDWR, 0o600)
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

    def append_note(
        self,
        sender_id: int,
        sender_role: str,
        recipient_id: int,
        sender_name: str,
        note_text: str,
    ) -> dict[str, Any]:
        text = str(note_text or "").strip()
        if not text:
            raise ValueError("Note text is required.")
        if len(text) > MAX_NOTE_LENGTH:
            raise ValueError("Note text is too long.")
        if sender_role not in {"father", "artemiy"}:
            raise ValueError("Unknown note sender role.")

        now = int(time.time())
        note = {
            "id": secrets.token_urlsafe(12),
            "sender_id": int(sender_id),
            "sender_role": sender_role,
            "recipient_id": int(recipient_id),
            "sender_name": str(sender_name or "")[:80],
            "text": text,
            "created_at": now,
        }
        with self._locked(write=True) as data:
            data["notes"].append(note)
            if len(data["notes"]) > MAX_NOTES:
                del data["notes"][: len(data["notes"]) - MAX_NOTES]
        return note

    def list_notes(self, user_id: int, limit: int = 100) -> list[dict[str, Any]]:
        safe_limit = max(1, min(int(limit or 100), 200))
        with self._locked(write=False) as data:
            matching = [
                note for note in data["notes"]
                if isinstance(note, dict)
                and (
                    int(note.get("sender_id", 0)) == int(user_id)
                    or int(note.get("recipient_id", 0)) == int(user_id)
                )
            ]
            matching.sort(key=lambda note: int(note.get("created_at", 0)), reverse=True)
            return [dict(note) for note in matching[:safe_limit]]


workflow_store = WorkflowStore()
