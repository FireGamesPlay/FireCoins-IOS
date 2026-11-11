from __future__ import annotations

import ast
import os
import shutil
import tempfile
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path.cwd()
API = ROOT / "api"
SERVER = API / "server.py"
SOURCE_MODULE = Path(__file__).resolve().parents[1] / "api" / "proof_store.py"


def replace_function(source: str, name: str, replacement: str) -> str:
    tree = ast.parse(source)
    nodes = [
        node for node in tree.body
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)) and node.name == name
    ]
    if len(nodes) != 1 or not isinstance(nodes[0], ast.AsyncFunctionDef):
        raise SystemExit(f"Expected exactly one async function {name!r}; found {len(nodes)}.")
    node = nodes[0]
    lines = source.splitlines(keepends=True)
    lines[node.lineno - 1:node.end_lineno] = [replacement.rstrip() + "\n\n"]
    return "".join(lines)


def replace_once(source: str, old: str, new: str, label: str) -> str:
    count = source.count(old)
    if count != 1:
        raise SystemExit(f"Expected one exact match for {label}, found {count}.")
    return source.replace(old, new, 1)


def atomic_write(path: Path, value: str) -> None:
    fd, temporary = tempfile.mkstemp(prefix=f".{path.name}.", dir=str(path.parent))
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as stream:
            stream.write(value)
            stream.flush()
            os.fsync(stream.fileno())
        os.chmod(temporary, path.stat().st_mode & 0o777)
        os.replace(temporary, path)
    except Exception:
        try:
            os.unlink(temporary)
        except OSError:
            pass
        raise


def build_server_source(source: str) -> str:
    if "from .proof_store import ProofStoreError, proof_store" in source:
        raise SystemExit("Proof storage import is already present.")
    source = replace_once(
        source,
        "from .mobile_auth import MobileAuthError, mobile_auth_store\n",
        "from .mobile_auth import MobileAuthError, mobile_auth_store\nfrom .proof_store import ProofStoreError, proof_store\n",
        "proof store import",
    )

    upload_proof = '''async def upload_proof(request: web.Request, user: MiniAppUser) -> web.Response:
    if user.role != "father":
        raise ApiError("Only Father can upload proof.", status=403, code="forbidden")

    reader = await request.multipart()
    file_bytes = b""
    async for field in reader:
        if field.name != "photo":
            continue
        chunks: list[bytes] = []
        size = 0
        while True:
            chunk = await field.read_chunk()
            if not chunk:
                break
            size += len(chunk)
            if size > MAX_UPLOAD_BYTES:
                raise ApiError("Proof image is too large.", status=413, code="upload_too_large")
            chunks.append(chunk)
        file_bytes = b"".join(chunks)

    try:
        proof_reference = proof_store.save(file_bytes, max_bytes=MAX_UPLOAD_BYTES)
    except ProofStoreError as exc:
        raise ApiError(str(exc), status=400, code="invalid_upload") from exc

    return json_response({"ok": True, "proof_file_id": proof_reference})'''

    proof_file = '''async def proof_file(request: web.Request, user: MiniAppUser) -> web.StreamResponse:
    proof_reference = await get_transaction_proof_file_id(user, request.match_info["transaction_id"])
    try:
        body, content_type = proof_store.load(proof_reference)
    except ProofStoreError as exc:
        logger.exception("Fire Coins proof storage lookup failed")
        raise ApiError("Proof image is not available in Fire Coins storage.", status=503, code="proof_storage_unavailable") from exc
    return web.Response(
        body=body,
        content_type=content_type,
        headers={
            "Cache-Control": "private, max-age=300",
            "Content-Disposition": 'inline; filename="proof"',
            "X-Content-Type-Options": "nosniff",
        },
    )'''

    source = replace_function(source, "upload_proof", upload_proof)
    source = replace_function(source, "proof_file", proof_file)
    ast.parse(source)
    if "telegram.send_photo" in source or "telegram.download_file" in source:
        raise SystemExit("Telegram proof dependency remains after patch generation.")
    return source


def build_changes() -> dict[Path, str]:
    if not SOURCE_MODULE.is_file():
        raise SystemExit(f"Missing proof store module source: {SOURCE_MODULE}")
    return {SERVER: build_server_source(SERVER.read_text(encoding="utf-8"))}


def apply() -> Path:
    changes = build_changes()
    backup_root = ROOT.parent / ".backups"
    backup_root.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(backup_root, 0o700)
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    backup_dir = backup_root / f"firecoins-local-proof-storage-{stamp}"
    backup_dir.mkdir(mode=0o700)
    shutil.copy2(SERVER, backup_dir / "server.py")

    shutil.copy2(SOURCE_MODULE, API / "proof_store.py")
    os.chmod(API / "proof_store.py", 0o640)
    for path, value in changes.items():
        atomic_write(path, value)

    print("Fire Coins proof upload/download now uses private local storage.")
    print(f"Backup: {backup_dir}")
    print("points.db was not modified.")
    return backup_dir


if __name__ == "__main__":
    apply()
