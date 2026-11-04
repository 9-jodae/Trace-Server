# 고정 버전

공식 프로젝트 자료로 2026-11-04에 확인했습니다.

- Spring Boot 4.1.2: https://docs.spring.io/spring-boot/system-requirements.html
- Kotlin 2.3.21: Spring Boot 4.1.2용 Spring Initializr 생성값 (https://start.spring.io/)
- Gradle 9.8.2: https://github.com/gradle/gradle/blob/master/released-versions.json
- springdoc 3.2.0: https://github.com/springdoc/springdoc-openapi
- MinIO Java SDK 9.0.3: https://github.com/minio/minio-java/releases
- MinIO 로컬 이미지 `RELEASE.2025-09-07T16-13-09Z`: https://github.com/minio/minio/releases/tag/RELEASE.2025-09-07T16-13-09Z
- pgvector 0.8.7 PostgreSQL 18 이미지: https://github.com/pgvector/pgvector#docker
- ktlint Gradle 플러그인 14.2.0: https://plugins.gradle.org/plugin/org.jlleitschuh.gradle.ktlint
- Testcontainers 2.0.5: https://java.testcontainers.org/

Kotlin 2.4.21은 최신 안정 버전이지만 ktlint Gradle 플러그인 14.2.0이 Kotlin 2.4 소스와 Gradle 스크립트를 파싱할 때 `Extensions storage is not registered` 오류가 발생했습니다. 호환 가능한 한 단계 낮은 2.3.21을 고정했고 Spring Initializr 생성값과 일치합니다. 이미지와 의존성은 `latest` 대신 버전을 고정합니다.

MinIO 이미지는 공식 릴리스 바이너리로 로컬 빌드하며 아키텍처별 SHA-256을 확인합니다. 보관된 Quay 이미지가 GitHub Actions에서 401을 반환해 해당 레지스트리 의존을 제거했습니다. **결정 필요:** 운영 오브젝트 스토리지 이미지와 갱신 정책.
