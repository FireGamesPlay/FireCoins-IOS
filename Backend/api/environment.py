from __future__ import annotations

import asyncio
import contextlib
import contextvars
import json
import os
import secrets
import shutil
import sqlite3
import tempfile
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any, AsyncIterator


class DatabaseModeError(RuntimeError):
    pass


@dataclass(frozen=True)
class EnvironmentSnapshot:
    mode: str
    database_path: Path
    sidecar_root: Path

    @property
    def is_sandbox(self) -> bool:
        return self.mode == "sandbox"


_active_environment: contextvars.ContextVar[EnvironmentSnapshot | None] = contextvars.ContextVar(
    "firecoins_environment", default=None
)


def current_environment() -> EnvironmentSnapshot | None:
    return _active_environment.get()


def current_database_path(production_path: Path | str) -> Path:
    active = current_environment()
    return active.database_path if active else Path(production_path).expanduser().resolve()


def current_sidecar_root(production_root: Path | str) -> Path:
    active = current_environment()
    return active.sidecar_root if active else Path(production_root).expanduser().resolve()


class DatabaseMode:
    """Request-scoped environment selection with fail-closed sandbox switching."""

    def __init__(
        self,
        production_database: Path | str,
        private_root: Path | str,
        account_ids: tuple[int, int],
    ):
        self.production_database = Path(production_database).expanduser().resolve()
        self.private_root = Path(private_root).expanduser().resolve()
        self.state_path = self.private_root / "database_mode.json"
        self.sandbox_root = self.private_root / "sandbox"
        self.sandbox_database = self.sandbox_root / "points-test.db"
        self.sandbox_sidecars = self.sandbox_root / "sidecars"
        self.account_ids = tuple(int(x) for x in account_ids)
        self._lock = asyncio.Lock()
        if self.production_database == self.sandbox_database:
            raise ValueError("Production and sandbox databases must be different.")

    def _prepare(self) -> None:
        self.private_root.mkdir(parents=True, exist_ok=True, mode=0o700)
        with contextlib.suppress(OSError):
            os.chmod(self.private_root, 0o700)

    def _read_mode(self) -> str:
        if not self.state_path.exists():
            return "production"
        try:
            state = json.loads(self.state_path.read_text(encoding="utf-8"))
        except (OSError, UnicodeError, json.JSONDecodeError) as exc:
            raise DatabaseModeError("Состояние базы повреждено. Операции остановлены для защиты данных.") from exc
        if not isinstance(state, dict) or state.get("version") != 1 or state.get("mode") not in {"production", "sandbox"}:
            raise DatabaseModeError("Неизвестное состояние базы. Операции остановлены для защиты данных.")
        return str(state["mode"])

    def _write_mode(self, mode: str) -> None:
        if mode not in {"production", "sandbox"}:
            raise ValueError("Unsupported database mode.")
        self._prepare()
        fd, temporary = tempfile.mkstemp(prefix=".database-mode-", suffix=".tmp", dir=str(self.private_root))
        try:
            os.fchmod(fd, 0o600)
            with os.fdopen(fd, "w", encoding="utf-8") as stream:
                json.dump({"version": 1, "mode": mode}, stream, separators=(",", ":"))
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temporary, self.state_path)
            os.chmod(self.state_path, 0o600)
            directory_fd = os.open(self.private_root, os.O_RDONLY)
            try:
                os.fsync(directory_fd)
            finally:
                os.close(directory_fd)
        except Exception:
            with contextlib.suppress(OSError):
                os.unlink(temporary)
            raise

    def _snapshot(self) -> EnvironmentSnapshot:
        mode = self._read_mode()
        if mode == "production":
            if not self.production_database.is_file():
                raise DatabaseModeError("Рабочая база не найдена. Операции остановлены.")
            return EnvironmentSnapshot("production", self.production_database, self.private_root)
        if not self.sandbox_database.is_file():
            raise DatabaseModeError("Тестовая база отсутствует. Рабочая база не выбрана автоматически.")
        return EnvironmentSnapshot("sandbox", self.sandbox_database, self.sandbox_sidecars)

    @contextlib.asynccontextmanager
    async def operation(self) -> AsyncIterator[EnvironmentSnapshot]:
        async with self._lock:
            snapshot = self._snapshot()
            token = _active_environment.set(snapshot)
            try:
                yield snapshot
            finally:
                _active_environment.reset(token)

    async def status(self) -> dict[str, Any]:
        async with self._lock:
            return self._payload(self._snapshot())

    @staticmethod
    def _payload(snapshot: EnvironmentSnapshot) -> dict[str, Any]:
        return {
            "mode": snapshot.mode,
            "is_sandbox": snapshot.is_sandbox,
            "label": "ТЕСТ РЕЖИМ" if snapshot.is_sandbox else "Рабочая среда",
        }

    def _create_empty_sandbox(self) -> None:
        if not self.production_database.is_file():
            raise DatabaseModeError("Рабочая база не найдена. Песочница не создана.")
        if self.production_database == self.sandbox_database:
            raise DatabaseModeError("Пути рабочей и тестовой базы совпадают.")

        if self.sandbox_root.exists():
            shutil.rmtree(self.sandbox_root)
        self.sandbox_sidecars.mkdir(parents=True, mode=0o700)
        source = sqlite3.connect(f"file:{self.production_database.as_posix()}?mode=ro", uri=True, timeout=15)
        target = sqlite3.connect(str(self.sandbox_database), timeout=15)
        try:
            objects = source.execute(
                """
                SELECT type, name, sql FROM sqlite_master
                WHERE sql IS NOT NULL AND name NOT LIKE 'sqlite_%'
                ORDER BY CASE type WHEN 'table' THEN 0 WHEN 'index' THEN 1
                                   WHEN 'trigger' THEN 2 WHEN 'view' THEN 3 ELSE 4 END, name
                """
            ).fetchall()
            tables = [item for item in objects if item[0] == "table"]
            other_objects = [item for item in objects if item[0] != "table"]
            if not tables:
                raise DatabaseModeError("В рабочей базе не найдена схема.")
            target.execute("PRAGMA journal_mode=DELETE")
            for _kind, _name, statement in tables + other_objects:
                target.execute(statement)

            table_names = {
                item[0] for item in target.execute(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'"
                ).fetchall()
            }
            if "balance" in table_names:
                columns = {item[1] for item in target.execute("PRAGMA table_info(balance)").fetchall()}
                if {"user_id", "balance"}.issubset(columns):
                    target.executemany(
                        "INSERT INTO balance (user_id, balance) VALUES (?, 0)",
                        [(account_id,) for account_id in self.account_ids],
                    )
            target.commit()
            integrity = target.execute("PRAGMA integrity_check").fetchone()
            if not integrity or integrity[0] != "ok":
                raise DatabaseModeError("Новая тестовая база не прошла проверку целостности.")
        except Exception:
            with contextlib.suppress(Exception):
                target.rollback()
            target.close()
            source.close()
            with contextlib.suppress(OSError):
                shutil.rmtree(self.sandbox_root)
            raise
        else:
            target.close()
            source.close()
        os.chmod(self.sandbox_database, 0o600)
        os.chmod(self.sandbox_root, 0o700)
        os.chmod(self.sandbox_sidecars, 0o700)

    def _reset_database(self, path: Path) -> dict[str, int]:
        connection = sqlite3.connect(str(path), timeout=20)
        try:
            integrity = connection.execute("PRAGMA integrity_check").fetchone()
            if not integrity or integrity[0] != "ok":
                raise DatabaseModeError("База повреждена и не проходит проверку. Автоматический сброс остановлен.")
            tables = [
                row[0] for row in connection.execute(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'"
                ).fetchall()
            ]
            connection.execute("PRAGMA foreign_keys=OFF")
            connection.execute("BEGIN IMMEDIATE")
            removed: dict[str, int] = {}
            for table in tables:
                safe_name = table.replace('"', '""')
                count = connection.execute(f'SELECT COUNT(*) FROM "{safe_name}"').fetchone()[0]
                connection.execute(f'DELETE FROM "{safe_name}"')
                removed[table] = int(count)
            if connection.execute(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name='sqlite_sequence'"
            ).fetchone():
                connection.execute("DELETE FROM sqlite_sequence")
            if "balance" in tables:
                columns = {row[1] for row in connection.execute("PRAGMA table_info(balance)").fetchall()}
                if {"user_id", "balance"}.issubset(columns):
                    connection.executemany(
                        "INSERT INTO balance (user_id, balance) VALUES (?, 0)",
                        [(account_id,) for account_id in self.account_ids],
                    )
            connection.commit()
            after = connection.execute("PRAGMA integrity_check").fetchone()
            if not after or after[0] != "ok":
                raise DatabaseModeError("После сброса база не прошла проверку целостности.")
            return removed
        except Exception:
            connection.rollback()
            raise
        finally:
            connection.close()

    async def set_sandbox(self, enabled: bool) -> dict[str, Any]:
        async with self._lock:
            current = self._read_mode()
            if enabled:
                if current == "sandbox":
                    return self._payload(self._snapshot())
                await asyncio.to_thread(self._create_empty_sandbox)
                self._write_mode("sandbox")
                return self._payload(self._snapshot())
            if current == "production":
                return self._payload(self._snapshot())
            self._write_mode("production")
            await asyncio.to_thread(shutil.rmtree, self.sandbox_root, True)
            return self._payload(self._snapshot())

    async def reset_current(self) -> dict[str, Any]:
        async with self._lock:
            snapshot = self._snapshot()
            removed = await asyncio.to_thread(self._reset_database, snapshot.database_path)
            if snapshot.is_sandbox and snapshot.sidecar_root.exists():
                await asyncio.to_thread(shutil.rmtree, snapshot.sidecar_root)
                snapshot.sidecar_root.mkdir(parents=True, mode=0o700)
            else:
                # Notes and proofs are environment data too. Preserve profile avatars.
                for name in ("firecoins_workflow.json", "firecoins_workflow.json.lock", "proofs"):
                    path = snapshot.sidecar_root / name
                    if path.is_dir():
                        await asyncio.to_thread(shutil.rmtree, path, True)
                    else:
                        with contextlib.suppress(FileNotFoundError):
                            path.unlink()
            return {"mode": snapshot.mode, "removed_rows": removed}

    def create_reset_challenge(self, ttl_seconds: int = 90) -> dict[str, Any]:
        now = int(time.time())
        token = secrets.token_urlsafe(24)
        return {"challenge": token, "expires_in": max(30, min(ttl_seconds, 180)), "_created_at": now}


from .config import FATHER_ID, ARTEMIY_ID, POINTS_DB_PATH
PRIVATE_ROOT = Path(POINTS_DB_PATH).expanduser().resolve().parent / ".private"
database_mode = DatabaseMode(POINTS_DB_PATH, PRIVATE_ROOT, (FATHER_ID, ARTEMIY_ID))


async def active_environment_status() -> dict[str, Any]:
    return await database_mode.status()


async def set_sandbox_mode(enabled: bool) -> dict[str, Any]:
    return await database_mode.set_sandbox(enabled)


