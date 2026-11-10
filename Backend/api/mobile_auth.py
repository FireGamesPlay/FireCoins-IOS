from __future__ import annotations

import argparse
import contextlib
import fcntl
import hashlib
import hmac
import json
import os
import re
import secrets
import tempfile
import time
from pathlib import Path
from typing import Any, Iterator

from .config import ARTEMIY_ID, FATHER_ID, MOBILE_AUTH_STORE_PATH, POINTS_DB_PATH, get_role


ACCESS_TOKEN_TTL_SECONDS = 15 * 60
REFRESH_TOKEN_TTL_SECONDS = 90 * 24 * 60 * 60
INVITATION_SCRYPT_N = 1 << 14
INVITATION_SCRYPT_R = 8
INVITATION_SCRYPT_P = 1
INVITATION_PATTERN = re.compile(r"^FC-[A-HJ-NP-Z2-9]{4}(?:-[A-HJ-NP-Z2-9]{4}){3}$")
INVITATION_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"


class MobileAuthError(Exception):
    def __init__(self, message: str, status: int = 401, code: str = "mobile_auth_error"):
        super().__init__(message)
        self.status = status
        self.code = code


def _empty_state() -> dict[str, Any]:
    return {
        "version": 1,
        "invitations": [],
        "sessions": {},
        "failed_attempts": {},
    }


def _normalize_invitation(value: str) -> str:
    normalized = re.sub(r"\s+", "", str(value or "")).upper()
    return normalized


def _derive_invitation_hash(code: str, salt: bytes) -> bytes:
    return hashlib.scrypt(
        code.encode("utf-8"),
        salt=salt,
        n=INVITATION_SCRYPT_N,
        r=INVITATION_SCRYPT_R,
        p=INVITATION_SCRYPT_P,
        dklen=32,
    )


def _new_invitation() -> str:
    groups = ["".join(secrets.choice(INVITATION_ALPHABET) for _ in range(4)) for _ in range(4)]
    return "FC-" + "-".join(groups)


