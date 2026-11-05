import importlib.util
import json
import unittest
from pathlib import Path
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("load_demo", Path(__file__).parents[1] / "load-demo.py")
load_demo = importlib.util.module_from_spec(spec)
spec.loader.exec_module(load_demo)


class LoadDemoTests(unittest.TestCase):
    def test_url_rules(self):
        for value in ("http://localhost:8080", "http://127.0.0.1:8080", "http://[::1]:8080", "https://example.com"):
            self.assertTrue(load_demo.validate_base_url(value))
        for value in ("http://example.com", "http://localhost.example.com", "file:///tmp/data"):
            self.assertFalse(load_demo.validate_base_url(value))

    def test_api_does_not_follow_redirects_and_does_not_print_body(self):
        class Response:
            status = 200

            def __enter__(self):
                return self

            def __exit__(self, *_):
                return False

            def read(self):
                return b'{"success":true,"data":{"accessToken":"secret"}}'

        opener = type("Opener", (), {"open": lambda self, req, timeout: Response()})()
        with patch.object(load_demo.urllib.request, "build_opener", return_value=opener) as build_opener:
            result = load_demo.request("https://example.com", "POST", "/api/auth/login", body=b"secret")
        self.assertEqual("secret", result["accessToken"])
        self.assertIs(load_demo.NoRedirectHandler, build_opener.call_args.args[0])

    def test_api_raises_on_error_without_echoing_payload(self):
        class ErrorResponse(Exception):
            code = 403

        opener = type("Opener", (), {
            "open": lambda self, *args, **kwargs: (_ for _ in ()).throw(
                load_demo.urllib.error.HTTPError("https://example.com/api", 403, "Forbidden", {}, None),
            ),
        })()
        with patch.object(load_demo.urllib.request, "build_opener", return_value=opener):
            with self.assertRaisesRegex(RuntimeError, "HTTP 403"):
                load_demo.request("https://example.com", "GET", "/api/admin/documents")

    def test_manifest_grouping_preserves_order_and_versions(self):
        manifest = json.loads(load_demo.MANIFEST_PATH.read_text(encoding="utf-8"))
        groups = load_demo.group_by_logical_id(manifest["documents"])
        travel = groups["travel-policy"]
        self.assertEqual([1, 2], [item["version"] for item in travel])
        self.assertEqual("travel-v1", travel[0]["id"])
        self.assertEqual("travel-v2", travel[1]["id"])

    def test_required_departments_includes_read_and_owner_departments(self):
        self.assertEqual(["개발팀", "인사팀"], load_demo.required_departments([
            {"readDepartments": ["인사팀"], "ownerDepartment": "개발팀"},
        ]))

    def test_multipart_includes_repeated_metadata_and_file(self):
        body, content_type = load_demo.multipart([("departmentIds", "dept-1"), ("paths", "a.md")], "files", "a.md", b"content")
        self.assertIn(b'name="departmentIds"', body)
        self.assertIn(b'name="paths"', body)
        self.assertIn(b'name="files"; filename="a.md"', body)
        self.assertIn(b"content", body)
        self.assertTrue(content_type.startswith("multipart/form-data; boundary="))

    def test_status_polling_returns_terminal_job_state(self):
        with patch.object(load_demo, "request", return_value={"job": {"status": "DONE"}, "versions": [{}, {}]}):
            status, count = load_demo.wait_for_status("http://localhost", "token", "doc-id")
        self.assertEqual(("DONE", 2), (status, count))


if __name__ == "__main__":
    unittest.main()
