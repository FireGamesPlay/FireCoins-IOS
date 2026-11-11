from __future__ import annotations

import ast
import unittest

from patches import apply_settings_sandbox_avatar_patch as patch


class SettingsSandboxPatchTests(unittest.TestCase):
    def test_services_patch_routes_notifications_and_suppresses_telegram_in_sandbox(self) -> None:
        services = """from .telegram_client import TelegramApiError, telegram
from .workflow_store import workflow_store
import aiosqlite

async def _notify(chat_id, text, reply_markup=None):
    pass

async def send_note(user, text_raw):
    return {}

async def helper():
    db = await aiosqlite.connect(str(POINTS_DB_PATH))
"""
        built = patch.build_services_source(services)
        ast.parse(built)
        self.assertIn("current_environment", built)
        self.assertIn("note_published", built)
        self.assertIn("dispute_requested", built)
        self.assertIn("dispute_resolved", built)
        self.assertIn("withdrawal_requested", built)
        self.assertIn("withdrawal_resolved", built)
        self.assertIn("points_changed", built)
        self.assertIn("if not is_sandbox and telegram.available", built)
        self.assertIn("[ТЕСТ]", built)


if __name__ == "__main__":
    unittest.main()
