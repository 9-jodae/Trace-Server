# Testing

- Test decision logic with unit tests. Use Testcontainers for PostgreSQL and MinIO integration paths. Follow the existing test base and conventions in `docs/conventions.md`.
- Isolate database and storage state per test. Cover both authorized and rejected paths when access rules change.
- Run the command in `docs/ai/workflows/verify.md`; report real results in Korean.
