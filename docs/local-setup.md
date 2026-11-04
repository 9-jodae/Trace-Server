# 로컬 실행

JDK 25, Docker Desktop과 Docker Compose가 필요합니다. Gradle은 고정된 Wrapper가 내려받습니다.

1. `docker compose up -d`
2. `./gradlew bootRun` (Windows PowerShell: `.\gradlew.bat bootRun`)
3. `http://localhost:8080/actuator/health`에서 `UP`을 확인하고 `http://localhost:8080/swagger-ui.html`을 엽니다.
4. `POST /api/dev/token`으로 1시간짜리 로컬 토큰을 발급해 Swagger의 Authorize에 입력하고 `GET /api/dev/me`를 호출합니다.
5. `POST /api/dev/storage/upload`의 multipart `file`은 저장소 키와 15분 유효한 다운로드 URL을 반환합니다. `GET /api/dev/ai/stream`으로 mock SSE를 확인합니다.

기본값은 로컬 전용입니다. [.env.example](../.env.example)을 참고해 환경 변수를 셸이나 IDE에 설정합니다. Spring은 `.env`를 자동으로 읽지 않습니다. 운영 프로필에는 모든 비밀값과 주소를 환경 변수로 전달합니다. MinIO 콘솔은 `http://localhost:9001`입니다.

`docker compose down`은 서비스를 중지하고 볼륨을 보존합니다. 초기화가 필요하면 데이터를 확인한 뒤 볼륨을 별도로 정리합니다.
