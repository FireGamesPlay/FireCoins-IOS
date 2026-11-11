from __future__ import annotations

import asyncio
import sqlite3

from api.config import POINTS_DB_PATH
from api.telegram_client import telegram
from api.proof_store import ProofStoreError, proof_store


async def main() -> None:
    if not telegram.available:
        raise SystemExit("Telegram is not configured; legacy proof migration cannot run.")
    connection = sqlite3.connect(str(POINTS_DB_PATH))
    try:
        rows = connection.execute(
            "SELECT DISTINCT proof_file_id FROM transactions "
            "WHERE proof_file_id IS NOT NULL AND TRIM(proof_file_id) != ''"
        ).fetchall()
    finally:
        connection.close()

    migrated = 0
    already_local = 0
    for (reference_raw,) in rows:
        reference = str(reference_raw or "").strip()
        if not reference:
            continue
        try:
            proof_store.load(reference)
            already_local += 1
            continue
        except ProofStoreError:
            pass

        body, _upstream_content_type = await telegram.download_file(reference)
        proof_store.import_legacy(reference, body)
        proof_store.load(reference)
        migrated += 1

    verified = 0
    for (reference_raw,) in rows:
        reference = str(reference_raw or "").strip()
        if reference:
            proof_store.load(reference)
            verified += 1

    print(f"Distinct DB proof references: {len(rows)}")
    print(f"Newly migrated: {migrated}")
    print(f"Already present and verified: {already_local}")
    print(f"Verified total: {verified}")
    print(f"Legacy mapping entries: {proof_store.count_legacy()}")
    print("points.db was read-only; no rows or schema were changed.")


if __name__ == "__main__":
    asyncio.run(main())
