# Local setup

Requirements: JDK 25, Docker Desktop, Docker Compose. `./gradlew` downloads the pinned Gradle wrapper.

1. `docker compose up -d`
2. `./gradlew bootRun` (Windows PowerShell: `.\gradlew.bat bootRun`)
3. Check `http://localhost:8080/actuator/health` and Swagger `http://localhost:8080/swagger-ui.html`.
4. `POST /api/dev/token` yields a one-hour local token. In Swagger, authorize with that bearer token, then call `GET /api/dev/me`.
5. `POST /api/dev/storage/upload` with multipart `file` returns a key and a 15-minute presigned download URL. `GET /api/dev/ai/stream` shows mock SSE.

Defaults are local-only. Copy `.env.example` to `.env` for reference and export values in your shell or IDE; Spring does not load `.env` automatically. Production requires all secret and address variables. MinIO console: `http://localhost:9001`.

`docker compose down` stops services and preserves volumes. For a clean local reset, remove volumes explicitly after checking their contents.
