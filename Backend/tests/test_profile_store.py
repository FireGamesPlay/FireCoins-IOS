from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from api.profile_store import ProfileStore, ProfileStoreError

JPEG = bytes.fromhex("ffd8ffe000104a46494600010100000100010000") + b"avatar-test" + bytes.fromhex("ffd9")


class ProfileStoreTests(unittest.TestCase):
    def test_avatar_round_trip_is_private_and_role_scoped(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp) / ".private" / "profiles"
            store = ProfileStore(root)
            result = store.save(222, JPEG)
            self.assertEqual(result["content_type"], "image/jpeg")
            self.assertEqual(store.load(222), (JPEG, "image/jpeg"))
            with self.assertRaises(ProfileStoreError):
                store.load(111)
            self.assertEqual(root.stat().st_mode & 0o777, 0o700)
            self.assertTrue(all(path.stat().st_mode & 0o777 == 0o600 for path in root.iterdir() if path.is_file()))

    def test_replacing_avatar_removes_old_blob(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp) / "profiles"
            store = ProfileStore(root)
            store.save(222, JPEG)
            old = list(root.glob("*.blob"))
            second = JPEG + b"new"
            store.save(222, second)
            self.assertEqual(store.load(222)[0], second)
            self.assertEqual(len(list(root.glob("*.blob"))), 1)
            self.assertFalse(old[0].exists())

    def test_non_image_and_oversized_avatar_are_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            store = ProfileStore(Path(temp) / "profiles")
            with self.assertRaises(ProfileStoreError):
                store.save(222, b"not an image")
            with self.assertRaises(ProfileStoreError):
                store.save(222, JPEG + b"x" * (4 * 1024 * 1024))


if __name__ == "__main__":
    unittest.main()
