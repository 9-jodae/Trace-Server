import importlib.util
import sys
import types
import unittest
from pathlib import Path

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

    def test_scenario_groups_cover_requested_cases(self):
        eval_set = smoke.load_eval_set()
        cases = [case for _, tags in smoke.SCENARIO_GROUPS for case in smoke.scenario_cases(eval_set, tags)]
        self.assertEqual({"Q01", "Q02", "Q03", "Q04", "Q05", "Q06"}, {case["id"] for case in cases})

    def test_scenario_accounts_include_both_access_sides(self):
        eval_set = smoke.load_eval_set()
        cases = smoke.scenario_cases(eval_set, {"권한별 연차"})
        accounts = smoke.scenario_accounts(cases)
        self.assertIn("hr.staff@local.docs199", accounts)
        self.assertIn("hr.manager@local.docs199", accounts)

    def test_evidence_visibility_requires_every_evidence_document(self):
        case = {"evidence": [{"documentId": "public"}, {"documentId": "restricted"}]}
        paths = {"public": "public.md", "restricted": "restricted.md"}

        def fake_api(session, base, method, path, token=None, json_body=None, timeout=30):
            return {"items": [{"id": "public", "relativePath": "public.md"}]}

        from unittest.mock import patch
        with patch.object(smoke, "api", side_effect=fake_api):
            visible, paths_seen, ids_seen = smoke.check_evidence_visibility(None, "http://localhost", "token", paths, case)
        self.assertFalse(visible)
        self.assertEqual({"public.md"}, paths_seen)
        self.assertEqual({"public"}, ids_seen)

    def test_list_all_documents_follows_all_pages(self):
        responses = [
            {"items": [{"id": "doc-1"}], "page": 0, "size": 100, "totalElements": 3, "totalPages": 2},
            {"items": [{"id": "doc-2"}, {"id": "doc-3"}], "page": 1, "size": 100, "totalElements": 3, "totalPages": 2},
        ]
        calls = []

        def fake_api(session, base, method, path, token=None, json_body=None, timeout=30):
            calls.append(path)
            return responses[len(calls) - 1]

        from unittest.mock import patch
        with patch.object(smoke, "api", side_effect=fake_api):
            result = smoke.list_all_documents(None, "http://localhost", "token")

        self.assertEqual(["/api/documents?page=0&size=100", "/api/documents?page=1&size=100"], calls)
        self.assertEqual(["doc-1", "doc-2", "doc-3"], [item["id"] for item in result["items"]])
        self.assertEqual(3, result["totalElements"])


if __name__ == "__main__":
    unittest.main()
