# 사용자와 문서 접근 권한

직급 순서는 `STAFF < SENIOR < MANAGER < DIRECTOR`, 보안 등급은 `PUBLIC < INTERNAL < CONFIDENTIAL`입니다. 역할은 `EMPLOYEE`, `DOC_ADMIN`, `SYSTEM_ADMIN`입니다. `DOC_ADMIN` 이상만 문서를 관리할 수 있습니다.

문서 열람은 사용자 최대 열람 등급이 문서 등급 이상이고, 문서의 열람 부서 집합이 비었거나 사용자 부서를 포함하고, 문서의 최소 직급이 없거나 사용자 직급 이상일 때 허용합니다. `SYSTEM_ADMIN`은 모든 문서를 열람할 수 있습니다. `DOC_ADMIN`은 이 열람 규칙에서 자동 예외가 아닙니다.

문서의 `owner_department_id`(소유 부서)는 문서를 관리하는 부서를 식별하는 값으로, 열람 가능 부서 집합(`document_departments`)과 별개이며 열람 권한 판단에 전혀 사용되지 않습니다. 소유 부서가 지정되어 있어도 열람 가능 부서 집합에 자동으로 포함되지 않습니다.

`AccessPolicy.scope(principal)`은 `AccessScope(departmentId, position, clearance, isAdmin)`을 반환합니다. AI 서버에는 문서 ID 목록 대신 이 범위를 전달할 예정입니다. SQL에서는 `isAdmin OR (clearance 순서 비교 AND (부서 제한 없음 OR departmentId 포함) AND (최소 직급 없음 OR position 순서 비교))`로 같은 규칙을 구현합니다. enum 문자열은 사전순이 아니라 명시적 순서 또는 숫자 매핑으로 비교해야 합니다.

**가정:** 로컬 데모 계정은 `docs/local-setup.md`의 고정 비밀번호를 사용하고 운영 사용자 생성 방식은 결정 필요입니다. JWT 갱신 토큰과 계정 비활성화 정책은 결정 필요입니다.

발급된 Access Token은 사용자 식별에만 사용합니다. 요청마다 사용자 역할·부서·직급·열람 등급을 데이터베이스에서 다시 읽으므로 이 값이 바뀌면 기존 토큰에도 다음 요청부터 즉시 적용됩니다. 사용자가 삭제된 경우 기존 토큰 인증은 거부됩니다. 토큰 자체는 만료 시각까지 유효하지만, 권한은 발급 시점의 클레임으로 결정하지 않습니다.

V1의 `app_users`에는 이메일과 비밀번호가 없습니다. V2는 기존 행을 `legacy_app_users`에 보존하고 새 `users`를 생성합니다. 기존 행을 새 인증 계정으로 자동 전환하지 않습니다. 실제 전환이 필요하면 사용자 식별·비밀번호 재설정 절차를 별도로 결정해야 합니다.
