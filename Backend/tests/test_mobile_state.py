from __future__ import annotations

import json
import stat
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import sys
import types

config_stub = types.ModuleType("api.config")
config_stub.MOBILE_STATE_STORE_PATH = Path("/tmp/firecoins-mobile-state-test.json")
config_stub.POINTS_DB_PATH = Path("/tmp/firecoins-points-test.db")
sys.modules.setdefault("api.config", config_stub)

from api.mobile_state import MobileStateStore


class MobileStateStoreTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp_dir.cleanup)
        self.path = Path(self.temp_dir.name) / ".private" / "mobile_state.json"
        self.store = MobileStateStore(self.path)

    def test_dispute_status_round_trip(self) -> None:
        self.assertEqual(self.store.get_dispute_status(12), "none")
        self.store.set_dispute_status(12, "pending")
        self.assertEqual(self.store.get_dispute_status(12), "pending")
        self.store.set_dispute_status(12, "approved")
        self.assertEqual(self.store.get_dispute_status(12), "approved")

    def test_rejects_unknown_dispute_status(self) -> None:
        with self.assertRaises(ValueError):
            self.store.set_dispute_status(12, "unknown")

    def test_notes_are_visible_to_both_participants_only(self) -> None:
        self.store.append_note(111, 222, "Father", "Hello")
        self.store.append_note(222, 111, "Artemiy", "Hi")
        self.store.append_note(333, 444, "Stranger", "Secret")

        father_notes = self.store.get_notes(111)
        artemiy_notes = self.store.get_notes(222)
        outsider_notes = self.store.get_notes(333)

        self.assertEqual(len(father_notes), 2)
        self.assertEqual(len(artemiy_notes), 2)
        self.assertEqual(outsider_notes, [])
        self.assertEqual({item["text"] for item in father_notes}, {"Hello", "Hi"})

    def test_notes_are_bounded_and_trimmed(self) -> None:
        record = self.store.append_note(111, 222, "  Father  ", "  hello  ")
        self.assertEqual(record["sender_name"], "Father")
        self.assertEqual(record["text"], "hello")
        with self.assertRaises(ValueError):
            self.store.append_note(111, 222, "Father", " ")

    def test_state_file_is_private(self) -> None:
        self.store.append_note(111, 222, "Father", "Hello")
        self.assertTrue(self.path.exists())
        self.assertEqual(stat.S_IMODE(self.path.stat().st_mode), 0o600)
        raw = json.loads(self.path.read_text(encoding="utf-8"))
        self.assertEqual(raw["version"], 1)


if __name__ == "__main__":
    unittest.main()
