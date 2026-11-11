from __future__ import annotations

import contextlib
import fcntl
import hashlib
import json
import os
import re
import tempfile
import uuid
from contextlib import contextmanager
from pathlib import Path
from typing import Any, Iterator

MAX_PROOF_BYTES = 12 * 1024 * 1024
LOCAL_PROOF_PREFIX = "fcproof:"
_LOCAL_ID_RE = re.compile(r"^[0-9a-f]{32}$")


class ProofStoreError(RuntimeError):
    pass


def detect_image_content_type(data: bytes) -> str | None:
    if data.startswith(bytes.fromhex("ffd8ff")):
        return "image/jpeg"
    if data.startswith(bytes.fromhex("89504e470d0a1a0a")):
        return "image/png"
    if data[:6] in (b"GIF87a", b"GIF89a"):
        return "image/gif"
    if len(data) >= 12 and data[:4] == b"RIFF" and data[8:12] == b"WEBP":
        return "image/webp"
    if len(data) >= 12 and data[4:8] == b"ftyp":
        brands = data[8:40]
        if any(brand in brands for brand in (b"avif", b"avis")):
            return "image/avif"
        if any(brand in brands for brand in (b"heic", b"heix", b"hevc", b"hevx", b"mif1", b"msf1")):
            return "image/heic"
    return None


