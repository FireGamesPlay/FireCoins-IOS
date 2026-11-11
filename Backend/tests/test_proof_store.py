from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from api.proof_store import ProofStore, ProofStoreError, detect_image_content_type

JPEG = bytes.fromhex("ffd8ffe000104a46494600010100000100010000") + b"firecoins-test" + bytes.fromhex("ffd9")
PNG = bytes.fromhex("89504e470d0a1a0a") + b"firecoins-test"


class ProofStoreTests(unittest.TestCase):
    def test_detects_supported_images_from_bytes(self) -> None:
        self.assertEqual(detect_image_content_type(JPEG), "image/jpeg")
        self.assertEqual(detect_image_content_type(PNG), "image/png")
        self.assertIsNone(detect_image_content_type(b"application/octet-stream"))

    def test_new_proofs_are_stored_locally_and_can_be_loaded(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp) / "private" / "proofs"
            store = ProofStore(root)
            reference = store.save(JPEG)
            body, content_type = store.load(reference)
            self.assertTrue(reference.startswith("fcproof:"))
            self.assertEqual(body, JPEG)
            self.assertEqual(content_type, "image/jpeg")
            self.assertEqual(root.stat().st_mode & 0o777, 0o700)

    def test_legacy_references_are_mapped_without_changing_reference(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            store = ProofStore(Path(temp) / "proofs")
            legacy = "legacy-telegram-file-id"
            store.import_legacy(legacy, JPEG)
            self.assertEqual(store.load(legacy), (JPEG, "image/jpeg"))
            self.assertTrue(store.has_legacy(legacy))

    def test_rejects_non_images_and_oversized_images(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            store = ProofStore(Path(temp) / "proofs")
            with self.assertRaises(ProofStoreError):
                store.save(b"not an image")
            with self.assertRaises(ProofStoreError):
                store.save(JPEG * 4, max_bytes=10)

    def test_corrupt_index_is_not_silently_reset(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp) / "proofs"
            root.mkdir()
            (root / "legacy-index.json").write_text("{broken", encoding="utf-8")
            with self.assertRaises(ProofStoreError):
                ProofStore(root).load("legacy-reference")


if __name__ == "__main__":
    unittest.main()
