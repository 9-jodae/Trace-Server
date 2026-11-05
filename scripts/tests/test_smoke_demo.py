import contextlib
import importlib.util
import io
import os
import sys
import types
import unittest
from pathlib import Path
from unittest.mock import patch

sys.modules.setdefault("requests", types.ModuleType("requests"))
spec = importlib.util.spec_from_file_location("smoke_demo", Path(__file__).parents[1] / "smoke-demo.py")
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)


class SmokeDemoTests(unittest.TestCase):
    def test_url_rules(self):
        for value in ("http://localhost:8080", "http://127.0.0.1:8080", "http://[::1]:8080", "https://example.com"):
            self.assertTrue(smoke.validate_base_url(value))
        for value in ("http://example.com", "http://localhost.example.com", "file:///tmp/data"):
            self.assertFalse(smoke.validate_base_url(value))

    def test_redirects_are_disabled_for_sensitive_requests(self):
        class Session:
            def request(self, *args, **kwargs):
                self.options = kwargs
                return types.SimpleNamespace(json=lambda: {"success": True, "data": {}}, status_code=200)

        session = Session()
        smoke.api(session, "http://localhost:8080", "POST", "/api/auth/login", json_body={"password": "test"})
        self.assertFalse(session.options["allow_redirects"])

    def test_non_mock_without_sources_fails(self):
        def fake_api(session, base, method, path, token=None, json_body=None, timeout=30):
            if path == "/api/health":
                return {"status": "UP"}
            if path == "/api/auth/login":
                return {"accessToken": "test"}
            if path == "/api/users/me":
                return {}
            if path.startswith("/api/documents"):
                return {"totalElements": 1}
            return {"answer": "answer", "sources": []}

        with (
            patch.object(smoke, "api", side_effect=fake_api),
            patch.object(smoke.requests, "Session", return_value=object(), create=True),
            patch.dict(os.environ, {"DEMO_PASSWORD": "test"}),
            patch.object(sys, "argv", ["smoke-demo.py", "http://localhost:8080"]),
            contextlib.redirect_stdout(io.StringIO()),
            self.assertRaises(SystemExit) as error,
        ):
            smoke.main()
        self.assertEqual(1, error.exception.code)


if __name__ == "__main__":
    unittest.main()
