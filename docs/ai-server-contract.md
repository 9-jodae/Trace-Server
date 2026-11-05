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

## 질문 API (최소 버전, 확정된 AI 계약 기준)

`POST /api/conversations/messages`(새 대화)와 `POST /api/conversations/{id}/messages`(기존 대화)에서 Spring이 AI 서버의 `POST /api/v1/agent/chat`을 호출합니다. SSE가 아닌 단건 JSON입니다.

- 인증: `X-Internal-Secret` 헤더(ingest 콜백과 같은 `INTERNAL_SECRET` 값)
- 요청: `{"message": "...", "threadId": "대화ID 또는 null"}`. `threadId`는 Spring의 `conversations.id`를 그대로 씁니다. **Spring이 호출자가 그 대화의 소유자인지 먼저 확인한 뒤에만 넘기므로, AI 서버는 Spring이 보낸 `threadId`를 신뢰해도 됩니다.**
- 응답: `{"threadId", "answer", "route", "contextProbability", "sources": [{"id","text","source","documentId","versionId","charStart","charEnd"}], "failedSources": [...], "fallbackReason": null|"..."}`. `sources[].documentId`로 사용자가 접근할 수 없는 문서의 출처는 Spring이 응답에서 제외합니다(`AccessPolicy`). `sources[].id`(청크 ID, UUID)는 접근 가능한 것만 `conversation_messages.evidence_chunk_ids`에 저장합니다.
- 오류: `{"code", "message"}`(`INVALID_REQUEST`/`AI_UNAVAILABLE`/`INTERNAL_ERROR`)를 Spring이 공통 오류 형식(`AI_UNAVAILABLE`, 502)으로 변환합니다.
- 타임아웃: AI 서버 최대 처리 시간(~60초)보다 길게(기본 70초, `AI_CHAT_TIMEOUT_SECONDS`).
- `AI_MODE=mock`에서는 고정 응답(`모의 응답입니다: <질문>`)을 반환합니다.

**AI 팀 확인 필요:** 권한 범위(`AccessScope(departmentId, position, clearance, isAdmin)`)는 아직 AI 서버에 전달하지 않습니다. 지금은 Spring이 응답을 받은 뒤 출처만 걸러내므로, 검색 자체는 권한과 무관하게 전체 청크에서 이루어집니다. AI 서버가 검색 단계에서 권한 필터를 받는 기능을 구현하면 요청에 `AccessScope`를 추가합니다.

## 청크·권한 보장 조회 함수 (확정, #15·#19)

벡터 저장소는 PostgreSQL + pgvector로 확정했습니다(#14, Qdrant 미사용).

`chunks` 테이블은 AI 서버가 직접 쓰고 읽습니다(`ai_app`에 `SELECT`/`INSERT`/`UPDATE`/`DELETE`, DDL 불가).

- `id UUID`(**AI 서버가 생성**: 문서 버전 + 순번 기반 UUID를 그대로 저장, DB가 생성하지 않음), `document_version_id`(→ `document_versions.id`), `chunk_index`(버전 안에서 유일), `content`, `char_start`, `char_end`, `page`(선택), `location`(JSONB, 이후 `bbox` 등), `embedding vector(1024)`(선택), `content_hash`, `created_at`
- 임베딩은 Bedrock Titan v2, 코사인 거리(`vector_cosine_ops`, HNSW 인덱스)

권한 보장 조회 함수 3개(모두 `STABLE`, `ai_app`에 `EXECUTE`). 인자는 Spring의 `AccessScope(departmentId, position, clearance, isAdmin)` 네 값(`department_id uuid, position text, clearance text, is_admin boolean`)과 동일합니다.

1. **권한 범위 안에서 벡터 검색**: `accessible_chunks`로 먼저 범위를 좁힌 뒤 벡터 거리로 정렬합니다.

   ```sql
   SELECT c.*, c.embedding <=> :query_embedding AS distance
   FROM accessible_chunks(:department_id, :position, :clearance, :is_admin) c
   ORDER BY distance
   LIMIT 10;
   ```

2. **그래프 탐색 후보 청크 ID 묶음 확인**: 그래프 홉마다 모은 청크 ID가 지금도 보이는지 확인합니다.

   ```sql
   SELECT * FROM filter_accessible_chunk_ids(
       :department_id, :position, :clearance, :is_admin, :candidate_chunk_ids
   );
   ```

3. **맥락 재사용 전 근거 재검증**: JEV가 0.7 이상이어서 이전 `evidence_chunk_ids`를 재사용하기 전에 같은 함수로 다시 걸러냅니다.

   ```sql
   SELECT * FROM filter_accessible_chunk_ids(
       :department_id, :position, :clearance, :is_admin, :previous_evidence_chunk_ids
   );
   ```

문서 단위로만 필요하면 `accessible_document_versions(:department_id, :position, :clearance, :is_admin)`로 현재 버전의 `document_version_id` 목록만 받을 수 있습니다. 세 함수 모두 삭제된 문서·이전 버전을 자동으로 제외합니다.

**AI 팀 확인 필요:** 질문 API가 이 네 값(`AccessScope`)을 요청에 실어 보내는 기능은 AI 서버가 받을 준비가 되면 추가합니다(지금은 문서화만, 코드는 아직 전달하지 않음).

## 남은 질문

- JEV(TypeSafe `jev-latest`)로 질문·대화·청크 본문이 외부로 전송되는 점과 "외부로 데이터를 보내지 않는다"는 기획 전제의 충돌을 예외로 인정할지
- 답변 생성 LLM의 Bedrock 모델 확정(Claude Haiku 4.5/Sonnet 등 팀 서버에서 키 없이 사용 가능)
- PDF 페이지·`bbox` 제공 계획(파서 확정 이후)
- `/ingest` 요청·콜백 초안(위 "처리 요청 초안") 수용 여부
