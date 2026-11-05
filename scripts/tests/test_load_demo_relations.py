import importlib.util
import unittest
from pathlib import Path


SCRIPT = Path(__file__).parents[1] / "load-demo-relations.py"
SPEC = importlib.util.spec_from_file_location("load_demo_relations", SCRIPT)
LOADER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(LOADER)


class BuildRelationsTests(unittest.TestCase):
    def test_reciprocal_conflicts_collapse_but_references_keep_direction(self):
        sources = {("LOCAL", "local"): "source-local"}
        entries = [
            {
                "id": "a",
                "logicalId": "a",
                "sourceRelativePath": "a.md",
                "sourceKind": "LOCAL",
                "sourceName": "local",
                "references": ["b"],
                "conflicts": [{"document": "b", "item": "days", "thisValue": "5", "otherValue": "7"}],
            },
            {
                "id": "b",
                "logicalId": "b",
                "sourceRelativePath": "b.md",
                "sourceKind": "LOCAL",
                "sourceName": "local",
                "references": ["a"],
                "conflicts": [{"document": "a", "item": "days", "thisValue": "7", "otherValue": "5"}],
            },
        ]

        relations, missing = LOADER.build_relations(
            entries,
            {("source-local", "a.md"): "uuid-a", ("source-local", "b.md"): "uuid-b"},
            sources,
        )

        self.assertEqual(set(), missing)
        self.assertEqual(3, len(relations))
        self.assertEqual(2, sum(relation["type"] == "REFERENCES" for relation in relations))
        conflicts = [relation for relation in relations if relation["type"] == "CONFLICTS"]
        self.assertEqual(1, len(conflicts))

    def test_revisions_map_to_the_same_current_document(self):
        sources = {("LOCAL", "local"): "source-local"}
        entries = [
            {"id": "v1", "logicalId": "policy", "sourceRelativePath": "policy.md", "sourceKind": "LOCAL", "sourceName": "local", "references": [], "conflicts": []},
            {"id": "v2", "logicalId": "policy", "sourceRelativePath": "policy.md", "sourceKind": "LOCAL", "sourceName": "local", "references": ["other"], "conflicts": []},
            {"id": "other", "sourceRelativePath": "other.md", "sourceKind": "LOCAL", "sourceName": "local", "references": [], "conflicts": []},
        ]

        relations, missing = LOADER.build_relations(
            entries,
            {("source-local", "policy.md"): "policy-uuid", ("source-local", "other.md"): "other-uuid"},
            sources,
        )

        self.assertEqual(set(), missing)
        self.assertEqual("policy-uuid", relations[0]["sourceDocumentId"])

    def test_url_validation_allows_https_and_local_http_only(self):
        self.assertTrue(LOADER.valid_base_url("https://demo.example.com"))
        self.assertTrue(LOADER.valid_base_url("http://localhost:8080"))
        self.assertFalse(LOADER.valid_base_url("http://demo.example.com"))
        self.assertFalse(LOADER.valid_base_url("file:///tmp/server"))

    def test_same_relative_path_from_different_sources_stays_distinct(self):
        sources = {("LOCAL", "local"): "source-local", ("OBSIDIAN", "obsidian"): "source-obsidian"}
        entries = [
            {
                "id": "local",
                "sourceKind": "LOCAL",
                "sourceName": "local",
                "sourceRelativePath": "same.md",
                "references": ["local-target"],
                "conflicts": [],
            },
            {
                "id": "local-target",
                "sourceKind": "LOCAL",
                "sourceName": "local",
                "sourceRelativePath": "target.md",
                "references": [],
                "conflicts": [],
            },
            {
                "id": "obsidian",
                "sourceKind": "OBSIDIAN",
                "sourceName": "obsidian",
                "obsidianRelativePath": "same.md",
                "references": ["obsidian-target"],
                "conflicts": [],
            },
            {
                "id": "obsidian-target",
                "sourceKind": "OBSIDIAN",
                "sourceName": "obsidian",
                "obsidianRelativePath": "target.md",
                "references": [],
                "conflicts": [],
            },
        ]

        relations, missing = LOADER.build_relations(
            entries,
            {
                ("source-local", "same.md"): "local-id",
                ("source-local", "target.md"): "local-target-id",
                ("source-obsidian", "same.md"): "obsidian-id",
                ("source-obsidian", "target.md"): "obsidian-target-id",
            },
            sources,
        )

        self.assertEqual(
            {
                ("local-id", "local-target-id"),
                ("obsidian-id", "obsidian-target-id"),
            },
            {(relation["sourceDocumentId"], relation["targetDocumentId"]) for relation in relations},
        )
        self.assertEqual(set(), missing)


if __name__ == "__main__":
    unittest.main()
