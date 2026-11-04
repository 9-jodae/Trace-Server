# 개발 규약

- 패키지는 `com.jodae.docs199.global`과 `com.jodae.docs199.domain.<기능>`을 사용합니다.
- 성공 응답은 `{ "success": true, "data": ..., "error": null }`, 오류 응답은 `{ "success": false, "data": null, "error": { "code": "...", "message": "...", "details": ... } }`입니다. 목록은 0부터 시작하는 `PageResponse`를 사용합니다.
- 예상 가능한 실패는 `ErrorCode`와 `ApiException`으로 표현합니다. 검증 오류에는 필드와 메시지를 담되 내부 예외 텍스트를 노출하지 않습니다.
- Controller는 HTTP 매핑과 검증, Service는 규칙과 트랜잭션, Repository는 데이터 접근을 맡습니다. 엔티티를 API 응답으로 직접 반환하지 않습니다.
- 스키마 변경은 새 Flyway 마이그레이션으로만 수행하며 JPA `ddl-auto=validate`를 유지합니다. AI 서버는 스키마를 변경하지 않습니다.
- 결정 로직은 단위 테스트, DB·저장소·보안 흐름은 Testcontainers 통합 테스트로 확인합니다. 테스트 간 상태를 분리합니다.
- Bearer 토큰, 자격 증명, 문서 본문, 비공개 파일 URL은 로그에 남기지 않습니다.
