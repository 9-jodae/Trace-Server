# AI 팀 연동 접속 안내

AI 서버를 아직 팀 서버에 올리기 전, 로컬에서 운영 PostgreSQL·Neo4j에 접속해 개발·테스트하기 위한 안내입니다.

## SSH 터널

팀 서버 SSH 키와 사용자 이름을 받은 뒤, 로컬에서 다음과 같이 터널을 엽니다(`<키>`, `<서버>`는 개인별로 안내받은 값으로 바꿉니다).

```bash
ssh -i <키> -L 5432:localhost:5432 -L 7474:localhost:7474 -L 7687:localhost:7687 ubuntu@<서버>
```

터널을 유지하는 동안 로컬에서 각 서비스에 접속할 수 있습니다.

## 접속 정보

- **PostgreSQL**: `localhost:5432`, 데이터베이스 `docs199`, 계정 `ai_app`. 비밀번호는 서버 `.env`의 `AI_DB_PASSWORD` 값입니다(아래 "비밀번호" 참고).
- **Neo4j 브라우저**: `http://localhost:7474`
- **Neo4j Bolt**: `bolt://localhost:7687`, 계정 `neo4j`. 비밀번호는 서버 `.env`의 `NEO4J_PASSWORD` 값입니다.

## Docker Compose 네트워크 호스트명

AI 서버가 같은 Compose 네트워크에 올라가면(터널이 아니라 컨테이너 안에서 직접 접속할 때) 아래 호스트명을 씁니다.

- PostgreSQL: `postgres:5432`
- Neo4j: `neo4j:7687`(Bolt), `neo4j:7474`(브라우저/HTTP)
- Spring 서버: `app:8080`

## AI 서버 연결

AI 서버 코드는 [docs199-AI-v2](https://github.com/9-jodae/docs199-AI-v2) 저장소를 사용합니다. 기존 `docs199-AI` 저장소는 이전되어 사용하지 않습니다. 실행·설정 방법은 AI 저장소에서 확인합니다.

1. **같은 Compose 서비스로 올리기**: `compose.prod.yaml`의 `ai` 서비스 주석은 자리 표시이며 AI 팀과 이미지·설정 계약을 확정한 후 구성합니다. Spring은 내부 주소 `http://ai:8000`으로 접근합니다. AI 포트를 호스트에 게시하지 않습니다.
2. **서버 venv로 직접 띄우기**: AI 서버를 Docker 기본 브리지의 게이트웨이 주소에만 바인딩합니다. `0.0.0.0`은 외부 연결도 받을 수 있으므로 사용하지 않습니다. 서버에서 `docker network inspect bridge --format '{{(index .IPAM.Config 0).Gateway}}'`로 주소를 확인하고 AI 서버 실행 옵션에 지정합니다. Spring 컨테이너는 `http://host.docker.internal:8000`으로 접근합니다. 해당 주소가 컨테이너에서 가리키는 게이트웨이와 일치하고 외부 인터페이스에서 포트가 열리지 않는지 확인합니다.

두 방식 모두 AI 서버는 `X-Internal-Secret`을 검증해야 합니다. 인증만으로 외부 포트 공개가 안전해지는 것은 아니므로 AI 포트를 외부에 공개하지 않습니다.

### Spring을 mock에서 http로 전환

1. 서버 `~/docs199-Server/.env`에서 `AI_MODE=http`로, `AI_BASE_URL`을 방식 1이면 `http://ai:8000`, 방식 2면 `http://host.docker.internal:8000`으로 바꿉니다.
2. `flock /tmp/docs199-deploy.lock scripts/deploy.sh develop`로 잠금 배포합니다.
3. 외부 `GET /api/health` 응답의 커밋이 최신 `develop`인지 확인합니다.

### 되돌리기

`.env`의 `AI_MODE`를 `mock`으로 되돌리고 같은 방식으로 잠금 배포합니다(`AI_BASE_URL`은 그대로 둬도 무방합니다. `mock` 모드에서는 쓰이지 않습니다).

### `scripts/switch-ai-mode.sh`

위 `.env` 수정과 잠금 배포를 한 번에 수행하는 스크립트입니다.

```bash
scripts/switch-ai-mode.sh http http://host.docker.internal:8000   # mock -> http 전환
scripts/switch-ai-mode.sh mock                                     # http -> mock 되돌리기
```

`.env`의 `AI_MODE`·`AI_BASE_URL` 두 값만 바꾸고 수정 전 백업부터 배포까지 같은 잠금을 유지합니다. 잠금 대기는 최대 5분입니다. 서버 간 비밀이 외부로 전송되지 않도록 HTTP 주소는 위 두 내부 주소만 허용합니다. 백업 파일은 600 권한이며 Git에서 제외합니다. 비밀값은 화면에 출력하지 않습니다.

## `ai_app` 계정이 할 수 있는 것

- 읽기(`SELECT`)만: `documents`, `document_versions`, `document_departments`, `sources`, `departments`, `ingestion_jobs`
- 읽기·쓰기: `chunks` (`SELECT`, `INSERT`, `UPDATE`, `DELETE`)
- 실행: `accessible_document_versions`, `accessible_chunks`, `filter_accessible_chunk_ids`
- 접근 불가: `users`, `conversations`, `conversation_messages`, Flyway 이력 테이블
- 테이블 생성 등 DDL 불가

AI 서버가 쓰기 권한이 필요한 새 테이블(청크·그래프 메타데이터 등)이 생기면, 그 테이블을 만드는 마이그레이션에서 `ai_app`에 권한을 함께 부여합니다. 자세한 내용은 [AI 서버 계약](ai-server-contract.md)을 참고하세요.

## 비밀번호

`NEO4J_PASSWORD`, `AI_DB_PASSWORD` 값은 이 문서나 저장소 어디에도 적지 않습니다. 팀장에게 개별로 받으세요.
