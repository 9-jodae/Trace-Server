# 도메인 추가 절차

1. 요구사항, 리소스 소유권, API 계약, 접근 규칙을 확정하고 `domain/<기능>` 패키지를 만듭니다.
2. 필요한 엔티티를 정의합니다. 생성·수정 시각이 필요하면 `BaseEntity`를 상속합니다. **새** `V번호__설명.sql` Flyway 마이그레이션을 추가합니다. 기존 파일은 수정하지 않습니다.
3. Spring Data Repository를 추가하고 필요한 조회 조건을 명시합니다.
4. Service에서 업무 규칙과 트랜잭션을 구현합니다. 보호된 리소스는 `AccessPolicy`로 검증합니다.
5. 요청·응답 DTO와 입력 검증을 작성하고 Controller에서 `ApiResponse` 또는 `PageResponse`로 반환합니다. 예상 오류는 `ErrorCode`로 매핑합니다.
6. 규칙 단위 테스트와 API·보안·DB 통합 테스트를 추가합니다. DB와 MinIO가 필요한 테스트는 Testcontainers 베이스를 사용합니다.
7. 관련 `docs/`와 README를 갱신한 뒤 `./gradlew ktlintCheck test build`를 실행합니다.

형태 예시이며 실제 도메인 코드는 아닙니다.

```kotlin
@RestController
@RequestMapping("/api/example")
class ExampleController(private val service: ExampleService)
```
