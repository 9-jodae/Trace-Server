#!/usr/bin/env python3
"""근거 위치와 권한별 기대 결과를 가진 수동 설계 평가 사례를 JSON으로 만든다."""

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

ACCOUNTS = {
    "hr.staff@local.docs199": dict(department="인사팀", position="STAFF", clearance="PUBLIC", role="EMPLOYEE", seeded=True),
    "dev.manager@local.docs199": dict(department="개발팀", position="MANAGER", clearance="INTERNAL", role="EMPLOYEE", seeded=True),
    "ops.director@local.docs199": dict(department="경영지원팀", position="DIRECTOR", clearance="CONFIDENTIAL", role="EMPLOYEE", seeded=True),
    "doc.admin@local.docs199": dict(department="경영지원팀", position="MANAGER", clearance="CONFIDENTIAL", role="DOC_ADMIN", seeded=True),
    "system.admin@local.docs199": dict(department="경영지원팀", position="DIRECTOR", clearance="CONFIDENTIAL", role="SYSTEM_ADMIN", seeded=True),
    "sales.manager@local.docs199": dict(department="영업팀", position="MANAGER", clearance="CONFIDENTIAL", role="EMPLOYEE", seeded=True),
    "hr.manager@local.docs199": dict(department="인사팀", position="MANAGER", clearance="CONFIDENTIAL", role="EMPLOYEE", seeded=True),
}


def evidence(document, section, excerpt):
    return dict(documentId=document, section=section, excerpt=excerpt)


def case(question, answer, citations, allowed, denied=(), scenario="기본"):
    return dict(id=f"Q{len(CASES) + 1:02d}", question=question, expectedAnswer=answer,
                evidence=list(citations), shouldAnswerAccounts=list(allowed),
                shouldNotAnswerAccounts=list(denied), scenario=scenario)


