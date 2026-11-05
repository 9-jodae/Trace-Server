#!/usr/bin/env python3
"""모아온 데모 매니페스트(demo/manifest.json)를 관리 API로 멱등하게 적재한다.

표준 라이브러리만 사용한다(추가 의존성 없음). 관리자(DOC_ADMIN 또는 SYSTEM_ADMIN) 계정의
비밀번호는 환경 변수 DEMO_PASSWORD로만 받으며 어떤 로그에도 찍지 않는다.

적재 순서는 매니페스트의 문서 순서를 따른다. 같은 `logicalId`를 가진 항목은 버전 순서대로
처리하며, 첫 버전은 `POST /api/admin/sources/{sourceId}/documents`로 올리고 다음 버전부터는
전용 개정 엔드포인트 `POST /api/admin/documents/{id}/revisions`로 올린다(예: 출장비 규정
v1 → v2). 이미 적용된 버전의 해시가 서버의 현재 버전과 같으면 `UNCHANGED`로 건너뛰고, 더 오래된
버전(이미 새 버전에 의해 덮인 버전)은 다시 올리지 않고 건너뛴다. 따라서 이 스크립트를 몇 번
실행해도 서버 상태는 같아진다.
"""

import argparse
import hashlib
import json
import mimetypes
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MANIFEST_PATH = ROOT / "demo/manifest.json"
DOCUMENTS_ROOT = (ROOT / "demo/documents").resolve()
SOURCE_NAMES = {"LOCAL": "모아온 local 자료실", "OBSIDIAN": "모아온 obsidian 자료실"}
ACCEPTED_OUTCOMES = {"CREATED", "UPDATED", "UNCHANGED"}


class NoRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, new_url):
        return None
STATUS_POLL_TIMEOUT = 15
STATUS_POLL_INTERVAL = 1


def validate_base_url(base):
    parsed = urllib.parse.urlparse(base)
    return parsed.scheme == "https" or (parsed.scheme == "http" and parsed.hostname in {"localhost", "127.0.0.1", "::1"})


