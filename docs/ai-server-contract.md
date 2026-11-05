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

### 질문 검색 필터 (AI 팀 연동 필요)

질문 요청에는 선택적으로 아래 `filters`를 넣을 수 있으며 Spring은 값을 검증한 후 AI 요청에 그대로 전달합니다.

```json
{"message":"출장비는?","threadId":"대화 UUID","filters":{"extensions":["pdf","md"],"documentTypes":["REPORT"],"sourceKinds":["LOCAL","OBSIDIAN"],"authoredFrom":"2026-01-01","authoredTo":"2026-12-31"}}
```

각 배열의 값은 OR, 서로 다른 필드와 작성일 양 끝은 AND입니다. 선택 필드는 생략하거나 `null`로 필터를 끌 수 있고, 빈 배열은 API에서 거부합니다. 확장자는 `ALLOWED_EXTENSIONS`의 소문자 값, 문서 유형은 `HANDOVER`/`APPROVAL`/`REPORT`/`PRESENTATION`/`ETC`, 소스 종류는 `LOCAL`/`OBSIDIAN`/`NOTION`입니다. 작성일이 없는 문서는 기간 필터가 있으면 제외합니다.

V13의 9인자 `accessible_chunks`와 `accessible_document_versions`에 각각 `extensions` → `p_extensions`, `documentTypes` → `p_document_types`, `sourceKinds` → `p_source_kinds`, `authoredFrom` → `p_authored_from`, `authoredTo` → `p_authored_to`를 넘깁니다. 필터가 없는 인자는 명시적으로 `NULL`을 넣습니다. 기존 4인자 함수는 그대로 유지되며 AI 계정은 두 시그니처 모두 호출할 수 있습니다. PostgreSQL에서 9인자 오버로드의 나머지 인자에 기본값을 지정하면 기존 4인자 호출이 모호해지므로 새 오버로드는 9개 인자를 모두 받습니다.

```sql
SELECT c.* FROM accessible_chunks(
    :department_id, :position, :clearance, :is_admin,
    ARRAY['pdf','md']::text[], ARRAY['REPORT']::text[], ARRAY['LOCAL']::text[],
    DATE '2026-01-01', DATE '2026-12-31'
) c;
```

**가정:** 아직 필터를 지원하지 않는 AI 서버가 추가 JSON 필드를 무시하면 기존 질문은 계속 동작하지만, 검색 결과에 필터가 적용되지는 않습니다. AI 서버가 `filters`를 위 함수에 연결하기 전에는 필터 적용을 보장할 수 없습니다. Spring의 기존 출처 재검증은 권한·현재 버전 확인을 유지하며, AI 답변 본문을 필터 조건으로 재검증하지 않습니다. 검색 전 권한 범위 전달과 대화 맥락 재사용의 필터 적용도 AI 팀과 확인해야 합니다.

## 데모 일괄 적재(방법 1)

현재 버전의 원문을 일회성 JSON으로 만들어 AI 팀에 전달합니다. 저장소 루트에서 Python 가상 환경에 `boto3`, `psycopg[binary]`, `pypdf`, `python-dotenv`를 설치한 뒤 실행합니다.

```bash
python3 -m venv .venv-export
.venv-export/bin/python -m pip install boto3 'psycopg[binary]' pypdf python-dotenv
.venv-export/bin/python scripts/export-documents-json.py ~/exports/demo-documents.json
```

DB 접속은 `DATABASE_URL` 또는 환경 변수·`.env`의 `DB_PASSWORD`, `DB_USER`, `DB_HOST`, `DB_PORT`, `DB_NAME`을 사용합니다. 기본값은 로컬 PostgreSQL의 `docs199` 사용자·DB입니다. S3는 `S3_BUCKET`, `S3_PREFIX`(기본 `uploads/`)와 AWS 기본 자격 증명 체인을 사용합니다. 출력은 원문을 포함하므로 접근을 제한하고 Git에 커밋하지 않습니다. 업로드·개정 후에는 다시 생성해야 합니다.

