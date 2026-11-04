# docs199 Server

Kotlin/Spring Boot foundation for a future internal document service. No document domain is implemented yet.

## Run locally

Install JDK 25 and Docker Desktop, then:

```bash
docker compose up -d
./gradlew bootRun
```

On Windows PowerShell use `.\gradlew.bat bootRun`. Open `http://localhost:8080/actuator/health` (UP) and `http://localhost:8080/swagger-ui.html`. Get a local token with `POST /api/dev/token`, authorize in Swagger, then call `/api/dev/me`, `/api/dev/storage/upload`, or `/api/dev/ai/stream`. Defaults are for local development; see [.env.example](.env.example) and [local setup](docs/local-setup.md).

## Develop

Read [adding a domain](docs/adding-a-domain.md), [conventions](docs/conventions.md), [architecture](docs/architecture.md), and the [AI contract template](docs/ai-server-contract.md). Run `./gradlew ktlintCheck test build` before a PR.
