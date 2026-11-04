# Add a domain

1. Read `AGENTS.md`, `docs/adding-a-domain.md`, and the relevant Korean product documentation. Confirm the requirement before coding.
2. Add the entity, a new Flyway migration, Repository, Service, Controller, and tests in that order. Keep DTOs separate from entities. Follow `docs/ai/rules/spring-kotlin.md`.
3. Define access checks at the service boundary and scope queries as needed. Follow `docs/access-control.md`; record unresolved rules instead of guessing.
4. Isolate tests per `docs/ai/rules/testing.md`. Update the relevant Korean docs and run `docs/ai/workflows/verify.md`.
