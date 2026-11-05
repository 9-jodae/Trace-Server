# 팀 서버 배포

## 최초 설정

서버에서 `docker ps`와 `ss -tlnp`로 다른 팀 서비스와 8080·18080 포트 사용 여부를 먼저 확인합니다. 저장소를 `~/docs199-Server`에 복제하고 `git config core.hooksPath .githooks`를 실행합니다. `.env.prod.example`의 변수 이름을 참고해 **서버의 저장소 루트에만** `.env`를 만듭니다. `.env`는 Git과 Docker 빌드 컨텍스트에서 제외됩니다.

`scripts/init-prod-env.sh`를 실행하면 `DB_PASSWORD`, `JWT_SECRET`, `INTERNAL_SECRET`, `DEMO_PASSWORD`를 각각 `openssl rand -hex 32`로 생성해 `.env`에 기록하고 `chmod 600 .env`를 적용합니다. `NEO4J_PASSWORD`, `AI_DB_PASSWORD` 등 이후 수동으로 추가하는 비밀값도 같은 방식(`openssl rand -hex 32`)을 씁니다. `base64` 출력은 `/`, `+`, `=`를 포함할 수 있어 `NEO4J_AUTH=neo4j/<password>`처럼 `/`를 구분자로 쓰는 값이나 셸·SQL 인용 규칙과 충돌할 수 있습니다(실제로 `/`가 섞인 `NEO4J_PASSWORD` 때문에 Neo4j 컨테이너가 기동하지 못한 사례가 있었습니다). `hex` 출력은 `[0-9a-f]`만 사용해 이런 충돌이 없습니다. 기존 `.env`는 덮어쓰지 않습니다. 화면이나 로그에 비밀값을 출력하지 않습니다. 스크립트는 `STORAGE_TYPE=s3`, `S3_BUCKET=hackathon-e3-t09-data`, `S3_PREFIX=uploads/`, `AWS_REGION=ap-southeast-1`, `DEMO_SEED=true`, `CORS_ORIGINS=http://localhost:3000`을 설정합니다. AWS 액세스 키는 만들거나 설정하지 않습니다. 앱 컨테이너는 인스턴스 역할을 SDK 기본 자격 증명 체인으로 사용합니다.

## 배포와 확인

```bash
scripts/init-prod-env.sh # 최초 한 번만
scripts/deploy.sh feat/deploy
curl -fsS http://127.0.0.1:8080/api/health
docker compose -f compose.prod.yaml ps
docker compose -f compose.prod.yaml logs --tail=100 app
```

스크립트는 원격 변경을 가져온 뒤 브랜치를 checkout하고 fast-forward pull만 수행합니다. PostgreSQL 준비를 기다려 앱을 시작한 다음 `api-gateway`만 재시작해 Nginx가 Compose DNS에서 새 앱 컨테이너 주소를 다시 확인하도록 합니다. 기존 Nginx는 시작 시점에 `app` 주소를 캐시할 수 있어 앱 컨테이너 교체 후에는 이전 주소로 요청할 수 있습니다. 이 재시작은 Spring 앱, PostgreSQL, Neo4j를 재시작하지 않습니다. 스크립트는 앱 직접 주소(`127.0.0.1:18080`)와 게이트웨이 주소(`127.0.0.1:8080`)를 각각 검사하고, 어느 검사에서 실패했는지 구분해 종료합니다. 배포 이미지의 `commit`은 빌드 시 짧은 Git SHA입니다. HTTPS 적용 전에는 `http://3.0.74.114:8080/api/health`로 확인합니다. HTTPS 적용 후에는 `https://api.docs199.exijn.com/api/health`를 사용합니다. 앱은 계속 서버 loopback `127.0.0.1:18080`에 바인딩되고 Nginx가 API 경로를 제한합니다. `/actuator/health`는 상세 정보를 노출하지 않습니다.

## HTTPS (API 전용)

