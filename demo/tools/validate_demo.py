#!/usr/bin/env python3
"""생성 파일, 근거 인용, 권한 기대값, PDF 텍스트 추출 가능성을 점검한다."""

import json
import subprocess
from pathlib import Path

from catalog import DOCUMENTS

ROOT = Path(__file__).resolve().parents[2]
POSITIONS = ["STAFF", "SENIOR", "MANAGER", "DIRECTOR"]
CLEARANCES = ["PUBLIC", "INTERNAL", "CONFIDENTIAL"]


def can_read(account, document):
    if account["role"] == "SYSTEM_ADMIN":
        return True
    return (CLEARANCES.index(account["clearance"]) >= CLEARANCES.index(document["clearance"])
            and (not document["readDepartments"] or account["department"] in document["readDepartments"])
            and (not document["minimumPosition"] or POSITIONS.index(account["position"]) >= POSITIONS.index(document["minimumPosition"])))


def main():
    manifest = json.loads((ROOT / "demo/manifest.json").read_text(encoding="utf-8"))
    eval_set = json.loads((ROOT / "demo/eval-set.json").read_text(encoding="utf-8"))
    documents = {document["id"]: document for document in DOCUMENTS}
    assert len(documents) == len(DOCUMENTS) == len(manifest["documents"]) == 19
    assert 20 <= len(eval_set["cases"]) <= 30
    assert sum(document["file"].endswith(".pdf") for document in DOCUMENTS) == 9
    paths = {}
    for document in DOCUMENTS:
        file = ROOT / document["file"]
        assert file.is_file(), file
        paths.setdefault((document["sourceKind"], document["sourceRelativePath"]), []).append(document)
        for reference in document["references"]:
            assert reference in documents, (document["id"], reference)
        if document["revisionOf"]:
            previous = documents[document["revisionOf"]]
            assert previous["logicalId"] == document["logicalId"]
            assert previous["version"] + 1 == document["version"]
        for conflict in document["conflicts"]:
            assert conflict["document"] in documents, (document["id"], conflict)
        if file.suffix == ".pdf":
            text = subprocess.check_output(["pdftotext", "-enc", "UTF-8", str(file), "-"], text=True, encoding="utf-8")
            assert document["title"] in text, file
            for heading, body in document["sections"]:
                assert heading in text, (file, heading)
            assert 1 <= len([page for page in text.split("\f") if page.strip()]) <= 4, file
    for revisions in paths.values():
        assert [doc["version"] for doc in revisions] == list(range(1, len(revisions) + 1)), revisions
    for case in eval_set["cases"]:
        assert case["evidence"], case["id"]
        assert case["shouldAnswerAccounts"], case["id"]
        assert not set(case["shouldAnswerAccounts"]) & set(case["shouldNotAnswerAccounts"]), case["id"]
        cited = []
        for item in case["evidence"]:
            document = documents[item["documentId"]]
            section = dict(document["sections"])[item["section"]]
            assert item["excerpt"] in section, (case["id"], item)
            cited.append(document)
        for email in case["shouldAnswerAccounts"]:
            assert all(can_read(eval_set["accountProfiles"][email], document) for document in cited), (case["id"], email)
        for email in case["shouldNotAnswerAccounts"]:
            assert not all(can_read(eval_set["accountProfiles"][email], document) for document in cited), (case["id"], email)
    print(f"검증 완료: 문서 {len(DOCUMENTS)}개, PDF 9개, 평가 질문 {len(eval_set['cases'])}개")


if __name__ == "__main__":
    main()
