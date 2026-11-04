# Conventions

- Kotlin packages use `com.docs199.server.global` or `com.docs199.server.domain.<feature>`.
- API success: `{ "success": true, "data": ... , "error": null }`; error: `{ "success": false, "data": null, "error": { "code": "...", "message": "...", "details": ... } }`.
- Use `ErrorCode` and `ApiException`; validation details contain field and message. Do not leak internal exception text.
- Use `PageResponse` for list results; page is zero based.
- Controllers validate and map input. Services own behavior and transactions. Repositories handle persistence. New tables require Flyway migrations and `ddl-auto=validate`.
- Add unit tests for decisions and Testcontainers integration tests for DB and storage behavior. Keep state isolated.
- Never log bearer tokens, credentials, document bodies or private file URLs.