def request(base, method, path, token=None, body=None, content_type=None, timeout=30):
    headers = {"Accept": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if content_type:
        headers["Content-Type"] = content_type
    req = urllib.request.Request(base + path, data=body, method=method, headers=headers)
    try:
        opener = urllib.request.build_opener(NoRedirectHandler)
        with opener.open(req, timeout=timeout) as response:
            payload = json.load(response)
    except urllib.error.HTTPError as error:
        raise RuntimeError(f"{method} {path}: HTTP {error.code}") from error
    except urllib.error.URLError as error:
        raise RuntimeError(f"{method} {path}: 연결 실패 ({error.reason})") from error
    if not payload.get("success"):
        code = (payload.get("error") or {}).get("code", "UNKNOWN")
        raise RuntimeError(f"{method} {path}: API 오류 {code}")
    return payload["data"]


def json_request(base, method, path, token, value):
    return request(base, method, path, token, json.dumps(value, ensure_ascii=False).encode("utf-8"), "application/json")


def multipart(fields, file_field, filename, content):
    boundary = "docs199-" + uuid.uuid4().hex
    chunks = []
    for name, value in fields:
        chunks.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"\r\n\r\n{value}\r\n'.encode("utf-8"))
    mime = mimetypes.guess_type(filename)[0] or "application/octet-stream"
    chunks.append(
        (
            f'--{boundary}\r\nContent-Disposition: form-data; name="{file_field}"; filename="{filename}"\r\n'
            f"Content-Type: {mime}\r\n\r\n"
        ).encode("utf-8"),
    )
    chunks.extend((content, b"\r\n", f"--{boundary}--\r\n".encode("ascii")))
    return b"".join(chunks), f"multipart/form-data; boundary={boundary}"


def sha256_hex(data):
    return hashlib.sha256(data).hexdigest()


def department_overrides(values):
    result = {}
    for value in values:
        if "=" not in value:
            raise RuntimeError("--department-id는 '부서명=UUID' 형식이어야 합니다.")
        name, identifier = value.split("=", 1)
        result[name] = str(uuid.UUID(identifier))
    return result


def department_map(base, token, overrides):
    departments = request(base, "GET", "/api/departments", token)
    mapping = {department["name"]: department["id"] for department in departments}
    mapping.update(overrides)
    return mapping


def ensure_sources(base, token):
    existing = request(base, "GET", "/api/admin/sources", token)
    sources = {}
    for kind, name in SOURCE_NAMES.items():
        matches = [source for source in existing if source["name"] == name]
        if len(matches) > 1:
            raise RuntimeError(f"동일한 이름의 소스가 여러 개입니다: {name}")
        if matches:
            sources[kind] = matches[0]["id"]
        else:
            created = json_request(
                base,
                "POST",
                "/api/admin/sources",
                token,
                {"kind": kind, "name": name, "defaultDepartmentId": None, "defaultClearance": "PUBLIC"},
            )
            sources[kind] = created["id"]
    return sources


def list_documents(base, token, source_id):
    page = 0
    result = {}
    while True:
        query = urllib.parse.urlencode({"sourceId": source_id, "page": page, "size": 100})
        data = request(base, "GET", f"/api/admin/documents?{query}", token)
        for item in data["items"]:
            result[item["relativePath"]] = item
        if page + 1 >= data["totalPages"]:
            return result
        page += 1


def load_version_state(base, token, document_id):
    detail = request(base, "GET", f"/api/admin/documents/{document_id}", token)
    versions = detail["versions"]
    latest = versions[0] if versions else None
    return {
        "id": document_id,
        "known_hashes": {version["contentHash"] for version in versions},
        "latest_hash": latest["contentHash"] if latest else None,
        "latest_version": latest["number"] if latest else None,
    }


def upload_metadata_fields(entry, departments):
    fields = [("clearance", entry["clearance"])]
    for name in entry["readDepartments"]:
        fields.append(("departmentIds", departments[name]))
    if entry["minimumPosition"]:
        fields.append(("minimumPosition", entry["minimumPosition"]))
    if entry["ownerDepartment"]:
        fields.append(("ownerDepartmentId", departments[entry["ownerDepartment"]]))
    if entry["author"]:
        fields.append(("author", entry["author"]))
    if entry["authoredAt"]:
        fields.append(("authoredAt", entry["authoredAt"]))
        fields.append(("sourceModifiedAt", f"{entry['authoredAt']}T00:00:00Z"))
    return fields


def upload_base(base, token, source_id, path, entry, content, departments):
    fields = [("paths", path), ("titles", entry["title"])] + upload_metadata_fields(entry, departments)
    body, content_type = multipart(fields, "files", Path(entry["file"]).name, content)
    results = request(base, "POST", f"/api/admin/sources/{source_id}/documents", token, body, content_type)
    return results[0]


def upload_revision(base, token, document_id, entry, content):
    fields = []
    if entry["author"]:
        fields.append(("author", entry["author"]))
    if entry["authoredAt"]:
        fields.append(("authoredAt", entry["authoredAt"]))
        fields.append(("sourceModifiedAt", f"{entry['authoredAt']}T00:00:00Z"))
    body, content_type = multipart(fields, "file", Path(entry["file"]).name, content)
    return request(base, "POST", f"/api/admin/documents/{document_id}/revisions", token, body, content_type)


def validate_manifest_files(entries):
    for entry in entries:
        file_path = ROOT / entry["file"]
        if not file_path.is_file() or not file_path.resolve().is_relative_to(DOCUMENTS_ROOT):
            raise RuntimeError(f"문서 파일이 없거나 허용 경로 밖입니다: {entry['file']}")


def required_departments(entries):
    names = {name for entry in entries for name in entry["readDepartments"]}
    names |= {entry["ownerDepartment"] for entry in entries if entry["ownerDepartment"]}
    return sorted(names)


def group_by_logical_id(entries):
    groups = {}
    for entry in entries:
        groups.setdefault(entry["logicalId"], []).append(entry)
    return groups


def wait_for_status(base, token, document_id):
    """처리(AI 인덱싱) 작업이 끝날 때까지 짧게 기다렸다가 마지막 상태와 버전 수를 반환한다."""
    deadline = time.monotonic() + STATUS_POLL_TIMEOUT
    while True:
        detail = request(base, "GET", f"/api/admin/documents/{document_id}", token)
        job = detail.get("job")
        status = job["status"] if job else "NONE"
        version_count = len(detail["versions"])
        if status not in {"PENDING", "PROCESSING"} or time.monotonic() >= deadline:
            return status, version_count
        time.sleep(STATUS_POLL_INTERVAL)


def print_status_summary(base, token, final_ids):
    rows = []
    failed = []
    for logical_id in final_ids:
        status, version_count = wait_for_status(base, token, final_ids[logical_id])
        rows.append((logical_id, version_count, status))
        if status in {"FAILED", "PENDING", "PROCESSING"}:
            failed.append(logical_id)
    width = max((len(row[0]) for row in rows), default=4)
    print()
    print(f"{'논리 문서'.ljust(width)} | 버전수 | 처리상태")
    print("-" * (width + 3 + 20))
    for logical_id, version_count, status in rows:
        print(f"{logical_id.ljust(width)} | {str(version_count).rjust(4)}개 | {status}")
    if failed:
        print()
        print("경고: 처리 실패 또는 대기 시간 초과 문서가 있습니다: " + ", ".join(failed))
    return failed


def process_group(base, token, sources, existing_by_source, departments, group, rows, counts, final_ids):
    kind = group[0]["sourceKind"]
    path = group[0]["sourceRelativePath"]
    source_id = sources[kind]
    existing_item = existing_by_source[kind].get(path)
    state = load_version_state(base, token, existing_item["id"]) if existing_item else None

    for index, entry in enumerate(group):
        content = (ROOT / entry["file"]).read_bytes()
        digest = sha256_hex(content)

        if state is None:
            if index != 0:
                raise RuntimeError(f"논리 문서의 첫 버전을 찾을 수 없습니다: {entry['logicalId']}")
            result = upload_base(base, token, source_id, path, entry, content, departments)
            if result["outcome"] not in ACCEPTED_OUTCOMES:
                raise RuntimeError(f"업로드 결과 확인 필요: {entry['id']} / {result['outcome']}")
            state = {
                "id": result["documentId"],
                "known_hashes": {digest},
                "latest_hash": digest,
                "latest_version": result["version"],
            }
            rows.append((entry["id"], entry["logicalId"], entry["version"], result["outcome"], result["version"]))
            counts[result["outcome"]] += 1
            continue

        if digest == state["latest_hash"]:
            rows.append((entry["id"], entry["logicalId"], entry["version"], "UNCHANGED", state["latest_version"]))
            counts["UNCHANGED"] += 1
            continue

        if digest in state["known_hashes"]:
            rows.append((entry["id"], entry["logicalId"], entry["version"], "SKIPPED(이전 버전)", state["latest_version"]))
            counts["SKIPPED"] += 1
            continue

        if index == 0:
            raise RuntimeError(f"기존 문서의 기준 버전이 매니페스트와 다릅니다: {path}")
        result = upload_revision(base, token, state["id"], entry, content)
        if result["outcome"] not in ACCEPTED_OUTCOMES:
            raise RuntimeError(f"개정 결과 확인 필요: {entry['id']} / {result['outcome']}")
        state["known_hashes"].add(digest)
        state["latest_hash"] = digest
        state["latest_version"] = result["version"]
        rows.append((entry["id"], entry["logicalId"], entry["version"], result["outcome"], result["version"]))
        counts[result["outcome"]] += 1

    final_ids[group[0]["logicalId"]] = state["id"]


def print_summary(rows, groups, counts):
    width = max((len(row[0]) for row in rows), default=4)
    logical_width = max((len(row[1]) for row in rows), default=4)
    print(f"{'문서'.ljust(width)} | {'논리ID'.ljust(logical_width)} | 버전 | 결과                | 서버버전")
    print("-" * (width + logical_width + 40))
    for doc_id, logical_id, version, outcome, server_version in rows:
        server_version_text = str(server_version) if server_version is not None else "-"
        print(f"{doc_id.ljust(width)} | {logical_id.ljust(logical_width)} | {version:4d} | {outcome.ljust(20)} | {server_version_text}")
    print()
    print(f"논리 문서 {len(groups)}개, 매니페스트 버전 {len(rows)}개 처리")
    print("상태별 집계: " + ", ".join(f"{key}={value}" for key, value in sorted(counts.items())))


def main():
    parser = argparse.ArgumentParser(description="모아온 데모 문서 적재(멱등, 기본값: 전체 매니페스트 적재)")
    parser.add_argument("base_url", help="서버 주소 (예: http://localhost:18080)")
    parser.add_argument("--email", default="doc.admin@local.docs199", help="업로드할 관리자 계정(DOC_ADMIN/SYSTEM_ADMIN)")
    parser.add_argument(
        "--department-id",
        action="append",
        default=[],
        metavar="부서명=UUID",
        help="부서 UUID 수동 보정 (반복 가능, 기본은 GET /api/departments로 자동 조회)",
    )
    parser.add_argument("--manifest", default=None, help="매니페스트 경로 재정의 (기본: demo/manifest.json)")
    args = parser.parse_args()

    base = args.base_url.rstrip("/")
    if not validate_base_url(base):
        print("오류: 비밀번호 보호를 위해 HTTPS 또는 localhost URL만 허용합니다.", file=sys.stderr)
        sys.exit(1)
    password = os.environ.get("DEMO_PASSWORD")
    if not password:
        print("오류: 환경 변수 DEMO_PASSWORD가 필요합니다.", file=sys.stderr)
        sys.exit(1)

    manifest_path = Path(args.manifest) if args.manifest else MANIFEST_PATH
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    entries = manifest["documents"]
    validate_manifest_files(entries)

    token = json_request(base, "POST", "/api/auth/login", None, {"email": args.email, "password": password})["accessToken"]

    departments = department_map(base, token, department_overrides(args.department_id))
    missing = sorted(set(required_departments(entries)) - departments.keys())
    if missing:
        raise RuntimeError("부서 UUID를 찾을 수 없습니다(--department-id로 보정 가능): " + ", ".join(missing))

    sources = ensure_sources(base, token)
    existing_by_source = {kind: list_documents(base, token, source_id) for kind, source_id in sources.items()}

    rows = []
    counts = Counter()
    groups = group_by_logical_id(entries)
    final_ids = {}
    for group in groups.values():
        process_group(base, token, sources, existing_by_source, departments, group, rows, counts, final_ids)

    print_summary(rows, groups, counts)
    failed = print_status_summary(base, token, final_ids)
    if failed:
        sys.exit(1)


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, ValueError, KeyError) as error:
        print(f"오류: {error}", file=sys.stderr)
        sys.exit(1)
