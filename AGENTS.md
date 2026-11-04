# 저장소 개발 규칙

이 파일은 작업 규칙의 단일 기준입니다. 세부 절차는 `docs/`를 참조합니다.

## 구조와 책임

- 단일 Gradle 모듈과 단순한 레이어 구조를 유지합니다. 공통 코드는 `global`, 기능 코드는 `domain/<기능>`에 둡니다.
- Controller는 HTTP 매핑·입력 검증, Service는 규칙·트랜잭션, Repository는 조회·저장을 맡습니다. 엔티티와 API DTO를 분리합니다.
- 새 도메인은 엔티티 → Flyway 마이그레이션 → Repository → Service → Controller → 테스트 순서로 추가합니다. 상세 절차는 `docs/adding-a-domain.md`를 참조합니다.
- 스키마는 **이 저장소의 Flyway에서만** 관리합니다. 기존 마이그레이션은 수정하지 않고 새 버전을 추가합니다. AI 서버는 스키마를 변경하지 않습니다.

## 금지 사항과 검증

- 확정되지 않은 도메인·API·권한을 추측해 구현하지 않습니다. 비밀값·토큰·문서 본문·Presigned URL을 커밋하거나 로그에 남기지 않습니다.
- 변경에 맞춰 README와 관련 `docs/`를 갱신합니다. 테스트 상태를 격리하고 `./gradlew ktlintCheck test build` (Windows: `.\gradlew.bat`)로 검증합니다.

## 브랜치·커밋·PR

- 작업 전 디렉터리, `git status`, 브랜치, `git fetch` 결과를 확인합니다. 기존 변경을 되돌리거나 관련 없는 파일을 수정하지 않습니다.
- `main`·`develop`에 직접 push하지 않습니다. 기능 브랜치에서 의미 단위로 커밋하고 `develop` 대상 PR을 엽니다. 선행 PR 의존 관계를 본문에 적습니다.
- 커밋은 `type(scope): 설명` 형식이며 type은 feat, fix, docs, test, chore, refactor 중 하나입니다. PR에는 이유·내용·검증 결과·관련 Issue를 한국어로 적습니다.