class ProofStore:
    """Private Fire Coins proof storage; never uses Telegram or points.db."""

    def __init__(self, root: Path | str | None = None):
        if root is None:
            project_root = Path(__file__).resolve().parents[2]
            root = os.environ.get("FIRECOINS_PROOF_STORAGE_DIR", project_root / ".private" / "proofs")
        self.root = Path(root).expanduser().resolve()
        self.index_path = self.root / "legacy-index.json"
        self.lock_path = self.root / ".store.lock"

    def _prepare(self) -> None:
        self.root.mkdir(parents=True, exist_ok=True, mode=0o700)
        with contextlib.suppress(OSError):
            os.chmod(self.root, 0o700)

    @contextmanager
    def _locked(self, write: bool = False) -> Iterator[dict[str, Any]]:
        self._prepare()
        fd = os.open(self.lock_path, os.O_CREAT | os.O_RDWR, 0o600)
        try:
            os.fchmod(fd, 0o600)
            fcntl.flock(fd, fcntl.LOCK_EX if write else fcntl.LOCK_SH)
            index = self._load_index()
            yield index
            if write:
                self._save_index(index)
        finally:
            with contextlib.suppress(OSError):
                fcntl.flock(fd, fcntl.LOCK_UN)
            os.close(fd)

    def _load_index(self) -> dict[str, Any]:
        if not self.index_path.exists():
            return {"version": 1, "legacy": {}}
        try:
            value = json.loads(self.index_path.read_text(encoding="utf-8"))
        except (OSError, UnicodeError, json.JSONDecodeError) as exc:
            raise ProofStoreError("Proof storage index is unreadable; refusing to reset it.") from exc
        if not isinstance(value, dict) or value.get("version") != 1 or not isinstance(value.get("legacy"), dict):
            raise ProofStoreError("Proof storage index has an unsupported format.")
        return value

    def _save_index(self, value: dict[str, Any]) -> None:
        fd, temporary = tempfile.mkstemp(prefix=".legacy-index-", suffix=".tmp", dir=str(self.root))
        try:
            os.fchmod(fd, 0o600)
            with os.fdopen(fd, "w", encoding="utf-8") as stream:
                json.dump(value, stream, ensure_ascii=False, separators=(",", ":"))
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temporary, self.index_path)
            os.chmod(self.index_path, 0o600)
            directory_fd = os.open(self.root, os.O_RDONLY)
            try:
                os.fsync(directory_fd)
            finally:
                os.close(directory_fd)
        except Exception:
            with contextlib.suppress(OSError):
                os.unlink(temporary)
            raise

    def _write_blob(self, data: bytes) -> str:
        key = uuid.uuid4().hex
        destination = self.root / f"{key}.blob"
        fd, temporary = tempfile.mkstemp(prefix=".proof-", suffix=".tmp", dir=str(self.root))
        try:
            os.fchmod(fd, 0o600)
            with os.fdopen(fd, "wb") as stream:
                stream.write(data)
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temporary, destination)
            os.chmod(destination, 0o600)
        except Exception:
            with contextlib.suppress(OSError):
                os.unlink(temporary)
            raise
        return key

    def save(self, data: bytes, max_bytes: int = MAX_PROOF_BYTES) -> str:
        body = bytes(data or b"")
        if not body:
            raise ProofStoreError("Proof image is empty.")
        if len(body) > max_bytes:
            raise ProofStoreError("Proof image is too large.")
        if detect_image_content_type(body) is None:
            raise ProofStoreError("Uploaded proof is not a supported image.")
        with self._locked():
            key = self._write_blob(body)
        return f"{LOCAL_PROOF_PREFIX}{key}"

    def has_legacy(self, legacy_reference: str) -> bool:
        reference = str(legacy_reference or "").strip()
        if not reference:
            return False
        digest = hashlib.sha256(reference.encode("utf-8")).hexdigest()
        with self._locked() as index:
            entry = index["legacy"].get(digest)
            if not isinstance(entry, dict):
                return False
            key = entry.get("key")
            return isinstance(key, str) and bool(_LOCAL_ID_RE.fullmatch(key)) and (self.root / f"{key}.blob").is_file()

    def import_legacy(self, legacy_reference: str, data: bytes, max_bytes: int = MAX_PROOF_BYTES) -> None:
        reference = str(legacy_reference or "").strip()
        body = bytes(data or b"")
        if not reference:
            raise ProofStoreError("Legacy proof reference is empty.")
        if not body or len(body) > max_bytes:
            raise ProofStoreError("Legacy proof has invalid size.")
        content_type = detect_image_content_type(body)
        if content_type is None:
            raise ProofStoreError("Legacy proof is not a supported image.")
        digest = hashlib.sha256(reference.encode("utf-8")).hexdigest()
        with self._locked(write=True) as index:
            existing = index["legacy"].get(digest)
            if isinstance(existing, dict):
                existing_key = existing.get("key")
                if isinstance(existing_key, str) and _LOCAL_ID_RE.fullmatch(existing_key) and (self.root / f"{existing_key}.blob").is_file():
                    return
            key = self._write_blob(body)
            index["legacy"][digest] = {
                "key": key,
                "content_type": content_type,
                "size": len(body),
            }

    def load(self, reference: str, max_bytes: int = MAX_PROOF_BYTES) -> tuple[bytes, str]:
        value = str(reference or "").strip()
        if not value:
            raise ProofStoreError("Proof reference is empty.")
        if value.startswith(LOCAL_PROOF_PREFIX):
            key = value[len(LOCAL_PROOF_PREFIX):]
            if not _LOCAL_ID_RE.fullmatch(key):
                raise ProofStoreError("Proof reference is invalid.")
        else:
            digest = hashlib.sha256(value.encode("utf-8")).hexdigest()
            with self._locked() as index:
                entry = index["legacy"].get(digest)
                if not isinstance(entry, dict):
                    raise ProofStoreError("Legacy proof has not been migrated to Fire Coins storage.")
                key = entry.get("key", "")
                if not isinstance(key, str) or not _LOCAL_ID_RE.fullmatch(key):
                    raise ProofStoreError("Legacy proof index entry is invalid.")

        path = self.root / f"{key}.blob"
        try:
            body = path.read_bytes()
        except OSError as exc:
            raise ProofStoreError("Proof image is missing from Fire Coins storage.") from exc
        if not body or len(body) > max_bytes:
            raise ProofStoreError("Stored proof image has invalid size.")
        content_type = detect_image_content_type(body)
        if content_type is None:
            raise ProofStoreError("Stored proof is not a supported image.")
        return body, content_type

    def count_legacy(self) -> int:
        with self._locked() as index:
            return len(index["legacy"])


proof_store = ProofStore()
