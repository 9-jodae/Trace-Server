# Security

- Never read, commit, or log secrets, tokens, document contents, presigned URLs, or `*.pem` files. Keep credentials in environment variables.
- Enforce authorization in the server before exposing document metadata or storage URLs. Use the shared access policy and query scope described in `docs/access-control.md`.
- Keep internal callbacks protected. Validate inputs and avoid logging sensitive request or exception content.
- The shared PostgreSQL role `ai_app` (see `docs/ai-server-contract.md`) only has `SELECT` on an explicit allowlist of tables. When a migration adds a table the AI server must write to, grant that specific access to `ai_app` in the same migration. Never broaden `ai_app`'s access to existing tables (`users`, `conversations`, `conversation_messages`, Flyway history) without an explicit agreement recorded in `docs/ai-server-contract.md`.
