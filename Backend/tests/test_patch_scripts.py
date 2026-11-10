from __future__ import annotations

import ast
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from patches import apply_workflow_disputes_patch as workflow_patch


class WorkflowPatchTests(unittest.TestCase):
    def test_patch_builds_valid_api_and_bot_sources_without_touching_database(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            project = Path(temporary)
            webapp = project / "webapp"
            api = webapp / "api"
            api.mkdir(parents=True)
            bot = project / "Bot.py"

            (api / "workflow_store.py").write_text("# sidecar stub\n", encoding="utf-8")
            services = """from .telegram_client import TelegramApiError, telegram

def _transaction_dict(row):
    return {}

async def create_dispute(user, transaction_id_raw):
    return {}

async def create_points_transaction(user, action, amount_raw, reason_raw, proof_file_id=None):
    reason = str(reason_raw or "").strip()
    if action == "remove" and reason in PROOF_REQUIRED_REASONS and not proof_file_id:
        raise ApiError("Photo proof is required for this reason.", status=400, code="proof_required")
    return {}

async def resolve_dispute(user, transaction_id_raw, action):
    return {}

async def get_casino_status(user_id):
    return {}

async def send_note(user, text_raw):
    return {}
"""
            server = """from .services import (
    get_history,
)

async def me(_: web.Request, user: MiniAppUser) -> web.Response:
    return {}

def create_app():
    app.router.add_get("/api/me", authed(me))
"""
            bot_source = """from telegram.error import BadRequest

TOKEN = os.environ.get("TG_BOT_TOKEN", "old-token")

async def handle_dispute_response(update, context):
    pass

async def handle_withdraw_response(update, context):
    pass

async def dispute_callback_handler(update, context):
    pass

async def leave_note_text(update, context):
    pass

async def show_history():
            dispute = " ⚠️" if row[5] else ""
"""
            (api / "services.py").write_text(services, encoding="utf-8")
            (api / "server.py").write_text(server, encoding="utf-8")
            bot.write_text(bot_source, encoding="utf-8")

            with (
                patch.object(workflow_patch, "ROOT", webapp),
                patch.object(workflow_patch, "API", api),
                patch.object(workflow_patch, "BOT", bot),
            ):
                changes = workflow_patch.build_changes()

            self.assertEqual(set(changes), {api / "services.py", api / "server.py", bot})
            for source in changes.values():
                ast.parse(source)

            patched_bot = changes[bot]
            self.assertNotIn("old-token", patched_bot)
            self.assertIn('TOKEN = os.environ.get("TG_BOT_TOKEN", "").strip()', patched_bot)
            self.assertIn("workflow_store.append_note", patched_bot)
            self.assertIn("disputed = 2", patched_bot)
            self.assertIn("disputed = 3", patched_bot)
            self.assertIn("BEGIN IMMEDIATE", patched_bot)
            self.assertIn("UPDATE withdrawals SET status = 'approved' WHERE id = ? AND status = 'pending'", patched_bot)
            self.assertIn("ORDER BY timestamp DESC, id DESC", patched_bot)
            self.assertIn("transaction_unavailable", changes[api / "services.py"])
            self.assertIn('{"неубранныетрусы", "оставленныетапки"}', changes[api / "services.py"])

            patched_server = changes[api / "server.py"]
            self.assertIn('app.router.add_get("/api/notes", authed(notes))', patched_server)

            patched_services = changes[api / "services.py"]
            service_note_block = patched_services.split("async def send_note", 1)[1].split("async def get_notes", 1)[0]
            self.assertLess(
                service_note_block.index("workflow_store.append_note"),
                service_note_block.index("telegram.send_message"),
            )

            bot_note_block = patched_bot.split("async def leave_note_text", 1)[1]
            self.assertLess(
                bot_note_block.index("workflow_store.append_note"),
                bot_note_block.index("context.bot.send_message"),
            )

            self.assertFalse((project / "points.db").exists())


if __name__ == "__main__":
    unittest.main()
