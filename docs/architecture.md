# Architecture

Clients call this Spring server for authentication, authorization, document management, and logs. The separate Python FastAPI service will handle parsing, embedding, search, and answer generation. Both may use PostgreSQL with pgvector; object bytes live in MinIO. The Spring server owns API access decisions before forwarding any authorized document scope to AI.

This repository is one Gradle module. `global` contains shared HTTP, security, storage, persistence and AI transport. Future features live in `domain/<feature>` with controller, service, repository and entity as needed. A concrete AI contract and document permission model are **결정 필요**.

The vector storage approach is described in [pgvector](pgvector.md). `AccessPolicy` currently allows access and must be replaced before any protected document feature is exposed. The local development endpoints are absent outside the `local` profile.
