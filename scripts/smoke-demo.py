#!/usr/bin/env python3
"""시연 서버의 핵심 흐름(헬스체크 → 로그인 → 문서 목록 → 질문 → 근거 조회)을 점검한다.

요구 사항: 표준 라이브러리 외에 `requests`가 필요하다 (pip install requests).
비밀번호는 환경 변수 DEMO_PASSWORD로만 받는다. 토큰·본문·근거 내용은 출력하지 않는다.
"""

import argparse
import os
import sys
import urllib.parse

import requests

# docs/demo-runbook.md 장면 1~3, docs/deployment.md 데모 계정 표 기준.
SCENES = [
    {
        "name": "장면 1. 출장비 규정 개정",
        "accounts": ["hr.staff@local.docs199"],
        "question": "현재 국내 출장 숙박비 한도는 얼마인가요?",
    },
    {
        "name": "장면 2. 연차 이월 규정 충돌",
        "accounts": ["hr.staff@local.docs199", "hr.manager@local.docs199"],
        "question": "내가 볼 수 있는 자료에서 연차는 며칠까지 이월하나요?",
    },
    {
        "name": "장면 3. 고객 할인율의 권한 차이",
        "accounts": ["ops.director@local.docs199", "sales.manager@local.docs199"],
        "question": "내가 볼 수 있는 자료에서 고객 추가 할인 한도는?",
    },
]

MOCK_PREFIX = "모의 응답입니다: "
TIMEOUT = 75  # AI 처리 최대 70초 + 여유


def api(session, base, method, path, token=None, json_body=None, timeout=30):
    headers = {"Accept": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    response = session.request(method, base + path, headers=headers, json=json_body, timeout=timeout)
    payload = response.json()
    if not payload.get("success"):
        code = (payload.get("error") or {}).get("code", "UNKNOWN")
        raise RuntimeError(f"{method} {path}: HTTP {response.status_code} API {code}")
    return payload["data"]


def login(session, base, email, password):
    data = api(session, base, "POST", "/api/auth/login", json_body={"email": email, "password": password})
    return data["accessToken"]


def main():
    parser = argparse.ArgumentParser(description="시연 서버 스모크 테스트")
    parser.add_argument("base_url", help="서버 주소 (예: http://localhost:8080)")
    args = parser.parse_args()
    base = args.base_url.rstrip("/")
    parsed = urllib.parse.urlparse(base)
    if parsed.scheme != "https" and not (parsed.scheme == "http" and parsed.hostname in {"localhost", "127.0.0.1", "::1"}):
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
    accounts = sorted({account for scene in SCENES for account in scene["accounts"]})
    for email in accounts:
        try:
            token = login(session, base, email, password)
            tokens[email] = token
            me = api(session, base, "GET", "/api/users/me", token=token)
            docs = api(session, base, "GET", "/api/documents?size=100", token=token)
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

    # 3. 장면별 질문 + 근거 조회
    for scene in SCENES:
        for email in scene["accounts"]:
            label = f"{scene['name']} / {email}"
            token = tokens.get(email)
            if not token:
                rows.append((label, "건너뜀 (로그인 실패)"))
                failures.append(f"{label}: 로그인 실패로 질문 단계를 건너뜀")
                continue
            try:
                response = api(
                    session,
                    base,
                    "POST",
                    "/api/conversations/messages",
                    token=token,
                    json_body={"message": scene["question"]},
                    timeout=TIMEOUT,
                )
                answer = response.get("answer", "")
                is_mock = answer.startswith(MOCK_PREFIX)
                mock_detected = mock_detected or is_mock
                sources = response.get("sources") or []
                titles = ", ".join(sorted({source.get("documentTitle", "?") for source in sources})) or "없음"
                status = "mock 응답" if is_mock else "실답변"
                rows.append((label, f"{status} 출처={len(sources)}개 제목=[{titles}]"))

                if sources:
                    first_id = sources[0].get("id")
                    try:
                        api(session, base, "GET", f"/api/evidence/{first_id}", token=token, timeout=30)
                        rows.append((f"{label} 근거조회", "성공"))
                    except Exception as error:
                        rows.append((f"{label} 근거조회", f"실패 ({type(error).__name__})"))
                        failures.append(f"{label} 근거조회: {error}")
                elif not is_mock:
                    # mock이 아닌 실답변인데 출처가 없으면 검색·인용 경로가 고장난 것이므로 실패로 기록한다.
                    failures.append(f"{label}: 실답변인데 출처가 없어 근거 조회를 점검하지 못함")
            except Exception as error:
                rows.append((label, f"실패 ({type(error).__name__})"))
                failures.append(f"{label}: {error}")

    if mock_detected:
        print("참고: AI_MODE=mock으로 보입니다. 질문 단계는 고정 모의 응답이며 실제 검색 품질을 반영하지 않습니다.")
        print()

    width = max(len(row[0]) for row in rows) if rows else 10
    print(f"{'항목'.ljust(width)} | 결과")
    print("-" * (width + 3 + 40))
    for name, value in rows:
        print(f"{name.ljust(width)} | {value}")

    print()
    if failures:
        print(f"실패 목록 ({len(failures)}건):")
        for failure in failures:
            print(f"  - {failure}")
        sys.exit(1)
    print("모든 단계 통과.")


if __name__ == "__main__":
    main()