`compose.prod.yaml`의 Caddy가 `api.docs199.exijn.com`에 자동 HTTPS를 제공하고, 같은 Compose 네트워크에서 Nginx `api-gateway:8081`로 요청을 전달합니다. Nginx의 기존 공개 `:8080`도 유지하므로 `http://3.0.74.114:8080`과 `http://api.docs199.exijn.com:8080`은 계속 사용할 수 있습니다. Caddy가 외부에 공개하는 `:80`은 인증서 발급과 HTTPS 리다이렉트에, `:443`은 HTTPS API에 사용합니다. 프론트엔드 경로는 구성하지 않습니다.

Nginx는 `:8080` 요청에 대해 `X-Forwarded-Proto`를 실제 연결 방식(`http`)으로 만들고 클라이언트가 보낸 `X-Forwarded-For`를 신뢰하지 않습니다. Caddy 전용 `:8081` 요청은 Compose 내부에서만 접근할 수 있으며, Nginx는 이 경로의 프로토콜을 `https`로 고정하고 Caddy가 정리한 클라이언트 주소를 전달합니다. 전달 호스트와 포트도 Nginx가 다시 설정하고, `X-Forwarded-Prefix`와 표준 `Forwarded` 헤더는 제거합니다. 외부에 `:8081`을 게시하지 않습니다. 기존 내부 경로 차단, 150MB 업로드 한도, 90초 응답 제한은 유지합니다.

Caddy 인증서 상태와 설정은 각각 `caddy-data`, `caddy-config` named volume에 저장됩니다. Caddy는 기본 동작으로 외부에서 직접 전달된 forwarded 헤더를 신뢰하지 않습니다. AWS 보안그룹의 80·443 인바운드 허용 여부는 별도로 확인해야 하며, 이 저장소 설정은 보안그룹을 변경하지 않습니다.

프론트가 Vercel에 배포되면 API 기본 주소를 `https://api.docs199.exijn.com`으로 설정하고 서버 `.env`의 `CORS_ORIGINS`에는 배포된 프론트의 정확한 origin을 추가합니다. CORS 변경은 Spring 앱 재시작이 필요하므로 HTTPS 구성과 별도로 적용합니다.

### 적용 전 확인

공유 서버에서 Caddy가 호스트 :80·:443을 사용하므로 작업 시간을 조율하고, 다른 컨테이너와 호스트 프로세스가 해당 포트를 사용하지 않는지 확인합니다. 포트가 사용 중이면 Caddy를 시작하지 않습니다. 현재 운영 API가 :8080을 사용 중이므로 이 바인딩을 유지합니다. 서버 작업 트리에 미커밋 변경이 있으면 checkout/pull을 진행하지 않습니다.

~~~bash
cd ~/docs199-Server
git status --short --branch
docker ps --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}'
sudo ss -ltnp '( sport = :80 or sport = :443 or sport = :8080 )'
git fetch origin
git checkout develop
git pull --ff-only origin develop
docker compose -f compose.prod.yaml config --quiet
~~~

### 적용 순서

아래는 적용 시 사용할 명령이며, 이 문서 변경 작업에서는 운영 서버에서 실행하지 않습니다. 기존 gateway 컨테이너는 새 내부 :8081 listener를 사용하도록 한 번 재생성해야 합니다. 그동안 해당 gateway를 통과하는 HTTP 요청이 잠시 실패할 수 있지만 Spring 앱, PostgreSQL, Neo4j는 재시작하지 않습니다. 먼저 새 Nginx 설정을 검증하고 gateway만 갱신합니다. 공개 :8080은 계속 바인딩됩니다.

~~~bash
docker compose -f compose.prod.yaml run --rm --no-deps --entrypoint nginx api-gateway -t
docker compose -f compose.prod.yaml up -d --no-deps api-gateway
curl -fsS http://127.0.0.1:8080/api/health
~~~

그 다음 Caddy만 시작합니다. `--no-deps`로 앱·DB·Neo4j 서비스는 건드리지 않습니다. Caddy 시작은 공유 호스트 `:80`·`:443`을 점유하므로 포트 확인과 팀 조율이 끝난 뒤 수행합니다.