출력은 `[{"doc_id":"문서 UUID","version_id":"현재 버전 UUID","title":"문서 제목","text":"추출 본문"}]` 배열입니다. 삭제된 문서와 구버전은 제외합니다. MD·TXT는 UTF-8을 그대로 읽고 PDF 페이지는 빈 줄로 구분합니다. 지원하지 않는 형식이나 빈 본문이 있으면 요약을 출력하고 실패합니다.

`charStart`·`charEnd`는 이 JSON의 `text`에서 유니코드 문자(코드 포인트) 위치를 기준으로 합니다. 이번 데모 문서는 BMP 문자만 포함하므로 Java UTF-16 위치와 같습니다. BMP 밖 문자가 포함된 새 문서는 두 단위가 다를 수 있어 별도 변환이 필요합니다. 근거 조회 API는 청크 본문과 앞뒤 청크를 돌려주므로 시연 하이라이트는 청크 단위입니다.

## 남은 질문

- JEV(TypeSafe `jev-latest`)로 질문·대화·청크 본문이 외부로 전송되는 점과 "외부로 데이터를 보내지 않는다"는 기획 전제의 충돌을 예외로 인정할지
- 답변 생성 LLM의 Bedrock 모델 확정(Claude Haiku 4.5/Sonnet 등 팀 서버에서 키 없이 사용 가능)
- PDF 페이지·`bbox` 제공 계획(파서 확정 이후)
- `/ingest` 요청·콜백 초안(위 "처리 요청 초안") 수용 여부

## 임시 임베딩 중계 API

노트북에서 AI 서버를 개발할 때 Spring 서버의 EC2 인스턴스 역할로 Titan 임베딩을 계산한다. AI 서버가 팀 서버로 이전되면 동일한 Bedrock 모델을 직접 호출하고 `EMBEDDING_PROXY_ENABLED=false`로 중계를 끈다. 기본값은 비활성화이며 비활성화 상태에서는 404를 반환한다.

- `POST /api/internal/embeddings`, `X-Internal-Secret` 헤더 필수. 값은 팀장에게 개별로 받는다. JWT만으로 호출할 수 없다.
- 요청: `{"texts":["첫 번째 텍스트","두 번째 텍스트"]}`. 1~32개, 각 8,000 유니코드 코드 포인트 이하, 빈 문자열이나 공백만 있는 문자열은 허용하지 않는다.
- 응답: `{"model":"amazon.titan-embed-text-v2:0","dimensions":1024,"embeddings":[[0.1, ...],[0.2, ...]]}`. 벡터는 각각 1024차원이며 요청 순서를 유지한다. 예시의 벡터 값은 축약했다.
- 전체 제한 시간은 30초, 서버 전체에서 Bedrock 동시 호출은 최대 4개다. 실패하면 본문을 노출하지 않고 공통 오류 `AI_UNAVAILABLE`과 HTTP 503을 반환한다.
- 기본 리전은 `ap-northeast-1`이며 `BEDROCK_EMBEDDING_REGION`, `BEDROCK_EMBEDDING_MODEL_ID`로 조정할 수 있다. 자격 증명은 AWS 기본 체인에서만 찾는다. `local`, `test`는 결정적인 가짜 벡터를 사용한다.
- 질문과 문서 임베딩은 같은 모델, 1024차원, `normalize=true`를 사용해야 검색 결과가 일치한다.

가정: 외부 게이트웨이는 헬스체크만 공개하므로 노트북에서는 `ssh -L 18880:localhost:18080 t09`로 터널을 연 다음 `http://localhost:18880/api/internal/embeddings`를 호출한다. 접속 권한과 비밀 헤더는 팀장에게 개별로 받는다. 문서 일괄 적재는 팀 서버에서 직접 실행해 인스턴스 역할로 Bedrock을 호출하는 것을 권장한다.