CASE_DEF = [
    ("내가 볼 수 있는 자료에서 연차는 며칠까지 이월하나요?", "공개 취업규칙 기준 최대 5일이며 다음 해 3월 31일까지 사용합니다.", [evidence("leave-policy", "제12조 연차휴가", "최대 5일 이월")], ["hr.staff@local.docs199"], [], "권한별 연차"),
    ("내가 볼 수 있는 자료에서 연차는 며칠까지 이월하나요?", "공개 취업규칙은 5일, 인사팀 내부 지침은 7일로 충돌합니다. 어느 값이 유효한지 확인이 필요합니다.", [evidence("leave-policy", "제12조 연차휴가", "최대 5일 이월"), evidence("leave-operation", "제2조 이월 처리", "최대 7일 이월")], ["system.admin@local.docs199", "hr.manager@local.docs199"], ["hr.staff@local.docs199"], "권한별 연차"),
    ("내가 볼 수 있는 자료에서 고객 추가 할인 한도는?", "일반 고객 추가 할인은 최대 5%입니다.", [evidence("sales-guide", "제1조 기본 할인", "최대 5%")], ["ops.director@local.docs199"], [], "권한별 할인"),
    ("내가 볼 수 있는 자료에서 고객 추가 할인 한도는?", "일반 고객은 5%, 전략 고객은 승인 조건에 따라 최대 12%입니다.", [evidence("sales-guide", "제1조 기본 할인", "최대 5%"), evidence("discount-exception", "제2조 승인 한도", "최대 12%")], ["system.admin@local.docs199", "sales.manager@local.docs199"], ["ops.director@local.docs199"], "권한별 할인"),
    ("현재 국내 출장 숙박비 한도는 얼마인가요?", "개정 v2 기준 1박당 90,000원입니다. v1의 70,000원은 신규 출장에 적용하지 않습니다.", [evidence("travel-v2", "제4조 숙박비", "1박당 90,000원")], ["hr.staff@local.docs199", "system.admin@local.docs199"], [], "개정 우선"),
    ("출장비 규정 개정 전 숙박비 한도는?", "v1은 1박당 70,000원이었습니다.", [evidence("travel-v1", "제4조 숙박비", "1박당 최대 70,000원")], ["system.admin@local.docs199"], [], "이력 조회"),
    ("출장 영수증 제출 기한은?", "출장 종료 후 5영업일 이내입니다.", [evidence("travel-v2", "제7조 증빙", "5영업일 이내")], ["hr.staff@local.docs199"], [], "기본"),
    ("100만 원 이상 경비는 누가 최종 승인하나요?", "경영지원팀 부장이 최종 승인합니다.", [evidence("expense-approval", "제3조 결재 단계", "경영지원팀 부장이 최종 승인")], ["ops.director@local.docs199", "doc.admin@local.docs199"], ["hr.staff@local.docs199"], "권한 제한"),
    ("사고 의심 링크를 열면 언제 신고하나요?", "30분 안에 담당자에게 신고합니다.", [evidence("security-handbook", "제3조 사고 신고", "30분 안에 담당자에게 신고")], ["hr.staff@local.docs199", "dev.manager@local.docs199"], [], "기본"),
    ("보안 사고 초기 대응에서 계정은 어떻게 하나요?", "신고 후 30분 이내에 영향 계정을 차단하고 로그를 보존합니다.", [evidence("incident-runbook", "제2조 초기 대응", "영향 계정을 차단하고 로그를 보존")], ["dev.manager@local.docs199"], ["hr.staff@local.docs199", "ops.director@local.docs199"], "권한 제한"),
    ("배포 전 확인해야 할 항목은?", "DB 마이그레이션과 헬스체크 결과를 확인합니다.", [evidence("dev-handover", "제3조 배포 전 확인", "데이터베이스 마이그레이션과 헬스체크")], ["dev.manager@local.docs199"], ["hr.staff@local.docs199"], "인수인계"),
    ("2분기 배포 중 롤백은 몇 건인가요?", "12건 중 1건입니다.", [evidence("release-report", "제2조 결과", "롤백은 1건")], ["dev.manager@local.docs199"], ["hr.staff@local.docs199"], "보고서"),
    ("2분기 평균 복구 시간은?", "18분입니다.", [evidence("release-report", "제2조 결과", "18분")], ["dev.manager@local.docs199"], ["hr.staff@local.docs199"], "보고서"),
    ("일반 고객 할인 상한을 넘는 요청은 어디로 보내나요?", "영업팀 팀장에게 전달합니다.", [evidence("sales-guide", "제3조 고객 응대", "영업팀 팀장에게 전달")], ["hr.staff@local.docs199"], [], "기본"),
    ("전략 고객 10% 할인은 누가 승인하나요?", "8% 초과~12% 구간이므로 영업본부장 결재가 필요합니다.", [evidence("discount-exception", "제2조 승인 한도", "영업본부장 결재")], ["sales.manager@local.docs199", "system.admin@local.docs199"], ["ops.director@local.docs199", "hr.staff@local.docs199"], "기밀"),
    ("영업 담당자 변경 시 무엇을 인계하나요?", "계약 상태, 고객 요청, 미수금, 다음 회의 일정을 인계합니다.", [evidence("sales-handover", "제1조 인수 범위", "계약 상태, 고객 요청, 미수금")], ["sales.manager@local.docs199"], ["hr.staff@local.docs199"], "인수인계"),
    ("상반기 신규 계약은 몇 건인가요?", "24건입니다.", [evidence("contract-report", "제1조 집계", "24건")], ["sales.manager@local.docs199", "system.admin@local.docs199"], ["hr.staff@local.docs199"], "보고서"),
    ("사무실 이전 목표일은 언제인가요?", "2026년 10월 17일입니다.", [evidence("operations-plan", "제1조 일정", "2026년 10월 17일")], ["ops.director@local.docs199"], ["dev.manager@local.docs199"], "권한 제한"),
    ("사무실 이전 예상 비용은?", "4,800만 원입니다.", [evidence("operations-plan", "제2조 예산", "4,800만 원")], ["doc.admin@local.docs199"], ["hr.staff@local.docs199"], "권한 제한"),
    ("징계위원회 의결 요건은?", "재적 과반수 찬성입니다.", [evidence("discipline-policy", "제2조 심의", "재적 과반수 찬성")], ["hr.manager@local.docs199", "system.admin@local.docs199"], ["hr.staff@local.docs199", "doc.admin@local.docs199"], "기밀"),
    ("징계 심의 기록은 몇 년 보관하나요?", "3년간 보관합니다.", [evidence("discipline-policy", "제3조 기록 보관", "3년간 보관")], ["hr.manager@local.docs199"], ["ops.director@local.docs199"], "기밀"),
    ("하반기 개발 직군 채용 목표는?", "3명입니다.", [evidence("hiring-presentation", "제1조 목표", "개발 직군 3명")], ["ops.director@local.docs199", "hr.manager@local.docs199"], ["dev.manager@local.docs199"], "발표"),
    ("신규 직원 자료에서 연차 기준은 어느 문서를 보나요?", "취업규칙 제12조를 기준으로 안내합니다.", [evidence("onboarding-note", "제2조 연차", "《취업규칙》 제12조")], ["hr.staff@local.docs199"], [], "Obsidian 링크"),
    ("문서 지식 지도에서 장애 연락 자료는 무엇인가요?", "장애 연락 흐름 문서로 연결합니다.", [evidence("knowledge-map", "제1조 연결", "[[장애 연락 흐름]]")], ["hr.staff@local.docs199"], [], "Obsidian 링크"),
    ("장애 발견 시 첫 연락 대상은?", "개발팀 당직자입니다.", [evidence("escalation-note", "제1조 알림", "개발팀 당직자")], ["dev.manager@local.docs199"], ["hr.staff@local.docs199"], "Obsidian 링크"),
    ("보안 사고 대응 절차와 인수인계의 관계는?", "인수인계 문서의 장애 복구 조항은 보안 사고 대응 절차 제2조를 참조합니다.", [evidence("dev-handover", "제5조 장애 복구", "《보안 사고 대응 절차》 제2조")], ["dev.manager@local.docs199"], ["hr.staff@local.docs199"], "문서 관계"),
]

CASES = []
for args in CASE_DEF:
    CASES.append(case(*args))


def main():
    data = {"schemaVersion": 1, "accountProfiles": ACCOUNTS, "cases": CASES}
    (ROOT / "demo/eval-set.json").write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"평가 질문 {len(CASES)}개를 생성했습니다.")


if __name__ == "__main__":
    main()
