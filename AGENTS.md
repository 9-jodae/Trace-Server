# Repository rules

- Single module, layered Kotlin Spring application. Put reusable infrastructure in `global`; create feature packages under `domain` only after requirements are agreed.
- A domain addition follows entity → Flyway migration → repository → service → controller → tests. See `docs/adding-a-domain.md`.
- Controllers handle HTTP mapping and validation; services own transactions and business rules; repositories only persist/query.
- Do not add domain tables, endpoints, DTOs, permissions, or AI contract fields speculatively. Keep secrets in environment variables. Never commit `.env`.
- Verify with `./gradlew ktlintCheck test build` (`.\gradlew.bat` on Windows); run `docker compose up -d` for integration tests.
- Commit focused units using `type(scope): 설명` where type is feat, fix, docs, test, chore, or refactor. Do not include unrelated workspace changes.
