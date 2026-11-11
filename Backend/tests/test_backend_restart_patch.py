from __future__ import annotations

import ast
import unittest

from patches import apply_backend_restart_patch as patch


class BackendRestartPatchTests(unittest.TestCase):
    def test_restart_route_is_admin_authenticated_and_unit_is_allowlisted(self) -> None:
        source = '''import logging
from aiohttp import web

logger = logging.getLogger(__name__)

def json_response(data, status=200):
    return web.Response()

class ApiError(Exception):
    pass

class MiniAppUser:
    role = "father"

def authed_unscoped(handler):
    return handler

def create_app():
    app = web.Application()
    app.router.add_get("/api/health", health)
    return app
'''
        result = patch.build_server_source(source)
        ast.parse(result)
        self.assertIn('app.router.add_post("/api/settings/backend/restart", authed_unscoped(backend_restart))', result)
        self.assertIn('if user.role != "father"', result)
        self.assertIn('"/usr/bin/systemctl",\n                "restart",\n                "miniapp.service"', result)
        self.assertIn('"--on-active=3s"', result)
        self.assertNotIn("request.match_info", result)
        self.assertNotIn("read_json(request)", result)

    def test_duplicate_endpoint_is_rejected(self) -> None:
        source = 'app.router.add_post("/api/settings/backend/restart", handler)\n'
        with self.assertRaises(SystemExit):
            patch.build_server_source(source)


if __name__ == "__main__":
    unittest.main()
