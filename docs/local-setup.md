# 로컬 실행

JDK 25, Docker Desktop과 Docker Compose가 필요합니다. Gradle은 고정된 Wrapper가 내려받습니다.

저장소를 복제한 뒤 `git config core.hooksPath .githooks`를 실행해 pre-push 훅을 활성화합니다. 훅은 원격 브랜치 삭제와 non-fast-forward push를 거부합니다. 이미 push한 브랜치는 rebase하지 말고 `git merge`로 갱신합니다.

1. `docker compose up -d`
2. `./gradlew bootRun` (Windows PowerShell: `.\gradlew.bat bootRun`)
3. `http://localhost:8080/actuator/health`에서 `UP`을 확인하고 `http://localhost:8080/swagger-ui.html`을 엽니다.
4. `POST /api/dev/token`으로 1시간짜리 시스템 관리자 로컬 토큰을 발급해 Swagger의 Authorize에 입력하고 `GET /api/users/me`를 호출합니다.
5. `POST /api/dev/storage/upload`의 multipart `file`은 저장소 키와 15분 유효한 다운로드 URL을 반환합니다. `GET /api/dev/ai/stream`으로 mock SSE를 확인합니다.

기본값은 로컬 전용입니다. [.env.example](../.env.example)을 참고해 환경 변수를 셸이나 IDE에 설정합니다. Spring은 `.env`를 자동으로 읽지 않습니다. 운영 프로필에는 모든 비밀값과 주소를 환경 변수로 전달합니다. MinIO 콘솔은 `http://localhost:9001`입니다.

`docker compose down`은 서비스를 중지하고 볼륨을 보존합니다. 초기화가 필요하면 데이터를 확인한 뒤 볼륨을 별도로 정리합니다.

로컬 데모 계정의 공통 비밀번호는 `local-demo-1234`입니다. 이메일은 `hr.staff@local.docs199`, `dev.manager@local.docs199`, `ops.director@local.docs199`, `doc.admin@local.docs199`, `system.admin@local.docs199`, `sales.manager@local.docs199`, `hr.manager@local.docs199`입니다. `POST /api/auth/login`에 이메일과 비밀번호를 보내 토큰을 발급합니다. 운영 환경에는 시드가 들어가지 않습니다.

| 이메일 | 부서 | 직급 | 역할 | 보안 등급 |
| --- | --- | --- | --- | --- |
| `hr.staff@local.docs199` | 인사팀 | STAFF | EMPLOYEE | PUBLIC |
| `dev.manager@local.docs199` | 개발팀 | MANAGER | EMPLOYEE | INTERNAL |
| `ops.director@local.docs199` | 경영지원팀 | DIRECTOR | EMPLOYEE | CONFIDENTIAL |
| `doc.admin@local.docs199` | 경영지원팀 | MANAGER | DOC_ADMIN | CONFIDENTIAL |
| `system.admin@local.docs199` | 경영지원팀 | DIRECTOR | SYSTEM_ADMIN | CONFIDENTIAL |
| `sales.manager@local.docs199` | 영업팀 | MANAGER | EMPLOYEE | CONFIDENTIAL |
| `hr.manager@local.docs199` | 인사팀 | MANAGER | EMPLOYEE | CONFIDENTIAL |

`sales.manager@local.docs199`와 `hr.manager@local.docs199`는 데모 문서의 권한 차이 시연(기밀 등급·영업팀 열람)을 위해 추가했습니다. 부서 UUID는 로그인 후 `GET /api/departments`로 조회합니다.

문서 업로드는 [문서 관리 안내](document-management.md)를 참고하세요. `ALLOWED_EXTENSIONS`로 허용 형식을 지정하고 `INTERNAL_SECRET`을 AI 서버와 공유합니다. `AI_MODE=mock`이면 별도 AI 서버 없이 처리 상태를 확인할 수 있습니다.
