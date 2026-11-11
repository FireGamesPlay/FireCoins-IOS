from __future__ import annotations

import asyncio
import sqlite3
import tempfile
import unittest
from pathlib import Path

import sys
import types

config_stub = types.ModuleType("api.config")
config_stub.ARTEMIY_ID = 222
config_stub.FATHER_ID = 111
config_stub.POINTS_DB_PATH = Path("/tmp/firecoins-points-environment-test.db")
config_stub.MOBILE_AUTH_STORE_PATH = Path("/tmp/firecoins-mobile-auth-environment-test.json")
config_stub.MOBILE_STATE_STORE_PATH = Path("/tmp/firecoins-mobile-state-environment-test.json")
config_stub.get_role = lambda user_id: {111: "father", 222: "artemiy"}.get(user_id, "unknown")
import api
sys.modules["api.config"] = config_stub
api.config = config_stub

from api.environment import DatabaseMode, DatabaseModeError


class DatabaseModeTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        root = Path(self.temp.name)
        self.production = root / "points.db"
        connection = sqlite3.connect(self.production)
        connection.executescript(
            """
            CREATE TABLE balance (user_id INTEGER PRIMARY KEY, balance INTEGER NOT NULL DEFAULT 0);
            CREATE TABLE transactions (
                id INTEGER PRIMARY KEY, from_id INTEGER, to_id INTEGER,
                type TEXT, amount INTEGER, reason TEXT, timestamp TEXT,
                disputed INTEGER DEFAULT 0, proof_file_id TEXT
            );
            CREATE TABLE withdrawals (
                id INTEGER PRIMARY KEY, user_id INTEGER, amount INTEGER,
                timestamp TEXT, status TEXT DEFAULT 'pending'
            );
            CREATE INDEX idx_transactions_to_id ON transactions(to_id);
            INSERT INTO balance(user_id, balance) VALUES (222, 77);
            INSERT INTO transactions(from_id,to_id,type,amount,reason) VALUES (111,222,'add',77,'prod-only');
            INSERT INTO withdrawals(user_id,amount) VALUES (222,5);
            """
        )
        connection.commit()
        connection.close()
        self.private = root / ".private"
        self.manager = DatabaseMode(self.production, self.private, (111, 222))

    async def test_sandbox_starts_empty_and_never_copies_production_rows(self) -> None:
        before = sqlite3.connect(self.production)
        prod_balance = before.execute("SELECT balance FROM balance WHERE user_id=222").fetchone()[0]
        prod_transactions = before.execute("SELECT COUNT(*) FROM transactions").fetchone()[0]
        prod_withdrawals = before.execute("SELECT COUNT(*) FROM withdrawals").fetchone()[0]
        before.close()

        result = await self.manager.set_sandbox(True)
        self.assertEqual(result["mode"], "sandbox")
        self.assertTrue(result["is_sandbox"])
        sandbox = sqlite3.connect(self.manager.sandbox_database)
        self.assertEqual(sandbox.execute("SELECT COUNT(*) FROM transactions").fetchone()[0], 0)
        self.assertEqual(sandbox.execute("SELECT COUNT(*) FROM withdrawals").fetchone()[0], 0)
        self.assertEqual(sandbox.execute("SELECT balance FROM balance WHERE user_id=222").fetchone()[0], 0)
        self.assertEqual(sandbox.execute("PRAGMA integrity_check").fetchone()[0], "ok")
        sandbox.close()

        after = sqlite3.connect(self.production)
        self.assertEqual(after.execute("SELECT balance FROM balance WHERE user_id=222").fetchone()[0], prod_balance)
        self.assertEqual(after.execute("SELECT COUNT(*) FROM transactions").fetchone()[0], prod_transactions)
        self.assertEqual(after.execute("SELECT COUNT(*) FROM withdrawals").fetchone()[0], prod_withdrawals)
        after.close()

    async def test_switching_off_deletes_all_sandbox_data_and_returns_to_production(self) -> None:
        await self.manager.set_sandbox(True)
        self.manager.sandbox_sidecars.mkdir(parents=True, exist_ok=True)
        (self.manager.sandbox_sidecars / "test-note.json").write_text("sandbox", encoding="utf-8")
        result = await self.manager.set_sandbox(False)
        self.assertEqual(result["mode"], "production")
        self.assertFalse(self.manager.sandbox_root.exists())
        self.assertEqual(await self.manager.status(), {
            "mode": "production",
            "is_sandbox": False,
            "label": "Рабочая среда",
        })

    async def test_requests_hold_the_environment_lock_while_in_flight(self) -> None:
        await self.manager.set_sandbox(True)
        started = asyncio.Event()
        release = asyncio.Event()

        async def active_request() -> None:
            async with self.manager.operation() as snapshot:
                self.assertEqual(snapshot.mode, "sandbox")
                started.set()
                await release.wait()

        operation = asyncio.create_task(active_request())
        await started.wait()
        switch = asyncio.create_task(self.manager.set_sandbox(False))
        await asyncio.sleep(0.03)
        self.assertFalse(switch.done())
        release.set()
        await operation
        result = await switch
        self.assertEqual(result["mode"], "production")

    async def test_reset_current_sandbox_does_not_touch_production(self) -> None:
        await self.manager.set_sandbox(True)
        sandbox = sqlite3.connect(self.manager.sandbox_database)
        sandbox.execute("INSERT INTO transactions(from_id,to_id,type,amount,reason) VALUES (111,222,'add',1,'test')")
        sandbox.execute("UPDATE balance SET balance=1 WHERE user_id=222")
        sandbox.commit()
        sandbox.close()

        result = await self.manager.reset_current()
        self.assertEqual(result["mode"], "sandbox")
        sandbox = sqlite3.connect(self.manager.sandbox_database)
        self.assertEqual(sandbox.execute("SELECT COUNT(*) FROM transactions").fetchone()[0], 0)
        self.assertEqual(sandbox.execute("SELECT balance FROM balance WHERE user_id=222").fetchone()[0], 0)
        sandbox.close()

        production = sqlite3.connect(self.production)
        self.assertEqual(production.execute("SELECT balance FROM balance WHERE user_id=222").fetchone()[0], 77)
        self.assertEqual(production.execute("SELECT COUNT(*) FROM transactions").fetchone()[0], 1)
        production.close()

    async def test_corrupt_sandbox_state_fails_closed(self) -> None:
        self.private.mkdir(parents=True, exist_ok=True)
        self.manager.state_path.write_text("{broken", encoding="utf-8")
        with self.assertRaises(DatabaseModeError):
            await self.manager.status()


if __name__ == "__main__":
    unittest.main()
