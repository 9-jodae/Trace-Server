# Spring and Kotlin

- Keep Controllers responsible for HTTP mapping and validation, Services for rules and transactions, and Repositories for persistence. Keep entities separate from API DTOs. See `docs/conventions.md` and `docs/adding-a-domain.md`.
- Add schema changes only through a new Flyway migration in this repository. Never let JPA or the AI server alter the shared schema.
- Represent expected failures with `ErrorCode` and `ApiException`; use the common response format. See `docs/conventions.md`.
