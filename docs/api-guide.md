# 프론트·앱 연동 안내

이 문서는 프론트·앱 팀이 백엔드 API를 연동하는 데 필요한 내용만 모았습니다. 세부 규칙은 각 안내 문서([접근 권한](access-control.md), [문서 관리](document-management.md), [대화](conversations.md))를 참고하세요.

## 서버 주소

- 로컬: `http://localhost:8080`
- 팀 서버(외부 확인용): `http://3.0.74.114:8080` — `/api/health`만 외부에 열려 있습니다. 실제 연동 주소는 프론트 배포 환경에 맞춰 별도로 확정합니다([Issue #8](https://github.com/9-jodae/docs199-Server/issues/8) 참고).

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

로컬은 `docs/local-setup.md`, 팀 서버는 `docs/deployment.md`의 데모 계정 표를 참고하세요. 비밀번호는 이 문서에 적지 않습니다. 로컬은 고정값, 팀 서버는 팀장에게 개별로 받으세요.

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
- `clearance`(`PUBLIC`/`INTERNAL`/`CONFIDENTIAL`), `departmentIds`(반복 가능, 비우면 전체 부서 열람 가능), `minimumPosition`(`STAFF`/`SENIOR`/`MANAGER`/`DIRECTOR`), `ownerDepartmentId`, `author`, `authoredAt`(`YYYY-MM-DD`), `sourceModifiedAt`(ISO 인스턴트, 예: `2026-03-01T00:00:00Z`)는 모두 선택 입력입니다.
- 소스를 먼저 만들어야 합니다: `POST /api/admin/sources`(`{"kind": "LOCAL"|"OBSIDIAN", "name": "...", "defaultDepartmentId": null, "defaultClearance": "PUBLIC"}`), 목록은 `GET /api/admin/sources`. `NOTION`은 아직 생성할 수 없습니다.
- 응답 `data`는 파일 순서에 맞춘 배열, 각 항목은 `{"path", "documentId", "version", "outcome", "reason"}`. `outcome`은 `CREATED`/`UPDATED`/`UNCHANGED`/`EXCLUDED`(사유는 `reason`)입니다.
- 같은 상대 경로로 다시 올리면 새 버전(개정)이 됩니다. 전용 개정 엔드포인트도 있습니다: `POST /api/admin/documents/{id}/revisions`(`file` 하나, `author`/`authoredAt`/`sourceModifiedAt` 선택).
- 의도한 문서 유형(`documentType`)과 표시 제목은 이 API로 입력할 수 없습니다. 유형은 AI 처리가 끝나야 채워지고, 제목은 파일명에서 그대로 파생됩니다.

## 문서 목록·상세·다운로드

직원용(본인 권한 범위만, `/api/documents`)과 관리자용(전체, `/api/admin/documents`)이 거의 같은 형태입니다.

```http
GET /api/documents?sourceId=&sourceKind=LOCAL&extension=pdf&documentType=REPORT&authoredFrom=2026-01-01&authoredTo=2026-12-31&status=DONE&ownerDepartmentId=&page=0&size=20
Authorization: Bearer <token>
```

모든 쿼리 파라미터는 선택입니다. `sourceKind`는 `LOCAL`/`OBSIDIAN`/`NOTION`, `status`는 처리 상태(`PENDING`/`PROCESSING`/`DONE`/`FAILED`)입니다. 응답 `data`는 `PageResponse<문서>`이며 각 문서는 `id`, `sourceId`, `externalId`, `title`, `relativePath`, `extension`, `documentType`, `author`, `authoredAt`, `clearance`, `minimumPosition`, `currentVersion`, `deletedAt`, `ownerDepartmentId`, `sourceModifiedAt`를 포함합니다.

```http
GET /api/documents/{id}
```

응답 `data`: `{"document", "departmentIds", "versions": [{"id","number","fileSize","contentHash","uploadedBy","createdAt"}], "job": {"id","versionId","status","errorMessage","startedAt","finishedAt"} | null}`. 권한이 없으면 `NOT_FOUND`(존재 자체를 숨김).

```http
GET /api/documents/{id}/download-url
```

응답 `data`: `{"url": "https://... (15분 유효 presigned URL)"}`. 이 URL은 화면에 노출하거나 로그에 남기지 않습니다.

```http
GET /api/documents/{id}/status
```

응답 `data`: 처리 작업(`job`) 객체 또는 `null`(아직 처리 요청 전).

관리자 전용 추가 기능: `PATCH /api/admin/documents/{id}/access`(`{"clearance","departmentIds","minimumPosition","ownerDepartmentId"}`, `ownerDepartmentId`를 생략하면 기존 값 유지), `DELETE /api/admin/documents/{id}`(소프트 삭제).

## 대화

질문 API(준비 중)가 쓸 대화 저장소입니다. 지금은 **생성 API가 없고** 조회·삭제만 가능합니다. 모든 요청은 본인 대화만 접근할 수 있으며(관리자 예외 없음), 다른 사용자의 대화 ID는 `NOT_FOUND`로 응답합니다.

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

## 아직 준비 중인 것

- **질문 API**: 질문을 보내고 답변·근거를 받는 API([Issue #20](https://github.com/9-jodae/docs199-Server/issues/20)). AI 연동 계약이 확정되는 대로 추가됩니다.
- **근거 조회 API**: 답변의 근거(문서·위치·인용)를 조회하는 API([Issue #21](https://github.com/9-jodae/docs199-Server/issues/21)).
- **문서 관계 그래프 API**: 문서 간 참조·유사도 그래프([Issue #15](https://github.com/9-jodae/docs199-Server/issues/15) 이후).

이 셋은 화면 설계를 먼저 시작해도 되지만, 실제 연동은 해당 Issue가 닫힌 뒤에 가능합니다.
