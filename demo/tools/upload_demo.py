#!/usr/bin/env python3
"""매니페스트의 문서를 현재 Spring 관리 API에 순서대로 올린다."""

import argparse
import hashlib
import json
import mimetypes
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = ROOT / "demo/manifest.json"


def request(base, method, path, token=None, body=None, content_type=None):
    headers = {"Accept": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if content_type:
        headers["Content-Type"] = content_type
    req = urllib.request.Request(base + path, data=body, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=30) as response:
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
        chunks.append(f"--{boundary}\r\nContent-Disposition: form-data; name=\"{name}\"\r\n\r\n{value}\r\n".encode("utf-8"))
    mime = mimetypes.guess_type(filename)[0] or "application/octet-stream"
    chunks.append(f"--{boundary}\r\nContent-Disposition: form-data; name=\"{file_field}\"; filename=\"{filename}\"\r\nContent-Type: {mime}\r\n\r\n".encode("utf-8"))
    chunks.extend((content, b"\r\n", f"--{boundary}--\r\n".encode("ascii")))
    return b"".join(chunks), f"multipart/form-data; boundary={boundary}"


def department_map(values):
    result = {}
    for value in values:
        if "=" not in value:
            raise ValueError("--department-id는 '부서명=UUID' 형식이어야 합니다.")
        name, identifier = value.split("=", 1)
        result[name] = str(uuid.UUID(identifier))
    return result


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


def main():
    parser = argparse.ArgumentParser(description="모아온 데모 문서 업로드 (기본값: 계획만 출력)")
    parser.add_argument("--execute", action="store_true", help="실제 API에 업로드")
    parser.add_argument("--department-id", action="append", default=[], metavar="부서명=UUID",
                        help="열람 부서 UUID 매핑 (반복 가능)")
    parser.add_argument("--check-exclusions", action="store_true", help=".obsidian 설정 파일의 EXCLUDED 응답 확인")
    args = parser.parse_args()
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    docs = manifest["documents"]
    for document in docs:
        file = ROOT / document["file"]
        if not file.is_file() or not file.resolve().is_relative_to((ROOT / "demo/documents").resolve()):
            raise RuntimeError(f"문서 파일이 없거나 허용 경로 밖입니다: {document['file']}")
    mappings = department_map(args.department_id)
    required = {name for document in docs for name in document["readDepartments"]}
    missing = sorted(required - mappings.keys())
    if not args.execute:
        print(f"계획: 파일 {len(docs)}개, 논리 문서 {len({d['logicalId'] for d in docs})}개, 소스 2개")
        print("현재 API는 LOCAL/OBSIDIAN kind를 지원하지 않아 두 소스 모두 UPLOAD로 생성합니다.")
        if missing:
            print("실행 전 부서 UUID 필요: " + ", ".join(missing))
        return
    if missing:
        raise RuntimeError("부서 UUID가 없습니다: " + ", ".join(missing))
    base = os.environ.get("DOCS199_BASE_URL", "http://localhost:8080").rstrip("/")
    parsed = urllib.parse.urlparse(base)
    if parsed.scheme != "https" and not (parsed.scheme == "http" and parsed.hostname in {"localhost", "127.0.0.1", "::1"}):
        raise RuntimeError("인증 정보 보호를 위해 HTTPS 또는 localhost URL만 허용합니다.")
    token = os.environ.get("DOCS199_TOKEN")
    if not token:
        email = os.environ.get("DOCS199_EMAIL")
        password = os.environ.get("DOCS199_PASSWORD")
        if not email or not password:
            raise RuntimeError("DOCS199_TOKEN 또는 DOCS199_EMAIL·DOCS199_PASSWORD가 필요합니다.")
        token = json_request(base, "POST", "/api/auth/login", None, {"email": email, "password": password})["accessToken"]

    existing_sources = request(base, "GET", "/api/admin/sources", token)
    sources = {}
    for kind in ("LOCAL", "OBSIDIAN"):
        name = f"모아온 {kind.lower()} 자료실"
        matches = [source for source in existing_sources if source["name"] == name]
        if len(matches) > 1:
            raise RuntimeError(f"동일한 이름의 소스가 여러 개입니다: {name}")
        source = matches[0] if matches else json_request(base, "POST", "/api/admin/sources", token,
                                                          {"kind": "UPLOAD", "name": name,
                                                           "defaultDepartmentId": None, "defaultClearance": "PUBLIC"})
        sources[kind] = source["id"]
    current = {kind: list_documents(base, token, source_id) for kind, source_id in sources.items()}
    uploaded = 0
    skipped = 0
    for document in docs:
        kind, path = document["sourceKind"], document["sourceRelativePath"]
        source_id = sources[kind]
        file = ROOT / document["file"]
        content = file.read_bytes()
        digest = hashlib.sha256(content).hexdigest()
        existing = current[kind].get(path)
        if existing:
            detail = request(base, "GET", f"/api/admin/documents/{existing['id']}", token)
            versions = detail["versions"]
            if versions and versions[0]["contentHash"] == digest:
                print(f"변경 없음: {document['id']}")
                skipped += 1
                continue
            if document["version"] == 1:
                known = any(version["contentHash"] == digest for version in versions)
                if known:
                    print(f"이전 버전 보존: {document['id']}")
                    skipped += 1
                    continue
                raise RuntimeError(f"기존 문서가 예상한 v1과 다릅니다: {path}")
        fields = [("paths", path), ("clearance", document["clearance"])]
        fields += [("departmentIds", mappings[name]) for name in document["readDepartments"]]
        if document["minimumPosition"]:
            fields.append(("minimumPosition", document["minimumPosition"]))
        body, content_type = multipart(fields, "files", file.name, content)
        result = request(base, "POST", f"/api/admin/sources/{source_id}/documents", token, body, content_type)[0]
        if result["outcome"] not in {"CREATED", "UPDATED", "UNCHANGED"}:
            raise RuntimeError(f"업로드 결과 확인 필요: {document['id']} / {result['outcome']}")
        print(f"{result['outcome']}: {document['id']} (v{result['version']})")
        uploaded += 1
        current[kind][path] = {"id": result["documentId"]}
    if args.check_exclusions:
        path = ".obsidian/app.json"
        file = ROOT / "demo/documents/obsidian/.obsidian/app.json"
        body, content_type = multipart([("paths", path)], "files", file.name, file.read_bytes())
        result = request(base, "POST", f"/api/admin/sources/{sources['OBSIDIAN']}/documents", token, body, content_type)[0]
        if result["outcome"] != "EXCLUDED":
            raise RuntimeError(f".obsidian 제외 실패: {result['outcome']}")
        print(".obsidian/app.json: EXCLUDED 확인")
    print(f"완료: 업로드 {uploaded}개, 건너뜀 {skipped}개")


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, ValueError, KeyError) as error:
        print(f"오류: {error}", file=sys.stderr)
        sys.exit(1)
