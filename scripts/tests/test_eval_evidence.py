"""Check evaluation evidence excerpts against extracted source files."""

import json
import unittest
from pathlib import Path

from pypdf import PdfReader

ROOT = Path(__file__).resolve().parents[2]


def extracted_text(path: Path) -> str:
    if path.suffix.lower() in {".md", ".txt"}:
        return path.read_text(encoding="utf-8")
    if path.suffix.lower() == ".pdf":
        return "\n\n".join((page.extract_text() or "").strip() for page in PdfReader(path).pages)
    raise AssertionError(f"Unsupported demo document format: {path.suffix}")


class EvalEvidenceTests(unittest.TestCase):
    def test_every_evidence_excerpt_exists_in_source_extraction(self):
        manifest = json.loads((ROOT / "demo/manifest.json").read_text(encoding="utf-8"))
        eval_set = json.loads((ROOT / "demo/eval-set.json").read_text(encoding="utf-8"))
        documents = {item["id"]: item for item in manifest["documents"]}
        extracted = {}
        missing = []
        for case in eval_set["cases"]:
            for evidence in case["evidence"]:
                document_id = evidence["documentId"]
                if document_id not in extracted:
                    extracted[document_id] = extracted_text(ROOT / documents[document_id]["file"])
                if evidence["excerpt"] not in extracted[document_id]:
                    missing.append((case["id"], document_id, evidence["excerpt"]))
        self.assertEqual([], missing)


if __name__ == "__main__":
    unittest.main()
