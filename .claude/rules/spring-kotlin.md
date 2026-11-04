# Spring/Kotlin 규칙

엔티티와 API DTO를 분리하고, 스키마는 Flyway에서만 관리합니다. 트랜잭션과 업무 규칙은 Service에 둡니다. 예상 가능한 예외는 `ErrorCode`와 `ApiException`으로 표현합니다. 자세한 기준은 `AGENTS.md`, `docs/conventions.md`, `docs/adding-a-domain.md`를 따릅니다.
