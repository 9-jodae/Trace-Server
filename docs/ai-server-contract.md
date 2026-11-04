# AI 서버 계약

## 연동 인프라 (확정)

AI 서버(Python, FastAPI)는 백엔드와 같은 서버·같은 Docker Compose에 올립니다. 그래프 DB는 Neo4j이며, 백엔드가 빈 인스턴스와 계정만 제공하고 노드·관계·인덱스는 AI 팀이 만듭니다. 벡터는 PostgreSQL(pgvector)을 같이 쓰며 AI 전용 계정 `ai_app`을 제공합니다. PostgreSQL 스키마는 이 저장소의 Flyway만 관리합니다.

`ai_app`은 `V10__ai_app_role.sql`에서 로그인 불가(NOLOGIN)로 생성되며, `scripts/enable-ai-db-user.sh`로 운영 서버에서만 비밀번호를 설정해 로그인 가능하게 만듭니다. 현재는 `documents`, `document_versions`, `document_departments`, `sources`, `departments`, `ingestion_jobs`만 읽기(`SELECT`) 권한이 있고 `users`, `conversations`, `conversation_messages`, Flyway 이력 테이블은 접근할 수 없으며 DDL 권한이 없습니다. **AI 서버가 쓰기 권한이 필요한 새 테이블(예: 청크·그래프 메타데이터)은 그 테이블을 만드는 마이그레이션에서 `ai_app`에 권한을 함께 부여합니다.** 기존 테이블에 대한 쓰기 권한은 별도 합의 없이 추가하지 않습니다.

AI 서버는 같은 Docker 네트워크에서만 노출하고 Spring 서버만 호출하는 구조로 배치합니다(포트를 호스트에 공개하지 않음). **AI 팀 확인 필요(#14):** 서버 간 인증으로 공유 비밀 헤더 `X-Internal-Secret`(ingest 콜백과 동일한 `INTERNAL_SECRET` 값)을 받아줄 수 있는지.

## 처리 요청 초안 — AI 팀 확인 필요

새 문서 버전의 DB 트랜잭션이 커밋되면 `ingestion_jobs`를 `PENDING`으로 생성하고 `AI_MODE=http`에서 `POST {AI_BASE_URL}/ingest`를 호출합니다. `X-Internal-Secret` 헤더와 다음 JSON을 전송합니다.

```json
{"jobId":"UUID","versionId":"UUID","storageKey":"documents/...","extension":"pdf"}
```

`AI_MODE=mock`에서는 잠시 뒤 `PROCESSING` → `DONE`으로 바꾸고 `md`를 `HANDOVER`, 다른 확장자를 `ETC`로 임시 분류합니다. 이는 실제 AI 분류 규칙이 아닙니다. PENDING 작업은 복구 시 같은 `jobId`로 다시 제출될 수 있으므로 AI 서버는 `jobId` 기준 멱등 처리가 필요합니다.

AI 서버는 `POST /api/internal/ingestion-jobs/{id}/result`에 같은 공유 비밀 헤더와 다음 JSON을 보냅니다.

```json
{"status":"DONE","errorMessage":null,"documentType":"REPORT","author":"홍길동","authoredAt":"2026-01-01"}
```

`status`는 `PROCESSING`, `DONE`, `FAILED`를 받습니다. `errorMessage`, `documentType`, `author`, `authoredAt`은 선택적입니다. `author`/`authoredAt`은 문서에 이미 값이 있으면(사용자가 업로드·개정 시 입력한 값) 콜백 값으로 덮어쓰지 않습니다. **AI 팀 확인 필요:** 이 "사용자 입력 우선" 규칙이 AI 추출 결과의 신뢰도를 반영하기에 적절한지, 더 나은 값을 AI가 추출했을 때 갱신할 방법이 필요한지. `PENDING → PROCESSING/DONE/FAILED`, `PROCESSING → DONE/FAILED`만 반영하고 종료 상태 이후 콜백은 무시합니다. 문서 유형은 현재 버전의 `DONE` 결과만 반영합니다. 공유 비밀은 `INTERNAL_SECRET` 환경 변수로 전달합니다. **AI 팀 확인 필요:** 요청·콜백 필드, 스토리지 접근 방법, `jobId` 멱등성, 실패 재시도, 비밀 회전.

## 질문·검색 계약 — 결정 필요

- 엔드포인트 이름·버전, 서버 간 인증, requestId 전달 방식
- 질문 요청 필드·길이 제한·언어·대화 문맥
- 근거 응답의 문서 ID·불변 버전·원문 위치·인용문·오류 형식
- `AccessScope(departmentId, position, clearance, isAdmin)` 전달과 SQL 조건 적용 방식
- SSE 이벤트 이름·JSON 스키마·heartbeat·완료·오류·취소
- 멱등성·타임아웃·안전한 재시도 범위·호출 제한

질문 API는 아직 구현하지 않았습니다. 공통 `AIClient`는 원시 JSON과 SSE 전송만 제공합니다.
