from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from api.push_store import PushStore


class PushStoreTests(unittest.TestCase):
    def test_tokens_are_bound_to_server_identity_and_can_be_revoked(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            store = PushStore(Path(temp) / ".private" / "push.json")
            store.register(222, "artemiy", "fcm-token-a", "android", "device-a")
            store.register(222, "artemiy", "fcm-token-b", "ios", "device-b")
            self.assertEqual(len(store.devices_for(222)), 2)
            self.assertEqual(store.devices_for(111), [])
            self.assertFalse(store.unregister(111, "fcm-token-a"))
            self.assertTrue(store.unregister(222, "fcm-token-a"))
            self.assertEqual(len(store.devices_for(222)), 1)

    def test_rejects_invalid_role_platform_and_token(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            store = PushStore(Path(temp) / "push.json")
            with self.assertRaises(Exception):
                store.register(999, "unknown", "token", "android", "device")
            with self.assertRaises(Exception):
                store.register(222, "artemiy", "", "android", "device")


if __name__ == "__main__":
    unittest.main()
