# 문서 관리

관리 API는 `DOC_ADMIN` 또는 `SYSTEM_ADMIN` 토큰으로 `/api/admin` 아래에서 사용합니다. 소스 종류(`SourceKind`)는 `LOCAL`, `OBSIDIAN`, `NOTION`입니다. 소스 생성은 `POST /api/admin/sources`, 목록은 `GET /api/admin/sources`입니다. `LOCAL`과 `OBSIDIAN`은 생성할 수 있고, `NOTION`은 enum만 있고 생성은 거부합니다(연동은 별도 Issue). 과거 `UPLOAD` 값으로 적재된 소스는 마이그레이션(`V5__source_kind_split.sql`)으로 모두 `LOCAL`로 변환했습니다.

여러 파일 업로드는 `POST /api/admin/sources/{sourceId}/documents`에 multipart `files`와 같은 순서의 `paths`를 반복해 보냅니다. `paths`는 소스 내부 상대 경로입니다. `clearance`, `departmentIds`, `minimumPosition`을 선택적으로 지정합니다. 숨김 파일·폴더, `.obsidian/`, `.trash/`, 허용되지 않은 확장자는 `EXCLUDED`와 사유로 반환합니다. 허용 확장자는 `ALLOWED_EXTENSIONS` 환경 변수로 설정하며 기본값은 `pdf,md`입니다. 내용의 SHA-256이 현재 버전과 같으면 `UNCHANGED`, 다르면 새 버전을 만듭니다. 응답은 파일 순서에 맞춘 결과 목록입니다. 이 제외 규칙은 소스 종류(`LOCAL`/`OBSIDIAN`)에 관계없이 동일하게 적용됩니다.

관리 목록·상세·권한 변경·개정·소프트 삭제·다운로드 URL·job 조회는 `/api/admin/documents`와 `/api/admin/ingestion-jobs/{id}`에서 제공합니다. 직원 목록·상세·다운로드 URL·상태는 `/api/documents` 아래에서 제공합니다. 직원 목록은 `AccessScope` SQL 조건으로, 상세와 URL은 `AccessPolicy`로 검사합니다. 목록 필터는 `sourceId`, `sourceKind`, `extension`, `documentType`, `authoredFrom`, `authoredTo`, `status`, `page`, `size`입니다. 목록 크기는 1~100입니다.

**가정:** 업로드 상대 경로를 소스 내 고유 외부 ID로 씁니다. 소프트 삭제한 같은 경로를 다시 올리면 기존 문서의 다음 버전으로 복구합니다. 개정에는 기존 권한을 유지합니다. 작성자·작성일은 현재 API에서 입력받지 않아 `NULL`이며 추후 메타데이터 계약이 정해지면 확장합니다.

DB 트랜잭션이 롤백되면 업로드한 객체를 삭제합니다. 삭제가 실패하면 `storage_cleanup_jobs`에 남기고 주기적으로 다시 시도합니다. DB까지 사용할 수 없어 정리 예약에 실패한 경우는 로그의 예외 유형으로 확인해야 합니다. 별도 분산 트랜잭션은 사용하지 않습니다.

새 버전으로 교체할 때 기존 문서 유형을 비우고 커밋 후 job을 생성합니다. 프로세스가 그 사이에 종료되거나 PENDING job 전송 전에 중단되면 주기적 복구 작업이 누락 버전·PENDING job을 다시 제출합니다. AI 서버는 동일한 `jobId` 요청을 멱등하게 처리해야 합니다. 완료·실패 job은 뒤늦은 콜백으로 상태가 되돌아가지 않으며, 문서 유형은 현재 버전의 완료 결과만 갱신합니다.

`chunks`와 `document_links`는 AI 팀과 임베딩 차원·링크 계약을 합의한 뒤 추가합니다. 배포 S3 구현과 Notion 연동은 범위 밖입니다.
