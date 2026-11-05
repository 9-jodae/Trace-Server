import importlib.util
import unittest
from pathlib import Path


SCRIPT = Path(__file__).parents[1] / "load-demo-relations.py"
SPEC = importlib.util.spec_from_file_location("load_demo_relations", SCRIPT)
LOADER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(LOADER)


class BuildRelationsTests(unittest.TestCase):
    def test_reciprocal_conflicts_collapse_but_references_keep_direction(self):
        entries = [
            {
                "id": "a",
                "logicalId": "a",
                "sourceRelativePath": "a.md",
                "references": ["b"],
                "conflicts": [{"document": "b", "item": "days", "thisValue": "5", "otherValue": "7"}],
            },
            {
                "id": "b",
                "logicalId": "b",
                "sourceRelativePath": "b.md",
                "references": ["a"],
                "conflicts": [{"document": "a", "item": "days", "thisValue": "7", "otherValue": "5"}],
            },
        ]

        relations, missing = LOADER.build_relations(entries, {"a.md": "uuid-a", "b.md": "uuid-b"})

        self.assertEqual(set(), missing)
        self.assertEqual(3, len(relations))
        self.assertEqual(2, sum(relation["type"] == "REFERENCES" for relation in relations))
        conflicts = [relation for relation in relations if relation["type"] == "CONFLICTS"]
        self.assertEqual(1, len(conflicts))

    def test_revisions_map_to_the_same_current_document(self):
        entries = [
            {"id": "v1", "logicalId": "policy", "sourceRelativePath": "policy.md", "references": [], "conflicts": []},
            {"id": "v2", "logicalId": "policy", "sourceRelativePath": "policy.md", "references": ["other"], "conflicts": []},
            {"id": "other", "sourceRelativePath": "other.md", "references": [], "conflicts": []},
        ]

        relations, missing = LOADER.build_relations(entries, {"policy.md": "policy-uuid", "other.md": "other-uuid"})

        self.assertEqual(set(), missing)
        self.assertEqual("policy-uuid", relations[0]["sourceDocumentId"])


if __name__ == "__main__":
    unittest.main()
