# AI server contract template

**결정 필요** before implementing domain DTOs or production routes:

- Endpoint names, version, authentication between servers, correlation/request ID.
- Question request fields, limits, language, conversation context.
- Evidence response shape: document ID, immutable version, source offsets, citation text, confidence, errors.
- Authorization filter transfer: allowed document IDs or signed scope, expiry, and behavior when scope is empty.
- Streaming media type, event names, JSON schemas, heartbeat, completion and error events, cancellation.
- Idempotency, timeouts, retry safety and rate limits.

`AIClient` transports raw JSON and SSE until this contract is set. Configure `AI_MODE=mock` for local samples, `http` for the real server.
