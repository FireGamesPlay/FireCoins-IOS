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
config_stub.ARTEMIY_ID = 222
config_stub.FATHER_ID = 111
config_stub.MOBILE_AUTH_STORE_PATH = Path("/tmp/firecoins-mobile-auth-test.json")
config_stub.POINTS_DB_PATH = Path("/tmp/firecoins-points-test.db")
config_stub.get_role = lambda user_id: {111: "father", 222: "artemiy"}.get(user_id, "unknown")
sys.modules.setdefault("api.config", config_stub)

from api import mobile_auth


class MobileAuthStoreTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp_dir.cleanup)
        self.store_path = Path(self.temp_dir.name) / ".private" / "mobile_auth.json"
        self.store = mobile_auth.MobileAuthStore(self.store_path)
        self.role_by_id = {111: "father", 222: "artemiy"}
        self.role_patch = patch.object(
            mobile_auth,
            "get_role",
            side_effect=lambda user_id: self.role_by_id.get(user_id, "unknown"),
        )
        self.role_patch.start()
        self.addCleanup(self.role_patch.stop)
        self.ids_patch = patch.multiple(mobile_auth, FATHER_ID=111, ARTEMIY_ID=222)
        self.ids_patch.start()
        self.addCleanup(self.ids_patch.stop)

    def bootstrap(self) -> dict[str, str]:
        return self.store.bootstrap_invitations()

    def test_bootstrap_creates_two_private_one_time_codes(self) -> None:
        codes = self.bootstrap()
        self.assertEqual(set(codes), {"father", "artemiy"})
        self.assertTrue(self.store_path.exists())
        self.assertEqual(stat.S_IMODE(self.store_path.stat().st_mode), 0o600)
        stored = self.store_path.read_text(encoding="utf-8")
        self.assertNotIn(codes["father"], stored)
        self.assertNotIn(codes["artemiy"], stored)
        state = json.loads(stored)
        self.assertEqual({row["role"] for row in state["invitations"]}, {"father", "artemiy"})

    def test_invitation_is_bound_to_role_and_can_only_be_used_once(self) -> None:
        codes = self.bootstrap()
        user_id, role, tokens = self.store.redeem_invitation(codes["artemiy"], "127.0.0.1")
        self.assertEqual((user_id, role), (222, "artemiy"))
        self.assertEqual(tokens["token_type"], "Bearer")
        self.assertGreater(tokens["access_expires_in"], 0)

        with self.assertRaises(mobile_auth.MobileAuthError):
            self.store.redeem_invitation(codes["artemiy"], "127.0.0.1")

    def test_access_token_authentication_and_refresh_rotation(self) -> None:
        codes = self.bootstrap()
        user_id, role, tokens = self.store.redeem_invitation(codes["father"], "127.0.0.1")
        self.assertEqual((user_id, role), (111, "father"))
        self.assertEqual(
            self.store.authenticate_access_token(tokens["access_token"]),
            (111, "father"),
        )

        rotated = self.store.refresh_session(tokens["refresh_token"])
        self.assertIsNone(self.store.authenticate_access_token(tokens["access_token"]))
        self.assertIsNotNone(self.store.authenticate_access_token(rotated["access_token"]))

        with self.assertRaises(mobile_auth.MobileAuthError):
            self.store.refresh_session(tokens["refresh_token"])

    def test_logout_revokes_session(self) -> None:
        codes = self.bootstrap()
        _, _, tokens = self.store.redeem_invitation(codes["father"], "127.0.0.1")
        self.assertTrue(self.store.revoke_refresh_token(tokens["refresh_token"]))
        self.assertIsNone(self.store.authenticate_access_token(tokens["access_token"]))
        self.assertFalse(self.store.revoke_refresh_token(tokens["refresh_token"]))

    def test_invalid_attempts_are_rate_limited_and_persisted(self) -> None:
        self.bootstrap()
        invalid = "FC-AAAA-BBBB-CCCC-DDDD"
        for _ in range(8):
            with self.assertRaises(mobile_auth.MobileAuthError):
                self.store.redeem_invitation(invalid, "203.0.113.10")

        with self.assertRaises(mobile_auth.MobileAuthError) as raised:
            self.store.redeem_invitation(invalid, "203.0.113.10")
        self.assertEqual(raised.exception.status, 429)

    def test_bootstrap_refuses_to_overwrite_existing_codes(self) -> None:
        self.bootstrap()
        with self.assertRaises(RuntimeError):
            self.store.bootstrap_invitations()
        self.assertEqual(len(json.loads(self.store_path.read_text())["invitations"]), 2)

    def test_auth_store_cannot_overwrite_business_database(self) -> None:
        with self.assertRaises(ValueError):
            mobile_auth.MobileAuthStore(Path("/tmp/firecoins-points-test.db"))


if __name__ == "__main__":
    unittest.main()