~~~bash
docker compose -f compose.prod.yaml up -d --no-deps caddy
curl -fsS https://api.docs199.exijn.com/api/health
curl -sSI http://api.docs199.exijn.com/api/health
~~~

HTTPS 헬스체크는 200과 status: UP, HTTP 요청은 HTTPS URL로의 리다이렉트를 보여야 합니다. 인증서 발급에는 도메인 DNS와 외부에서 80·443으로 들어오는 연결이 필요합니다. 성공 여부를 확인하기 전에도 기존 :8080 주소는 그대로 유지됩니다.

### 롤백

Caddy가 실패하면 Caddy 컨테이너만 중지하고 제거합니다. 인증서 저장 volume과 기존 서비스는 그대로 둡니다. 기존 HTTP :8080은 계속 공개 상태입니다.

~~~bash
docker compose -f compose.prod.yaml stop caddy
docker compose -f compose.prod.yaml rm -f caddy
~~~

새 Nginx 설정 문제로 HTTP가 실패하면 롤백용 Compose override로 HTTP 전용 Nginx 설정을 적용합니다. 저장소 파일을 덮어쓰지 않으므로 Git 작업 트리는 깨끗하게 유지됩니다. gateway가 재생성되는 동안 짧은 API 중단이 있을 수 있습니다. 복구가 확인되면 Caddy만 중지·제거합니다. HTTPS 검증 후에도 `:8080`을 차단하거나 loopback으로 제한하지 않습니다.

~~~bash
docker compose -f compose.prod.yaml -f compose.https-rollback.yaml config --quiet
docker compose -f compose.prod.yaml -f compose.https-rollback.yaml run --rm --no-deps --entrypoint nginx api-gateway -t
docker compose -f compose.prod.yaml -f compose.https-rollback.yaml up -d --no-deps api-gateway
curl -fsS http://127.0.0.1:8080/api/health
docker compose -f compose.prod.yaml stop caddy
docker compose -f compose.prod.yaml rm -f caddy
~~~

### 외부 공개 범위

| 경로 | 외부 HTTP :8080 / HTTPS :443 | 비고 |
| --- | --- | --- |
| `GET /api/health` | 공개 | 메서드는 GET만 허용 |
| `/api/**` (그 외 전체) | 공개 | 인증·권한은 Spring이 검사 |
| `/api/internal/**`, `/api/dev/**` | 차단(404) | 내부·개발 전용 API |
| `/actuator/**`, `/swagger-ui/**`, `/v3/api-docs/**` | 차단(404) | 게이트웨이 이중 방어 |
| 그 외 경로 | 차단(404) | 프론트엔드 라우팅 없음 |

업로드 한도는 nginx `client_max_body_size 150m`, 질문 API 제한 시간은 `proxy_read_timeout 90s`입니다. HTTPS 요청은 Caddy에서 Nginx로 전달되고 같은 제한을 적용받습니다.

## 채팅과 적재 모드

운영에서는 질문·채팅 모드와 문서 적재 모드를 별도로 설정할 수 있습니다. `AI_MODE=http`는 채팅을 AI 서버로 연결하고 `AI_INGEST_MODE=mock`은 Spring의 미확정 `/ingest` 계약 호출을 막습니다. 이때 업로드 결과가 `DONE`이어도 청크·임베딩이 생긴 것이 아니므로 **새로 올린 문서는 수동 적재 전에는 검색되지 않습니다.**

`AI_INGEST_MODE`를 생략하면 기존 동작 호환을 위해 `AI_MODE`와 같은 모드가 사용됩니다. 업로드 후 `DONE`은 실제 색인 완료를 보장하지 않습니다. 시연용 자료의 JSON 내보내기, AI CLI 적재, 청크 상태 확인은 [시연 진행 문서의 사전 적재 절차](demo-runbook.md#사전-적재-절차)를 따릅니다.

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
