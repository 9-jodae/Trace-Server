#!/usr/bin/env python3
"""Load the manifest's declared document relations into the Spring graph API."""

import argparse
import json
import os
import sys
from pathlib import Path

def api(session, base_url, method, path, token=None, body=None):
    headers = {"Accept": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    response = session.request(method, f"{base_url}{path}", headers=headers, json=body, timeout=30)
    try:
        payload = response.json()
    except ValueError as exc:
        raise RuntimeError(f"{method} {path}: 서버가 JSON 응답을 반환하지 않았습니다 (HTTP {response.status_code})") from exc
    if not response.ok or not payload.get("success"):
        error = payload.get("error") or {}
        code = error.get("code", "UNKNOWN")
        raise RuntimeError(f"{method} {path}: HTTP {response.status_code}, API {code}")
    return payload["data"]


def login(session, base_url, email, password):
    data = api(session, base_url, "POST", "/api/auth/login", body={"email": email, "password": password})
    return data["accessToken"]


def document_ids(session, base_url, token):
    result = {}
    page = 0
    while True:
        data = api(session, base_url, "GET", f"/api/admin/documents?page={page}&size=100", token)
        items = data.get("items", [])
        for doc in items:
            result[doc.get("externalId")] = doc["id"]
            result[doc.get("relativePath")] = doc["id"]
        if page + 1 >= data.get("totalPages", 0):
            return result
        page += 1


def build_relations(entries, ids_by_path):
    ids_by_key = {}
    for entry in entries:
        doc_id = ids_by_path.get(entry.get("sourceRelativePath"))
        if not doc_id:
            doc_id = ids_by_path.get(entry.get("obsidianRelativePath"))
        if doc_id:
            ids_by_key[entry["id"]] = doc_id
            ids_by_key[entry.get("logicalId", entry["id"])] = doc_id

    pending = {}
    missing = set()
    for entry in entries:
        source = ids_by_key.get(entry.get("logicalId", entry["id"]))
        if not source:
            missing.add(entry["id"])
            continue
        for target_key in entry.get("references", []):
            target = ids_by_key.get(target_key)
            if target:
                key = ("REFERENCES", source, target)
                pending[key] = {
                    "sourceDocumentId": source,
                    "targetDocumentId": target,
                    "type": "REFERENCES",
                }
            else:
                missing.add(target_key)
        for conflict in entry.get("conflicts", []):
            target = ids_by_key.get(conflict.get("document"))
            if not target:
                missing.add(conflict.get("document"))
                continue
            left, right = sorted((source, target))
            key = ("CONFLICTS", left, right, conflict["item"])
            if key not in pending:
                pending[key] = {
                    "sourceDocumentId": source,
                    "targetDocumentId": target,
                    "type": "CONFLICTS",
                    "detail": {
                        "item": conflict["item"],
                        "thisValue": conflict["thisValue"],
                        "otherValue": conflict["otherValue"],
                    },
                }
    return list(pending.values()), missing


def main():
    try:
        import requests
    except ImportError:
        print("오류: requests 패키지가 필요합니다.", file=sys.stderr)
        return 2

    parser = argparse.ArgumentParser(description="demo/manifest.json 관계를 그래프 API에 멱등 적재합니다")
    parser.add_argument("base_url", help="서버 주소 (예: http://localhost:8080)")
    parser.add_argument("--manifest", default="demo/manifest.json", help="manifest 경로")
    parser.add_argument("--admin-email", default=os.environ.get("DEMO_ADMIN_EMAIL", "system.admin@local.docs199"))
    args = parser.parse_args()
    password = os.environ.get("DEMO_PASSWORD")
    if not password:
        print("오류: DEMO_PASSWORD 환경 변수가 필요합니다.", file=sys.stderr)
        return 2

    base_url = args.base_url.rstrip("/")
    manifest_path = Path(args.manifest)
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        print(f"오류: manifest를 읽을 수 없습니다 ({manifest_path}): {exc}", file=sys.stderr)
        return 2

    session = requests.Session()
    try:
        token = login(session, base_url, args.admin_email, password)
        ids_by_path = document_ids(session, base_url, token)
        entries = manifest.get("documents", [])
        pending, missing = build_relations(entries, ids_by_path)

        if missing:
            raise RuntimeError(f"manifest 관계 문서 ID를 API에서 찾지 못했습니다: {', '.join(sorted(missing))}")

        for body in pending:
            api(session, base_url, "POST", "/api/admin/document-relations", token, body)

        print(f"관계 적재 완료: manifest={len(entries)}개 문서, REFERENCES={sum(1 for item in pending if item['type'] == 'REFERENCES')}개, CONFLICTS={sum(1 for item in pending if item['type'] == 'CONFLICTS')}개")
        print("CONFLICTS는 문서 쌍과 항목 기준으로 중복 제거했으며, 서버 API의 upsert로 재실행해도 같은 결과를 유지합니다.")
        return 0
    except (requests.RequestException, RuntimeError, KeyError) as exc:
        print(f"오류: {exc}", file=sys.stderr)
        return 1
    finally:
        session.close()


if __name__ == "__main__":
    raise SystemExit(main())
