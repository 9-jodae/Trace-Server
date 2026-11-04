# Add a domain

1. Confirm requirements, resource ownership, API contract and access rules. Create `domain/<feature>`.
2. Add an entity extending `BaseEntity` when timestamps are needed. Define the PostgreSQL schema in a new `V<number>__description.sql` Flyway migration. Never use Hibernate schema generation.
3. Add a Spring Data repository for persistence.
4. Add a service for business logic and transactions. Invoke `AccessPolicy` before exposing protected resources; replace the default allow implementation once policy is agreed.
5. Add request and response DTOs plus a controller with validation. Return `ApiResponse` and use `PageResponse` for pages. Map expected failures to `ErrorCode`.
6. Add tests for service decisions and API/security behavior; use the Testcontainers base for DB and MinIO paths. Run `./gradlew ktlintCheck test build`.

Example shape only (not compiled):

```kotlin
@RestController
@RequestMapping("/api/example")
class ExampleController(private val service: ExampleService)
```
