# 문서 관리

관리 API는 `DOC_ADMIN` 또는 `SYSTEM_ADMIN` 토큰으로 `/api/admin` 아래에서 사용합니다. 업로드 소스 생성은 `POST /api/admin/sources`, 목록은 `GET /api/admin/sources`입니다. `NOTION`은 enum만 있고 생성은 거부합니다.

여러 파일 업로드는 `POST /api/admin/sources/{sourceId}/documents`에 multipart `files`와 같은 순서의 `paths`를 반복해 보냅니다. `paths`는 소스 내부 상대 경로입니다. `clearance`, `departmentIds`, `minimumPosition`을 선택적으로 지정합니다. 숨김 파일·폴더, `.obsidian/`, `.trash/`, 허용되지 않은 확장자는 `EXCLUDED`와 사유로 반환합니다. 허용 확장자는 `ALLOWED_EXTENSIONS` 환경 변수로 설정하며 기본값은 `pdf,md`입니다. 내용의 SHA-256이 현재 버전과 같으면 `UNCHANGED`, 다르면 새 버전을 만듭니다. 응답은 파일 순서에 맞춘 결과 목록입니다.

관리 목록·상세·권한 변경·개정·소프트 삭제·다운로드 URL·job 조회는 `/api/admin/documents`와 `/api/admin/ingestion-jobs/{id}`에서 제공합니다. 직원 목록·상세·다운로드 URL·상태는 `/api/documents` 아래에서 제공합니다. 직원 목록은 `AccessScope` SQL 조건으로, 상세와 URL은 `AccessPolicy`로 검사합니다. 목록 필터는 `sourceId`, `extension`, `documentType`, `authoredFrom`, `authoredTo`, `status`, `page`, `size`입니다. 목록 크기는 1~100입니다.

**가정:** 업로드 상대 경로를 소스 내 고유 외부 ID로 씁니다. 소프트 삭제한 같은 경로를 다시 올리면 기존 문서의 다음 버전으로 복구합니다. 개정에는 기존 권한을 유지합니다. 작성자·작성일은 현재 API에서 입력받지 않아 `NULL`이며 추후 메타데이터 계약이 정해지면 확장합니다. 개별 파일 업로드는 각자 트랜잭션으로 처리되지만 저장소 쓰기와 DB 커밋 사이의 분산 트랜잭션은 없으므로 실패 시 고아 객체 정리 정책은 결정 필요입니다.

`chunks`와 `document_links`는 AI 팀과 임베딩 차원·링크 계약을 합의한 뒤 추가합니다. 배포 S3 구현과 Notion 연동은 범위 밖입니다.
