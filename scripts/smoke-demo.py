#!/usr/bin/env python3
"""시연 서버의 핵심 흐름(헬스체크 → 로그인 → 문서 목록 → 질문 → 근거 조회 → 평가 시나리오)을 점검한다.

요구 사항: 표준 라이브러리 외에 `requests`가 필요하다 (pip install requests).
비밀번호는 환경 변수 DEMO_PASSWORD로만 받는다. 토큰·본문·근거 내용은 출력하지 않는다.

기본 모드(AI_MODE=mock 가정)에서는 demo/eval-set.json의 개정·충돌·권한 3시나리오를 계정별로
호출해 응답 구조(sources·evidence 필드 존재)와 Spring 접근 제어에 따른 계정 간 권한 차이(문서
목록 노출 차이)만 검증한다. `--strict`를 주면 실제 AI 응답값(출장비 70,000원→90,000원, 연차
5일/7일, 할인율 5%/12% 등 eval-set.json의 근거 인용구)과 비인가 계정으로의 근거 비노출까지
검증한다.
"""

import argparse
import json
import os
import sys
import urllib.parse
from pathlib import Path

import requests

ROOT = Path(__file__).resolve().parents[1]
EVAL_SET_PATH = ROOT / "demo/eval-set.json"
MANIFEST_PATH = ROOT / "demo/manifest.json"

# 개정·충돌·권한 3시나리오는 demo/eval-set.json의 scenario 태그로 선택한다(docs/demo-runbook.md 장면 1~3과 대응).
SCENARIO_GROUPS = [
    ("장면 1. 출장비 규정 개정(개정)", {"개정 우선", "이력 조회"}),
    ("장면 2. 연차 이월 규정 충돌(충돌)", {"권한별 연차"}),
    ("장면 3. 고객 할인율의 권한 차이(권한)", {"권한별 할인"}),
]

MOCK_PREFIX = "모의 응답입니다: "
TIMEOUT = 75  # AI 처리 최대 70초 + 여유


