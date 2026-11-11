from __future__ import annotations

import hashlib
import secrets
import threading
import time


class ResetChallengeError(RuntimeError):
    pass


class ResetChallengeStore:
    """Short-lived, one-use server-side challenges for destructive database reset."""

    def __init__(self, ttl_seconds: int = 90):
        self.ttl_seconds = max(30, min(int(ttl_seconds), 180))
        self._lock = threading.Lock()
        self._challenges: dict[str, tuple[int, int]] = {}

    @staticmethod
    def _digest(token: str) -> str:
        return hashlib.sha256(token.encode("utf-8")).hexdigest()

    def issue(self, user_id: int) -> dict[str, int | str]:
        now = int(time.time())
        token = secrets.token_urlsafe(32)
        digest = self._digest(token)
        with self._lock:
            self._prune(now)
            self._challenges[digest] = (int(user_id), now + self.ttl_seconds)
        return {"reset_token": token, "expires_in": self.ttl_seconds}

    def _prune(self, now: int) -> None:
        for key, (_, expires_at) in list(self._challenges.items()):
            if expires_at < now:
                self._challenges.pop(key, None)

    def consume(self, user_id: int, token: str) -> None:
        value = str(token or "").strip()
        if not value or len(value) > 256:
            raise ResetChallengeError("Подтверждение сброса истекло. Запросите новое.")
        digest = self._digest(value)
        now = int(time.time())
        with self._lock:
            self._prune(now)
            entry = self._challenges.pop(digest, None)
        if entry is None or entry[0] != int(user_id) or entry[1] < now:
            raise ResetChallengeError("Подтверждение сброса недействительно или уже использовано.")
