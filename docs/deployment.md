# 팀 서버 배포

## 최초 설정

서버에서 `docker ps`와 `ss -tlnp`로 다른 팀 서비스와 8080·18080 포트 사용 여부를 먼저 확인합니다. 저장소를 `~/docs199-Server`에 복제하고 `git config core.hooksPath .githooks`를 실행합니다. `.env.prod.example`의 변수 이름을 참고해 **서버의 저장소 루트에만** `.env`를 만듭니다. `.env`는 Git과 Docker 빌드 컨텍스트에서 제외됩니다.

`scripts/init-prod-env.sh`를 실행하면 `DB_PASSWORD`, `JWT_SECRET`, `INTERNAL_SECRET`, `DEMO_PASSWORD`를 각각 `openssl rand -base64 32`로 생성해 `.env`에 기록하고 `chmod 600 .env`를 적용합니다. 기존 `.env`는 덮어쓰지 않습니다. 화면이나 로그에 비밀값을 출력하지 않습니다. 스크립트는 `STORAGE_TYPE=s3`, `S3_BUCKET=hackathon-e3-t09-data`, `S3_PREFIX=uploads/`, `AWS_REGION=ap-southeast-1`, `DEMO_SEED=true`, `CORS_ORIGINS=http://localhost:3000`을 설정합니다. AWS 액세스 키는 만들거나 설정하지 않습니다. 앱 컨테이너는 인스턴스 역할을 SDK 기본 자격 증명 체인으로 사용합니다.

## 배포와 확인

```bash
scripts/init-prod-env.sh # 최초 한 번만
scripts/deploy.sh feat/deploy
curl -fsS http://127.0.0.1:8080/api/health
docker compose -f compose.prod.yaml ps
docker compose -f compose.prod.yaml logs --tail=100 app
```

스크립트는 원격 변경을 가져온 뒤 브랜치를 checkout하고 fast-forward pull만 수행합니다. PostgreSQL 준비를 기다려 앱을 시작하고, 헬스체크가 2분 안에 성공하지 않으면 실패 코드로 종료합니다. 배포 이미지의 `commit`은 빌드 시 짧은 Git SHA입니다. 외부 확인 주소는 `http://3.0.74.114:8080/api/health`입니다. 외부 8080 포트는 health-gateway가 `/api/health`만 전달하며 그 밖의 경로는 404로 거부합니다. 앱의 인증·문서 API는 서버 loopback `127.0.0.1:18080`에서만 접근합니다. `/actuator/health`는 상세 정보를 노출하지 않습니다.

`docker compose -f compose.prod.yaml restart app`으로 앱을 재시작합니다. 로그에는 비밀값, 토큰, 문서 내용, Presigned URL을 남기지 않습니다. PostgreSQL과 Neo4j는 호스트 `127.0.0.1`에만 포트를 열어(AI 팀원의 SSH 터널 접속용, [AI 연동 접속 안내](ai-integration-setup.md) 참고) 외부에는 노출하지 않으며 named volume을 유지합니다. AI 서버는 이번 배포에서 mock 모드입니다.

## Neo4j 메모리 제한

Neo4j Community 컨테이너는 힙 1G, 페이지 캐시 512M로 제한합니다. 이 서버는 Spring 앱·PostgreSQL과 함께 여러 팀이 공유하며, 앞으로 AI 서버도 같은 호스트에 올라올 예정입니다. Neo4j의 기본 메모리 설정은 호스트 전체 메모리를 기준으로 자동 계산되어 단독 서버를 가정하므로, 공유 호스트에서는 다른 컨테이너의 메모리를 빼앗지 않도록 명시적으로 낮은 값을 고정합니다. 데모 규모 데이터셋에는 충분한 크기입니다.

## 롤백

이전 커밋을 확인하고 서버에서 `scripts/deploy.sh <이전-커밋-SHA>`를 실행합니다. 스크립트가 해당 커밋을 checkout해 이미지를 다시 빌드합니다. 데이터베이스 마이그레이션은 자동으로 되돌리지 않으므로, 이전 코드가 현재 스키마와 호환되는지 확인한 뒤 실행합니다. 저장된 데이터나 볼륨을 삭제하지 않습니다.

## 데모 계정

`DEMO_SEED=true`일 때만 초기 계정을 생성합니다. 암호는 서버의 `~/docs199-Server/.env`에 있는 `DEMO_PASSWORD` 값이며 보고나 저장소에 적지 않습니다. 기존 계정의 암호는 재배포 시 바뀌지 않습니다.

| 이메일 | 부서 | 직급 | 역할 |
| --- | --- | --- | --- |
| `hr.staff@local.docs199` | 인사팀 | STAFF | EMPLOYEE |
| `dev.manager@local.docs199` | 개발팀 | MANAGER | EMPLOYEE |
| `ops.director@local.docs199` | 경영지원팀 | DIRECTOR | EMPLOYEE |
| `doc.admin@local.docs199` | 경영지원팀 | MANAGER | DOC_ADMIN |
| `system.admin@local.docs199` | 경영지원팀 | DIRECTOR | SYSTEM_ADMIN |
| `sales.manager@local.docs199` | 영업팀 | MANAGER | EMPLOYEE |
| `hr.manager@local.docs199` | 인사팀 | MANAGER | EMPLOYEE |

## 남은 결정

- Issue #5: MinIO 비가용 시 로컬/테스트 기동 정책. 이번 S3 배포와 별개입니다.
- AI 서버의 실제 주소와 처리 계약이 정해지면 mock 모드를 전환합니다.