def api(session, base, method, path, token=None, json_body=None, timeout=30):
    headers = {"Accept": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    response = session.request(method, base + path, headers=headers, json=json_body, timeout=timeout, allow_redirects=False)
    payload = response.json()
    if not payload.get("success"):
        code = (payload.get("error") or {}).get("code", "UNKNOWN")
        raise RuntimeError(f"{method} {path}: HTTP {response.status_code} API {code}")
    return payload["data"]


def login(session, base, email, password):
    data = api(session, base, "POST", "/api/auth/login", json_body={"email": email, "password": password})
    return data["accessToken"]


def list_all_documents(session, base, token):
    """Fetch every page from GET /api/documents while preserving the first page metadata."""
    page = 0
    first_page = None
    items = []
    while True:
        result = api(session, base, "GET", f"/api/documents?page={page}&size=100", token=token, timeout=30)
        if first_page is None:
            first_page = result
        items.extend(result.get("items", []))
        total_pages = result.get("totalPages")
        if total_pages is None or page + 1 >= total_pages:
            break
        page += 1
    return {**(first_page or {}), "items": items}


def validate_base_url(base):
    parsed = urllib.parse.urlparse(base)
    return parsed.scheme == "https" or (parsed.scheme == "http" and parsed.hostname in {"localhost", "127.0.0.1", "::1"})


def load_eval_set():
    return json.loads(EVAL_SET_PATH.read_text(encoding="utf-8"))


def load_manifest_paths():
    manifest = json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
    return {document["id"]: document["sourceRelativePath"] for document in manifest["documents"]}


def scenario_cases(eval_set, scenario_tags):
    return [case for case in eval_set["cases"] if case["scenario"] in scenario_tags]


def scenario_accounts(cases):
    accounts = []
    for case in cases:
        for email in case["shouldAnswerAccounts"] + case["shouldNotAnswerAccounts"]:
            if email not in accounts:
                accounts.append(email)
    return accounts


def check_evidence_visibility(session, base, token, manifest_paths, case):
    """계정 본인의 GET /api/documents 목록에서 근거 문서가 보이는지 확인한다(AI 응답과 무관하게 Spring 접근 제어를 직접 검증)."""
    own_docs = list_all_documents(session, base, token)
    visible_paths = {item.get("relativePath") for item in own_docs.get("items", [])}
    visible_ids = {item.get("id") for item in own_docs.get("items", [])}
    evidence_paths = [manifest_paths.get(item["documentId"]) for item in case["evidence"]]
    evidence_paths = [path for path in evidence_paths if path]
    visible = bool(evidence_paths) and all(path in visible_paths for path in evidence_paths)
    return visible, visible_paths, visible_ids


def run_scenario_group(session, base, tokens, manifest_paths, group_name, cases, strict):
    rows = []
    group_failed = False
    for case in cases:
        for email in scenario_accounts([case]):
            label = f"{case['id']}({case['scenario']}) {email}"
            token = tokens.get(email)
            should_answer = email in case["shouldAnswerAccounts"]
            if not token:
                rows.append((label, "FAIL", "로그인 실패"))
                group_failed = True
                continue
            try:
                response = api(
                    session, base, "POST", "/api/conversations/messages",
                    token=token, json_body={"message": case["question"]}, timeout=TIMEOUT,
                )
            except Exception as error:
                rows.append((label, "FAIL", f"질문 실패 ({type(error).__name__})"))
                group_failed = True
                continue
            sources = response.get("sources")
            evidence = response.get("evidence")
            if not isinstance(sources, list) or not isinstance(evidence, list) or "answer" not in response:
                rows.append((label, "FAIL", "응답 구조 불일치(answer/sources/evidence)"))
                group_failed = True
                continue
            try:
                visible, visible_paths, visible_ids = check_evidence_visibility(session, base, token, manifest_paths, case)
            except Exception as error:
                rows.append((label, "FAIL", f"문서 목록 조회 실패 ({type(error).__name__})"))
                group_failed = True
                continue
            permission_ok = visible if should_answer else not visible
            if not permission_ok:
                rows.append((label, "FAIL", "권한별 근거 노출 불일치"))
                group_failed = True
                continue
            returned_document_ids = {
                item.get("documentId")
                for item in sources + evidence
                if item.get("documentId") is not None
            }
            if not returned_document_ids.issubset(visible_ids):
                rows.append((label, "FAIL", "문서 목록에 없는 근거가 응답에 포함됨"))
                group_failed = True
                continue
            if not strict:
                is_mock = response.get("answer", "").startswith(MOCK_PREFIX)
                rows.append((label, "PASS", "구조 확인(mock 응답)" if is_mock else "구조 확인"))
                continue
            answer = response.get("answer", "")
            if answer.startswith(MOCK_PREFIX):
                rows.append((label, "FAIL", "--strict인데 mock 응답"))
                group_failed = True
                continue
            quoted = answer + " " + " ".join(item.get("quote", "") for item in evidence)
            if should_answer:
                missing = [item["excerpt"] for item in case["evidence"] if item["excerpt"] not in quoted]
                if missing:
                    rows.append((label, "FAIL", "기대 수치/인용 누락: " + ", ".join(missing)))
                    group_failed = True
                    continue
            else:
                leaked = [
                    item["excerpt"] for item in case["evidence"]
                    if item["excerpt"] in quoted and manifest_paths.get(item["documentId"]) not in
                    {d.get("relativePath") for d in list_all_documents(session, base, token).get("items", [])}
                ]
                if leaked:
                    rows.append((label, "FAIL", "비인가 근거 노출: " + ", ".join(leaked)))
                    group_failed = True
                    continue
            rows.append((label, "PASS", "실값 확인"))
    return rows, group_failed


def main():
    parser = argparse.ArgumentParser(description="시연 서버 스모크 테스트")
    parser.add_argument("base_url", help="서버 주소 (예: http://localhost:18080)")
    parser.add_argument("--strict", action="store_true", help="mock을 거부하고 eval-set.json 기대 근거 인용을 검증합니다")
    args = parser.parse_args()
    base = args.base_url.rstrip("/")
    if not validate_base_url(base):
        print("오류: 비밀번호 보호를 위해 HTTPS 또는 localhost URL만 허용합니다.", file=sys.stderr)
        sys.exit(1)
    password = os.environ.get("DEMO_PASSWORD")
    if not password:
        print("오류: 환경 변수 DEMO_PASSWORD가 필요합니다.", file=sys.stderr)
        sys.exit(1)

    session = requests.Session()
    failures = []
    rows = []
    mock_detected = False

    # 1. 헬스체크
    try:
        health = api(session, base, "GET", "/api/health")
        rows.append(("헬스체크", f"status={health.get('status')} commit={health.get('commit')}"))
        if health.get("status") != "UP":
            failures.append("헬스체크: status가 UP이 아님")
    except Exception as error:
        rows.append(("헬스체크", f"실패 ({type(error).__name__})"))
        failures.append(f"헬스체크: {error}")

    # 2. 계정별 로그인 + 문서 목록 개수
    tokens = {}
    eval_set = load_eval_set()
    manifest_paths = load_manifest_paths()
    all_cases = [case for _, tags in SCENARIO_GROUPS for case in scenario_cases(eval_set, tags)]
    accounts = sorted({email for case in all_cases for email in case["shouldAnswerAccounts"] + case["shouldNotAnswerAccounts"]})
    for email in accounts:
        try:
            token = login(session, base, email, password)
            tokens[email] = token
            me = api(session, base, "GET", "/api/users/me", token=token)
            docs = list_all_documents(session, base, token)
            rows.append(
                (
                    f"계정 {email}",
                    f"부서={me.get('department')} 직급={me.get('position')} 등급={me.get('clearance')} "
                    f"문서수={docs.get('totalElements')}",
                ),
            )
        except Exception as error:
            rows.append((f"계정 {email}", f"실패 ({type(error).__name__})"))
            failures.append(f"로그인/문서목록 {email}: {error}")

    # 3. eval-set.json의 개정·충돌·권한 세 시나리오를 각 계정으로 호출한다.
    for group_name, tags in SCENARIO_GROUPS:
        cases = scenario_cases(eval_set, tags)
        if not cases:
            rows.append((group_name, "FAIL", "eval-set.json 시나리오가 비어 있음"))
            failures.append(f"{group_name}: 평가 사례 없음")
            continue
        scenario_rows, failed = run_scenario_group(session, base, tokens, manifest_paths, group_name, cases, args.strict)
        rows.extend(scenario_rows)
        if failed:
            failures.append(f"{group_name}: 평가 사례 실패")
        if any("mock 응답" in row[2] for row in scenario_rows):
            mock_detected = True

    if mock_detected:
        print("참고: AI_MODE=mock으로 보입니다. 질문 단계는 고정 모의 응답이며 실제 검색 품질을 반영하지 않습니다.")
        print()

    width = max((len(row[0]) for row in rows), default=10)
    print(f"{'항목'.ljust(width)} | 결과 | 설명")
    print("-" * (width + 3 + 20 + 3 + 40))
    for row in rows:
        name, status, detail = row if len(row) == 3 else (row[0], "INFO", row[1])
        print(f"{name.ljust(width)} | {status.ljust(6)} | {detail}")

    print()
    if failures:
        print(f"실패 목록 ({len(failures)}건):")
        for failure in failures:
            print(f"  - {failure}")
        sys.exit(1)
    print("모든 단계 통과.")


if __name__ == "__main__":
    main()
