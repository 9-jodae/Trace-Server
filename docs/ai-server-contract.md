# AI 서버 계약

## 처리 요청 초안 — AI 팀 확인 필요

새 문서 버전의 DB 트랜잭션이 커밋되면 `ingestion_jobs`를 `PENDING`으로 생성하고 `AI_MODE=http`에서 `POST {AI_BASE_URL}/ingest`를 호출합니다. `X-Internal-Secret` 헤더와 다음 JSON을 전송합니다.

```json
{"jobId":"UUID","versionId":"UUID","storageKey":"documents/...","extension":"pdf"}
```

`AI_MODE=mock`에서는 잠시 뒤 `PROCESSING` → `DONE`으로 바꾸고 `md`를 `HANDOVER`, 다른 확장자를 `ETC`로 임시 분류합니다. 이는 실제 AI 분류 규칙이 아닙니다.

AI 서버는 `POST /api/internal/ingestion-jobs/{id}/result`에 같은 공유 비밀 헤더와 다음 JSON을 보냅니다.

```json
{"status":"DONE","errorMessage":null,"documentType":"REPORT"}
```

`status`는 `PROCESSING`, `DONE`, `FAILED`를 받습니다. `errorMessage`와 `documentType`은 선택적입니다. 공유 비밀은 `INTERNAL_SECRET` 환경 변수로 전달합니다. **AI 팀 확인 필요:** 요청·콜백 필드, 스토리지 접근 방법, 멱등성, 실패 재시도, 비밀 회전, callback 상태 전이.

## 질문·검색 계약 — 결정 필요

- 엔드포인트 이름·버전, 서버 간 인증, requestId 전달 방식
- 질문 요청 필드·길이 제한·언어·대화 문맥
- 근거 응답의 문서 ID·불변 버전·원문 위치·인용문·오류 형식
- `AccessScope(departmentId, position, clearance, isAdmin)` 전달과 SQL 조건 적용 방식
- SSE 이벤트 이름·JSON 스키마·heartbeat·완료·오류·취소
- 멱등성·타임아웃·안전한 재시도 범위·호출 제한

질문 API는 아직 구현하지 않았습니다. 공통 `AIClient`는 원시 JSON과 SSE 전송만 제공합니다.
