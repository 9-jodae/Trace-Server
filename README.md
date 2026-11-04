# docs199 서버

사내 문서 검색 서비스를 위한 Kotlin/Spring Boot 백엔드입니다.

## 로컬 실행

JDK 25와 Docker Desktop을 설치한 뒤 실행합니다.

```bash
docker compose up -d
./gradlew bootRun
```

Windows PowerShell에서는 `.\gradlew.bat bootRun`을 사용합니다. `http://localhost:8080/actuator/health`가 `UP`인지 확인하고 `http://localhost:8080/swagger-ui.html`을 엽니다. `POST /api/dev/token`으로 로컬 토큰을 발급해 Swagger의 Authorize에 입력합니다. 자세한 내용은 [로컬 실행 안내](docs/local-setup.md)를 참고하세요.

## 개발

도메인은 [도메인 추가 안내](docs/adding-a-domain.md)에 따라 추가합니다. [개발 규약](docs/conventions.md), [구조](docs/architecture.md), [AI 서버 계약](docs/ai-server-contract.md)도 확인하세요. PR 전에는 `./gradlew ktlintCheck test build`를 실행합니다.

사용자·조직·문서 접근 규칙은 [접근 권한 안내](docs/access-control.md)를 참고하세요.
