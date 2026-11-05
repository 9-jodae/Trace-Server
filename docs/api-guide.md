# 프론트·앱 연동 안내

이 문서는 프론트·앱 팀이 백엔드 API를 연동하는 데 필요한 내용만 모았습니다. 세부 규칙은 각 안내 문서([접근 권한](access-control.md), [문서 관리](document-management.md), [대화](conversations.md))를 참고하세요.

## 서버 주소

- 로컬: `http://localhost:8080`
- 팀 서버(현재 HTTP): `http://3.0.74.114:8080`, `http://api.docs199.exijn.com:8080`
- 팀 서버(HTTPS 적용 후): `https://api.docs199.exijn.com` — HTTPS 전환이 끝나면 프론트·앱의 API 기본 주소를 이 값으로 설정합니다. CORS는 서버 `.env`의 `CORS_ORIGINS`에 등록된 출처만 허용하므로, 프론트 배포 주소가 정해지면 정확한 HTTPS origin을 서버 설정 담당자에게 전달하세요([Issue #8](https://github.com/9-jodae/docs199-Server/issues/8) 참고). 전환 중에는 HTTPS 헬스체크가 성공하기 전까지 기존 HTTP 주소를 유지합니다.

### 외부 공개 범위

| 경로 | 외부 공개 | 비고 |
| --- | --- | --- |
| `GET /api/health` | O | |
| `/api/**`(그 외 전체) | O | 모든 메서드. 인증은 `Authorization: Bearer` 토큰, 관리자 전용은 역할 검사 |
| `/api/internal/**`, `/api/dev/**` | X(404) | 내부 전용(임베딩 중계, ingestion 콜백, 데모 토큰) |
| `/actuator/**`, `/swagger-ui/**`, `/v3/api-docs/**` | X(404) | |

자세한 경로별 403/401 구분과 배포 설정은 `docs/deployment.md`의 "외부 공개 범위"를 참고하세요.

## 공통 응답 형식

성공 응답:

```json
{"success": true, "data": { ... }, "error": null}
```

오류 응답:

```json
{"success": false, "data": null, "error": {"code": "INVALID_INPUT", "message": "요청이 올바르지 않습니다", "details": null}}
```

| 코드 | HTTP 상태 | 의미 |
| --- | --- | --- |
| `INVALID_INPUT` | 400 | 요청 형식·값 오류(검증 실패 시 `details`에 `field`/`message` 배열) |
| `UNAUTHENTICATED` | 401 | 토큰 없음·만료·무효 |
| `FORBIDDEN` | 403 | 권한 부족, 또는 내부 콜백 비밀 불일치 |
| `NOT_FOUND` | 404 | 리소스 없음(권한 없는 리소스도 존재를 숨기기 위해 404로 응답) |
| `STORAGE_ERROR` | 502 | 파일 저장소 오류 |
| `AI_UNAVAILABLE` | 502 | AI 서버 연결 실패 |
| `INTERNAL_ERROR` | 500 | 서버 내부 오류 |

목록 응답(`PageResponse`):

```json
{"items": [...], "page": 0, "size": 20, "totalElements": 42, "totalPages": 3}
```

`page`는 0부터 시작합니다. `size`는 1~100(문서 목록 기준)입니다.

## 로그인과 토큰

```http
POST /api/auth/login
Content-Type: application/json

{"email": "hr.staff@local.docs199", "password": "..."}
```

응답 `data`: `{"accessToken": "...", "tokenType": "Bearer"}`. 이후 모든 요청에 `Authorization: Bearer <accessToken>` 헤더를 붙입니다. 토큰은 1시간 후 만료되며(갱신 토큰 없음, 결정 필요), 재로그인으로 다시 발급받습니다.

```http
GET /api/users/me
Authorization: Bearer <token>
```

응답 `data`: `{"id", "email", "name", "departmentId", "department", "position", "role", "clearance"}`.

### 데모 계정

아래 네 계정은 로컬 시연에서 권한 차이를 보여주기 위한 대표 계정입니다. 로컬 계정 전체와 팀 서버 계정 정책은 `docs/local-setup.md`, `docs/deployment.md`를 참고하세요. 비밀번호는 이 문서에 적지 않습니다. 팀 서버 비밀번호는 담당자에게 개별로 받으세요.

| 이메일 | 부서 | 직급 | 역할 | 보안 등급 | 연동 확인 용도 |
| --- | --- | --- | --- | --- | --- |
| `hr.staff@local.docs199` | 인사팀 | STAFF | EMPLOYEE | PUBLIC | 공개 문서·일반 질문 |
| `dev.manager@local.docs199` | 개발팀 | MANAGER | EMPLOYEE | INTERNAL | 개발팀 내부 문서 |
| `ops.director@local.docs199` | 경영지원팀 | DIRECTOR | EMPLOYEE | CONFIDENTIAL | 경영지원 기밀 문서 |
| `doc.admin@local.docs199` | 경영지원팀 | MANAGER | DOC_ADMIN | CONFIDENTIAL | 문서 소스·업로드 관리 |

부서·직급·보안 등급·역할을 함께 적용해 문서 접근을 판정합니다. `DOC_ADMIN`은 문서 관리 API 역할이며 `SYSTEM_ADMIN`과 같지 않습니다.

## 부서 목록

```http
GET /api/departments
Authorization: Bearer <token>
```

응답 `data`: `[{"id": "UUID", "name": "인사팀"}, ...]`. 로그인한 사용자 누구나 조회할 수 있습니다. 문서 업로드 시 `departmentIds`·`ownerDepartmentId`에 쓸 UUID를 여기서 조회합니다.

## 문서 업로드(관리자)

`DOC_ADMIN` 또는 `SYSTEM_ADMIN` 토큰이 필요합니다. 폴더 업로드는 각 파일을 같은 요청에 반복해서 보냅니다.

```http
POST /api/admin/sources/{sourceId}/documents
Content-Type: multipart/form-data
Authorization: Bearer <token>

files=<파일1>
files=<파일2>
paths=공통/규정/leave-policy.md
paths=인사/지침/leave-operation.md
clearance=INTERNAL
departmentIds=<부서UUID>
minimumPosition=MANAGER
ownerDepartmentId=<부서UUID>
author=정서윤
authoredAt=2026-03-01
sourceModifiedAt=2026-03-01T00:00:00Z
```

- `files`/`paths`는 개수와 순서가 같아야 합니다. `paths`는 소스 내부 상대 경로입니다.
- 파일별 기본 한도는 20MB(`MAX_UPLOAD_FILE_SIZE`), 요청 전체 기본 한도는 multipart 오버헤드를 포함해 100MB(`MAX_UPLOAD_REQUEST_SIZE`)입니다. 클라이언트는 두 한도 안에 들어오도록 여러 파일을 나눠 전송해야 합니다. 기존 `MAX_UPLOAD_SIZE`는 새 변수가 없을 때 두 한도에 대한 하위 호환값으로 사용합니다.
- `clearance`(`PUBLIC`/`INTERNAL`/`CONFIDENTIAL`), `departmentIds`(반복 가능, 비우면 전체 부서 열람 가능), `minimumPosition`(`STAFF`/`SENIOR`/`MANAGER`/`DIRECTOR`), `ownerDepartmentId`, `author`, `authoredAt`(`YYYY-MM-DD`), `sourceModifiedAt`(ISO 인스턴트, 예: `2026-03-01T00:00:00Z`)는 모두 선택 입력입니다.
- 소스를 먼저 만들어야 합니다: `POST /api/admin/sources`(`{"kind": "LOCAL"|"OBSIDIAN", "name": "...", "defaultDepartmentId": null, "defaultClearance": "PUBLIC"}`), 목록은 `GET /api/admin/sources`. `NOTION`은 아직 생성할 수 없습니다.
- 응답 `data`는 파일 순서에 맞춘 배열, 각 항목은 `{"path", "documentId", "version", "outcome", "reason"}`. `outcome`은 `CREATED`/`UPDATED`/`UNCHANGED`/`EXCLUDED`(사유는 `reason`)입니다.
- 같은 상대 경로로 다시 올리면 새 버전(개정)이 됩니다. 전용 개정 엔드포인트도 있습니다: `POST /api/admin/documents/{id}/revisions`(`file` 하나, `author`/`authoredAt`/`sourceModifiedAt` 선택).
- 의도한 문서 유형(`documentType`)과 표시 제목은 이 API로 입력할 수 없습니다. 유형은 AI 처리가 끝나야 채워지고, 제목은 파일명에서 그대로 파생됩니다.

### 프론트에서 자주 막히는 부분

- **토큰 만료:** 액세스 토큰은 발급 1시간 뒤 만료되고 갱신 토큰은 없습니다. `UNAUTHENTICATED`(401)를 받으면 로그인 API를 다시 호출해 토큰을 교체한 뒤 원래 요청을 재시도하세요.
- **multipart 업로드:** 각 파일마다 `files`와 `paths`를 같은 순서로 반복해서 보냅니다. JSON으로 인코딩하지 말고 `multipart/form-data`를 사용하세요. 기본 파일 한도는 20 MB, 요청 전체 한도는 100 MB입니다.
- **CORS:** 현재 기본 허용 출처는 `http://localhost:3000` 하나입니다. 다른 개발 포트나 배포 도메인에서 브라우저 호출이 차단되면 서버 `.env`의 `CORS_ORIGINS`에 프론트의 정확한 origin(스킴·호스트·포트)을 추가해야 합니다. 변경은 서버 설정 담당자에게 요청하세요.
- **OpenAPI:** 로컬 프로필에서만 `/v3/api-docs`와 Swagger UI가 열립니다. 프론트 타입 생성용 고정 파일은 저장소의 [`openapi.json`](openapi.json)입니다. API 변경 후 아래 명령으로 파일을 갱신하세요. export 설정을 생략한 테스트는 생성 결과와 커밋된 파일이 같은지 검증합니다. 산출물에 서버 주소가 없으므로 개발·시연 환경의 base URL은 앱 설정으로 전달하세요.

  ```powershell
  $env:OPENAPI_EXPORT = "true"
  .\gradlew.bat test --tests com.jodae.docs199.OpenApiExportTests
  Remove-Item Env:OPENAPI_EXPORT
  ```

## 문서 목록·상세·다운로드

직원용(본인 권한 범위만, `/api/documents`)과 관리자용(전체, `/api/admin/documents`)이 거의 같은 형태입니다.

```http
GET /api/documents?sourceId=&sourceKind=LOCAL&extension=pdf&documentType=REPORT&authoredFrom=2026-01-01&authoredTo=2026-12-31&status=DONE&ownerDepartmentId=&page=0&size=20
Authorization: Bearer <token>
```

모든 쿼리 파라미터는 선택입니다. `sourceKind`는 `LOCAL`/`OBSIDIAN`/`NOTION`, `status`는 처리 상태(`PENDING`/`PROCESSING`/`DONE`/`FAILED`)입니다. 응답 `data`는 `PageResponse<문서>`이며 각 문서는 `id`, `sourceId`, `externalId`, `title`, `relativePath`, `extension`, `documentType`, `author`, `authoredAt`, `clearance`, `minimumPosition`, `currentVersion`, `deletedAt`, `ownerDepartmentId`, `sourceModifiedAt`를 포함합니다.

### 문서 목록과 질문 검색 필터

질문 API(`POST /api/conversations/messages`, `POST /api/conversations/{id}/messages`)의 JSON에 `filters`를 선택적으로 넣습니다. 문서 목록은 쿼리 파라미터를 사용합니다.

| 조건 | 문서 목록 | 질문 `filters` | 허용 값 |
| --- | --- | --- | --- |
| 확장자 | `extension` | `extensions` | `ALLOWED_EXTENSIONS`의 소문자 값(기본 `pdf`, `md`) |
| 문서 유형 | `documentType` | `documentTypes` | `HANDOVER`, `APPROVAL`, `REPORT`, `PRESENTATION`, `ETC` |
| 소스 종류 | `sourceKind` | `sourceKinds` | `LOCAL`, `OBSIDIAN`, `NOTION` |
| 작성 기간 | `authoredFrom`, `authoredTo` | `authoredFrom`, `authoredTo` | `YYYY-MM-DD`, 양 끝 포함 |
| 소스 ID | `sourceId` | 지원하지 않음 | UUID |
| 처리 상태 | `status` | 지원하지 않음 | `PENDING`, `PROCESSING`, `DONE`, `FAILED` |
| 소유 부서 | `ownerDepartmentId` | 지원하지 않음 | UUID |

예: `{"message":"출장비는?","filters":{"extensions":["pdf"],"documentTypes":["REPORT"],"sourceKinds":["LOCAL"],"authoredFrom":"2026-01-01","authoredTo":"2026-12-31"}}`. 배열은 비어 있으면 안 되며 알 수 없는 값, 잘못된 날짜, 시작일이 종료일보다 늦은 경우 400입니다. 배열 내부는 OR, 서로 다른 조건은 AND입니다. 작성일이 없는 문서는 기간 조건에서 제외됩니다.

**가정:** AI 서버가 아직 `filters`를 처리하지 않으면 요청은 기존처럼 처리될 수 있지만 검색 필터는 적용되지 않습니다. 실제 적용은 AI 서버 연동 후 확인이 필요하며, Spring의 출처 검증은 기존 권한·현재 버전 검사입니다. 상세는 [AI 서버 계약](ai-server-contract.md#질문-검색-필터-ai-팀-연동-필요)을 참조하세요.

```http
GET /api/documents/{id}
```

응답 `data`: `{"document", "departmentIds", "versions": [{"id","number","fileSize","contentHash","uploadedBy","createdAt"}], "job": {"id","versionId","status","errorMessage","startedAt","finishedAt"} | null}`. 권한이 없으면 `NOT_FOUND`(존재 자체를 숨김).

```http
GET /api/documents/{id}/download-url
```

응답 `data`: `{"url": "/api/documents/{id}/download"}`. URL은 Spring API 경로이며, 로그인 인증과 문서 접근 권한을 검사한 뒤 파일을 스트리밍합니다.

```http
GET /api/documents/{id}/download
GET /api/documents/{id}/versions/{versionId}/download
Authorization: Bearer <token>
```

파일을 Spring을 통해 스트리밍합니다. 버전 경로는 근거 조회 결과의 `versionId`와 함께 사용합니다. 관리자는 `/api/admin/documents/{id}/download` 및 `/api/admin/documents/{id}/versions/{versionId}/download`를 사용합니다.

```http
GET /api/documents/{id}/status
```

응답 `data`: 처리 작업(`job`) 객체 또는 `null`(아직 처리 요청 전).

관리자 전용 추가 기능: `PATCH /api/admin/documents/{id}/access`(`{"clearance","departmentIds","minimumPosition","ownerDepartmentId"}`, `ownerDepartmentId`를 생략하면 기존 값 유지), `DELETE /api/admin/documents/{id}`(소프트 삭제).

## 대화

새 질문을 보내면 대화가 생성됩니다. 대화 목록·상세 조회와 삭제도 제공하며, 모든 요청은 본인 대화만 접근할 수 있습니다(관리자 예외 없음). 다른 사용자의 대화 ID는 `NOT_FOUND`로 응답합니다.

```http
GET /api/conversations
```

응답 `data`: 내 대화 목록(최근 갱신순) `[{"id","userId","title","createdAt","updatedAt"}, ...]`.

```http
GET /api/conversations/{id}
```

응답 `data`: `{"conversation": {...}, "messages": [{"id","conversationId","role":"USER"|"ASSISTANT","content","evidenceChunkIds":[...],"createdAt"}, ...]}`.

```http
DELETE /api/conversations/{id}
```

응답 `data`: `{"deleted": true}`.

## 질문

최소 버전입니다([Issue #20](https://github.com/9-jodae/docs199-Server/issues/20) 참고).

```http
POST /api/conversations/messages        (새 대화)
POST /api/conversations/{id}/messages   (기존 대화에 이어서 질문, 본인 대화만 가능)
Content-Type: application/json
Authorization: Bearer <token>

{"message": "연차는 며칠까지 이월할 수 있나요?"}
```

응답 `data`의 관련 형식은 다음과 같습니다.

```json
{
  "conversationId": "<uuid>",
  "answer": "연차 이월 한도는 5일입니다.",
  "answerType": "ANSWER",
  "sources": [{"id": "<chunk-uuid>", "text": "...", "source": "취업규칙", "documentId": "<uuid>", "documentTitle": "취업규칙", "versionId": "<uuid>", "charStart": 120, "charEnd": 160}],
  "evidence": [{"chunkId": "<chunk-uuid>", "source": "취업규칙", "documentId": "<uuid>", "versionId": "<uuid>", "quote": "연차는 최대 5일까지 이월할 수 있다.", "charStart": 120, "charEnd": 140}],
  "keywords": [{"text": "연차 이월", "chunkId": "<chunk-uuid>"}, {"text": "최대 5일", "chunkId": "<chunk-uuid>"}],
  "failedSources": [],
  "fallbackReason": null
}
```

`keywords`는 AI가 중요도순으로 보낸 최대 3개 문구입니다. Spring은 `chunkId`의 UUID, 최신 버전, 사용자 접근 권한을 확인하고 문구가 해당 청크 본문에 그대로 있을 때만 반환합니다. 중복 문구는 제거하며 검증할 항목이 없으면 `[]`입니다. 핵심 문장은 기존 `evidence[].quote`를 사용합니다. 키워드와 근거 문구는 대화 이력에 저장되지 않아 이전 대화를 다시 열면 복원되지 않습니다.

AI가 Spring에 보내는 evidence의 `documentId`, `versionId`, `charStart`, `charEnd`는 `null`일 수 있습니다. Spring 응답의 `chunkId`, `source`, `documentId`, `versionId`, `quote`는 DB 검증을 통과한 실제 값으로 채워지며 null이 아닙니다. 응답의 `charStart`와 `charEnd`는 위치를 계산할 수 없으면 둘 다 `null`입니다. 항목은 최대 3개이고, 검증 가능한 항목이 없으면 `[]`입니다. `quote`는 DB에서 확인한 청크 본문에 그대로 포함된 문구만 반환합니다.

`charStart`/`charEnd`는 문서 본문 기준 유니코드 코드 포인트 오프셋이며 끝 위치는 미포함입니다. 청크의 문서 본문 시작 위치와 청크 내 인용 위치로 계산합니다. 위치를 신뢰성 있게 계산할 수 없으면 두 값 모두 `null`입니다. 브라우저에서 원문 위치를 사용할 때는 JavaScript UTF-16 인덱스로 변환해야 합니다. `null`이면 해당 청크 단위로 표시합니다. 이 필드와 `evidence` 구조는 AI·프론트 합의 전 임시 계약입니다. 응답은 SSE가 아닌 단건이며 AI 처리 시간 때문에 최대 70초까지 걸릴 수 있습니다. 다른 사람의 대화 ID로 질문하면 `NOT_FOUND`입니다. `sources[].id`는 아래 근거 조회 API의 `{chunkId}`로 그대로 씁니다. 자세한 검증 규칙은 [AI 서버 계약](ai-server-contract.md#핵심-인용-문구-evidence)을 참고하세요.

## 근거 조회

질문 응답의 출처(`sources[].id`)를 눌렀을 때 원문 맥락과 하이라이트 위치를 보여주는 API입니다([Issue #21](https://github.com/9-jodae/docs199-Server/issues/21)).

```http
GET /api/evidence/{chunkId}
Authorization: Bearer <token>
```

응답 `data`: `{"chunkId", "documentId", "documentTitle", "extension", "versionId", "versionNumber", "content", "charStart", "charEnd", "page", "previousChunk", "nextChunk", "downloadUrl"}`.

- `content`가 현재 청크의 전체 텍스트이고, `charStart`/`charEnd`는 그 안에서 하이라이트할 위치입니다(둘 다 있으면 `content.substring(charStart, charEnd)`를 강조 표시). PDF는 아직 페이지·좌표(`bbox`)가 없어 `page`만 있고 `charStart`/`charEnd`가 `null`일 수 있습니다.
- `previousChunk`/`nextChunk`는 앞뒤 문맥용 텍스트이며 문서의 시작·끝이면 `null`입니다. 화면에는 `previousChunk` → `content`(하이라이트) → `nextChunk` 순서로 이어 붙여 보여주면 자연스럽습니다.
- `downloadUrl`은 Spring 원문 다운로드 API 경로입니다. 로그인 인증과 문서 접근 권한을 검사하며, 해당 근거의 버전을 내려받습니다.
- 청크 ID가 없거나, 삭제된 문서·이전 버전에 속하거나, 사용자가 접근할 수 없는 문서면 전부 `NOT_FOUND`로 응답합니다(어느 경우인지 구분하지 않음).

## 문서 관계 그래프

문서 간 선언된 참조·충돌 관계를 조회하는 그래프 API가 구현되었습니다. 전체 조회는 `GET /api/graph`, 특정 문서 중심 조회는 `GET /api/graph?documentId={id}&depth=1`을 사용합니다. 요청·응답 형식과 접근 제어 규칙은 [그래프 API 상세 안내](graph-api.md)를 참고하세요.


## 질문 응답 종류 (`answerType`, Issue #76)

응답의 `answerType`은 `ANSWER`, `NO_EVIDENCE`, `CLARIFICATION`, `SMALL_TALK` 중 하나입니다. `NO_EVIDENCE`일 때만 재시도 UI를 표시하고, 나머지는 `answer`를 표시합니다. AI가 필드를 생략하면 원본 `sources`가 비었을 때 `NO_EVIDENCE`, 있으면 `ANSWER`로 추론합니다. `ANSWER`의 출처가 모두 접근 권한 필터링에서 제거되거나 AI가 보낸 출처·근거·키워드 중 하나의 목록 전체가 검증에서 제외되면 답변을 `관련 문서를 찾지 못했습니다.`로 바꾸고 `NO_EVIDENCE`를 반환합니다. AI가 선언한 유형은 이 검증을 우회하지 못합니다.

대화 이력에 `answerType`을 저장하지 않습니다. 대화를 다시 열면 과거의 되묻기·일상 대화가 정확히 복원되지 않을 수 있습니다.
