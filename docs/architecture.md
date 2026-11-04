# 구조

클라이언트는 인증·권한·문서 관리·로그를 담당하는 Spring 서버를 호출합니다. Python AI 서버는 파싱·청킹·임베딩·검색·답변을 맡습니다. 두 서버가 PostgreSQL과 pgvector를 공유하더라도 **스키마 마이그레이션은 이 저장소의 Flyway만** 실행합니다. 로컬 원문 파일은 MinIO에 저장하며 배포 저장소는 결정 필요입니다.

단일 Gradle 모듈의 `global`에는 Web, 보안, 저장소, 영속성, AI 전송 공통 코드가 있습니다. 기능은 `domain/<기능>`에 둡니다. 권한 확인은 Spring 서버에서 수행합니다.

벡터 저장 방식은 [pgvector 안내](pgvector.md)를 참고합니다. AI 계약은 [AI 서버 계약](ai-server-contract.md)에 기록합니다. 로컬 개발용 API는 `local` 프로필에서만 활성화합니다.
