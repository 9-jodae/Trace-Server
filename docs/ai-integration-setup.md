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

## `ai_app` 계정이 할 수 있는 것

- 읽기(`SELECT`)만: `documents`, `document_versions`, `document_departments`, `sources`, `departments`, `ingestion_jobs`
- 접근 불가: `users`, `conversations`, `conversation_messages`, Flyway 이력 테이블
- 테이블 생성 등 DDL 불가

AI 서버가 쓰기 권한이 필요한 새 테이블(청크·그래프 메타데이터 등)이 생기면, 그 테이블을 만드는 마이그레이션에서 `ai_app`에 권한을 함께 부여합니다. 자세한 내용은 [AI 서버 계약](ai-server-contract.md)을 참고하세요.

## 비밀번호

`NEO4J_PASSWORD`, `AI_DB_PASSWORD` 값은 이 문서나 저장소 어디에도 적지 않습니다. 팀장에게 개별로 받으세요.