def _token_hash(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


class MobileAuthStore:
    """File-backed mobile auth state. No connection to the business SQLite DB."""

    def __init__(self, path: Path | str = MOBILE_AUTH_STORE_PATH):
        self.path = Path(path).expanduser().resolve()
        if self.path == Path(POINTS_DB_PATH).expanduser().resolve():
            raise ValueError("Mobile auth store must never point to the business SQLite database.")
        self.lock_path = self.path.with_name(self.path.name + ".lock")

    def _prepare_directory(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        try:
            os.chmod(self.path.parent, 0o700)
        except OSError:
            pass

    def _load_unlocked(self) -> dict[str, Any]:
        if not self.path.exists():
            return _empty_state()
        try:
            raw = self.path.read_text(encoding="utf-8")
            state = json.loads(raw)
        except (OSError, UnicodeError, json.JSONDecodeError) as exc:
            raise RuntimeError("Mobile authentication store is unreadable; refusing to reset it.") from exc
        if (
            not isinstance(state, dict)
            or state.get("version") != 1
            or not isinstance(state.get("invitations"), list)
            or not isinstance(state.get("sessions"), dict)
            or not isinstance(state.get("failed_attempts"), dict)
        ):
            raise RuntimeError("Mobile authentication store has an unsupported format; refusing to reset it.")
        return state

    def _save_unlocked(self, state: dict[str, Any]) -> None:
        self._prepare_directory()
        fd, temporary_name = tempfile.mkstemp(prefix=".mobile-auth-", suffix=".tmp", dir=str(self.path.parent))
        try:
            os.fchmod(fd, 0o600)
            with os.fdopen(fd, "w", encoding="utf-8") as stream:
                json.dump(state, stream, ensure_ascii=True, separators=(",", ":"))
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temporary_name, self.path)
            os.chmod(self.path, 0o600)
            directory_fd = os.open(str(self.path.parent), os.O_RDONLY)
            try:
                os.fsync(directory_fd)
            finally:
                os.close(directory_fd)
        except Exception:
            with contextlib.suppress(OSError):
                os.unlink(temporary_name)
            raise

    @contextlib.contextmanager
    def _locked(self, write: bool = False) -> Iterator[dict[str, Any]]:
        self._prepare_directory()
        lock_fd = os.open(str(self.lock_path), os.O_CREAT | os.O_RDWR, 0o600)
        try:
            os.fchmod(lock_fd, 0o600)
            fcntl.flock(lock_fd, fcntl.LOCK_EX if write else fcntl.LOCK_SH)
            state = self._load_unlocked()
            try:
                yield state
            except BaseException:
                if write:
                    self._save_unlocked(state)
                raise
            else:
                if write:
                    self._save_unlocked(state)
        finally:
            with contextlib.suppress(OSError):
                fcntl.flock(lock_fd, fcntl.LOCK_UN)
            os.close(lock_fd)

    @staticmethod
    def _prune_sessions(state: dict[str, Any], now: int) -> None:
        sessions = state["sessions"]
        expired = [
            session_id
            for session_id, session in sessions.items()
            if not isinstance(session, dict)
            or int(session.get("refresh_expires_at", 0)) <= now
        ]
        for session_id in expired:
            sessions.pop(session_id, None)

    def bootstrap_invitations(self, rotate: bool = False) -> dict[str, str]:
        """Create exactly two role-bound invitations and persist only salted hashes."""
        self._prepare_directory()
        with self._locked(write=True) as state:
            if self.path.exists() and not rotate:
                raise RuntimeError("Mobile auth is already initialized. Use the explicit rotation command to replace invitations.")
            generated: dict[str, str] = {}
            invitation_rows: list[dict[str, Any]] = []
            for role, user_id in (("father", FATHER_ID), ("artemiy", ARTEMIY_ID)):
                code = _new_invitation()
                salt = secrets.token_bytes(16)
                digest = _derive_invitation_hash(code, salt)
                generated[role] = code
                invitation_rows.append({
                    "role": role,
                    "user_id": int(user_id),
                    "salt": salt.hex(),
                    "hash": digest.hex(),
                    "used_at": None,
                    "created_at": int(time.time()),
                })
            state.clear()
            state.update(_empty_state())
            state["invitations"] = invitation_rows
            return generated

    @staticmethod
    def _check_rate_limit(state: dict[str, Any], remote_key: str, now: int) -> None:
        attempts = state["failed_attempts"]
        entry = attempts.get(remote_key, {})
        locked_until = int(entry.get("locked_until", 0)) if isinstance(entry, dict) else 0
        if locked_until > now:
            raise MobileAuthError(
                "Too many invalid invitation attempts. Try again later.",
                status=429,
                code="rate_limited",
            )

    @staticmethod
    def _record_failed_attempt(state: dict[str, Any], remote_key: str, now: int) -> None:
        attempts = state["failed_attempts"]
        entry = attempts.get(remote_key)
        if not isinstance(entry, dict) or now - int(entry.get("window_started_at", 0)) > 600:
            entry = {"window_started_at": now, "count": 0, "locked_until": 0}
        entry["count"] = int(entry.get("count", 0)) + 1
        if entry["count"] >= 8:
            entry["locked_until"] = now + 900
        attempts[remote_key] = entry
        if len(attempts) > 2048:
            oldest = sorted(
                attempts.items(),
                key=lambda pair: int(pair[1].get("window_started_at", 0)) if isinstance(pair[1], dict) else 0,
            )
            for key, _ in oldest[: len(attempts) - 1024]:
                attempts.pop(key, None)

    def _new_session(self, state: dict[str, Any], user_id: int, role: str, now: int) -> dict[str, Any]:
        access_token = secrets.token_urlsafe(32)
        refresh_token = secrets.token_urlsafe(48)
        session_id = secrets.token_urlsafe(18)
        state["sessions"][session_id] = {
            "user_id": int(user_id),
            "role": role,
            "access_hash": _token_hash(access_token),
            "refresh_hash": _token_hash(refresh_token),
            "access_expires_at": now + ACCESS_TOKEN_TTL_SECONDS,
            "refresh_expires_at": now + REFRESH_TOKEN_TTL_SECONDS,
            "created_at": now,
            "updated_at": now,
        }
        return {
            "access_token": access_token,
            "refresh_token": refresh_token,
            "token_type": "Bearer",
            "access_expires_in": ACCESS_TOKEN_TTL_SECONDS,
            "refresh_expires_in": REFRESH_TOKEN_TTL_SECONDS,
        }

    def redeem_invitation(self, invitation_code: str, remote_key: str = "unknown") -> tuple[int, str, dict[str, Any]]:
        code = _normalize_invitation(invitation_code)
        now = int(time.time())
        with self._locked(write=True) as state:
            self._check_rate_limit(state, remote_key, now)
            matched: dict[str, Any] | None = None
            if INVITATION_PATTERN.fullmatch(code):
                for row in state["invitations"]:
                    if row.get("used_at") is not None:
                        continue
                    try:
                        salt = bytes.fromhex(str(row["salt"]))
                        expected = bytes.fromhex(str(row["hash"]))
                    except (KeyError, ValueError, TypeError):
                        continue
                    actual = _derive_invitation_hash(code, salt)
                    if hmac.compare_digest(actual, expected):
                        matched = row
                        break

            if matched is None:
                self._record_failed_attempt(state, remote_key, now)
                raise MobileAuthError(
                    "Invitation is invalid or has already been used.",
                    status=401,
                    code="invalid_invitation",
                )

            user_id = int(matched["user_id"])
            role = str(matched["role"])
            if get_role(user_id) != role:
                raise MobileAuthError("Invitation is no longer valid for this account.", status=403, code="invitation_disabled")

            matched["used_at"] = now
            state["failed_attempts"].pop(remote_key, None)
            tokens = self._new_session(state, user_id, role, now)
            return user_id, role, tokens

    def authenticate_access_token(self, access_token: str) -> tuple[int, str] | None:
        if not access_token or len(access_token) > 256:
            return None
        candidate = _token_hash(access_token)
        now = int(time.time())
        with self._locked(write=False) as state:
            for session in state["sessions"].values():
                if not isinstance(session, dict):
                    continue
                if int(session.get("access_expires_at", 0)) <= now:
                    continue
                if not hmac.compare_digest(candidate, str(session.get("access_hash", ""))):
                    continue
                user_id = int(session.get("user_id", 0))
                role = str(session.get("role", ""))
                if get_role(user_id) != role:
                    return None
                return user_id, role
        return None

    def refresh_session(self, refresh_token: str) -> dict[str, Any]:
        if not refresh_token or len(refresh_token) > 256:
            raise MobileAuthError("Refresh token is invalid or expired.", status=401, code="invalid_refresh_token")
        candidate = _token_hash(refresh_token)
        now = int(time.time())
        with self._locked(write=True) as state:
            self._prune_sessions(state, now)
            for session in state["sessions"].values():
                if not isinstance(session, dict):
                    continue
                if not hmac.compare_digest(candidate, str(session.get("refresh_hash", ""))):
                    continue
                user_id = int(session.get("user_id", 0))
                role = str(session.get("role", ""))
                if get_role(user_id) != role or int(session.get("refresh_expires_at", 0)) <= now:
                    break
                tokens = self._new_rotated_tokens(session, now)
                return tokens
        raise MobileAuthError("Refresh token is invalid or expired.", status=401, code="invalid_refresh_token")

    @staticmethod
    def _new_rotated_tokens(session: dict[str, Any], now: int) -> dict[str, Any]:
        access_token = secrets.token_urlsafe(32)
        refresh_token = secrets.token_urlsafe(48)
        session["access_hash"] = _token_hash(access_token)
        session["refresh_hash"] = _token_hash(refresh_token)
        session["access_expires_at"] = now + ACCESS_TOKEN_TTL_SECONDS
        session["refresh_expires_at"] = now + REFRESH_TOKEN_TTL_SECONDS
        session["updated_at"] = now
        return {
            "access_token": access_token,
            "refresh_token": refresh_token,
            "token_type": "Bearer",
            "access_expires_in": ACCESS_TOKEN_TTL_SECONDS,
            "refresh_expires_in": REFRESH_TOKEN_TTL_SECONDS,
        }

    def revoke_refresh_token(self, refresh_token: str) -> bool:
        if not refresh_token or len(refresh_token) > 256:
            return False
        candidate = _token_hash(refresh_token)
        with self._locked(write=True) as state:
            for session_id, session in list(state["sessions"].items()):
                if isinstance(session, dict) and hmac.compare_digest(candidate, str(session.get("refresh_hash", ""))):
                    state["sessions"].pop(session_id, None)
                    return True
        return False


mobile_auth_store = MobileAuthStore()


def _main() -> None:
    parser = argparse.ArgumentParser(description="Initialize Fire Coins mobile invitations.")
    parser.add_argument("--bootstrap", action="store_true", help="Generate the first two one-time invitations.")
    parser.add_argument("--rotate", action="store_true", help="Invalidate existing mobile sessions and replace both invitations.")
    args = parser.parse_args()
    if not args.bootstrap and not args.rotate:
        parser.error("Specify --bootstrap or --rotate.")
    codes = mobile_auth_store.bootstrap_invitations(rotate=args.rotate)
    print("Fire Coins mobile invitations (shown once; keep them private):")
    print(f"Father:  {codes['father']}")
    print(f"Artemiy: {codes['artemiy']}")


if __name__ == "__main__":
    _main()
