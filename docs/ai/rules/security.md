# Security

- Never read, commit, or log secrets, tokens, document contents, presigned URLs, or `*.pem` files. Keep credentials in environment variables.
- Enforce authorization in the server before exposing document metadata or storage URLs. Use the shared access policy and query scope described in `docs/access-control.md`.
- Keep internal callbacks protected. Validate inputs and avoid logging sensitive request or exception content.
