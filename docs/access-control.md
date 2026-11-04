# 사용자와 문서 접근 권한

직급 순서는 `STAFF < SENIOR < MANAGER < DIRECTOR`, 보안 등급은 `PUBLIC < INTERNAL < CONFIDENTIAL`입니다. 역할은 `EMPLOYEE`, `DOC_ADMIN`, `SYSTEM_ADMIN`입니다. `DOC_ADMIN` 이상만 문서를 관리할 수 있습니다.

문서 열람은 사용자 최대 열람 등급이 문서 등급 이상이고, 문서의 열람 부서 집합이 비었거나 사용자 부서를 포함하고, 문서의 최소 직급이 없거나 사용자 직급 이상일 때 허용합니다. `SYSTEM_ADMIN`은 모든 문서를 열람할 수 있습니다. `DOC_ADMIN`은 이 열람 규칙에서 자동 예외가 아닙니다.

`AccessPolicy.scope(principal)`은 `AccessScope(departmentId, position, clearance, isAdmin)`을 반환합니다. AI 서버에는 문서 ID 목록 대신 이 범위를 전달할 예정입니다. SQL에서는 `isAdmin OR (clearance 순서 비교 AND (부서 제한 없음 OR departmentId 포함) AND (최소 직급 없음 OR position 순서 비교))`로 같은 규칙을 구현합니다. enum 문자열은 사전순이 아니라 명시적 순서 또는 숫자 매핑으로 비교해야 합니다.

**가정:** 로컬 데모 계정은 `docs/local-setup.md`의 고정 비밀번호를 사용하고 운영 사용자 생성 방식은 결정 필요입니다. JWT 갱신 토큰, 계정 비활성화, 권한 변경 시 기존 토큰 무효화 정책도 결정 필요입니다.
