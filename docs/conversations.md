# 대화

질문 API(추후 구현)가 대화 맥락을 유지하기 위한 저장소입니다. `conversations`는 대화 하나, `conversation_messages`는 대화에 속한 메시지(`USER`/`ASSISTANT`)입니다. 메시지의 `evidence_chunk_ids`는 답변이 사용한 근거 청크 ID 배열(`uuid[]`)입니다. 청크 테이블이 아직 없어 외래 키를 걸지 않았으며, 추후 사용할 때는 현재 유효성·권한을 다시 확인해야 합니다.

API는 `GET /api/conversations`(내 대화 목록), `GET /api/conversations/{id}`(메시지 포함 상세), `DELETE /api/conversations/{id}`만 제공합니다. 세 API 모두 **본인 대화만** 접근할 수 있으며, 다른 사용자의 대화 ID로 조회·삭제를 시도하면 존재 여부를 노출하지 않기 위해 `NOT_FOUND`를 반환합니다. `SYSTEM_ADMIN`도 예외가 아닙니다.

대화 생성과 메시지 추가 API는 아직 없습니다. `ConversationService.create`/`addMessage`는 질문 API(별도 Issue)가 사용할 내부 메서드로만 존재합니다.

**결정 필요:** 대화·메시지의 보관 기간과 삭제 정책(예: 일정 기간 후 자동 삭제 여부).
