# pgvector 사용 안내

Flyway V1이 `vector` 확장을 활성화합니다. 임베딩 모델과 차원·거리 계산·인덱스 전략을 AI 팀과 합의한 다음 도메인 마이그레이션에서 `vector(n)` 컬럼을 추가합니다.

JPA에서는 Spring Boot 4와 호환되는 Hibernate 벡터 타입을 확인해 매핑하거나, Repository에서 파라미터화된 네이티브 SQL로 벡터 쓰기와 유사도 조회를 처리합니다. 벡터 값을 SQL 문자열에 이어 붙이지 않습니다. 예시 형태: `SELECT id FROM embeddings ORDER BY embedding <=> CAST(:query AS vector) LIMIT :limit`.

**결정 필요:** 차원, 거리 척도, 인덱스 종류와 생성 시점.
