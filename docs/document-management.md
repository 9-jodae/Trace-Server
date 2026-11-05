# 문서 관리

업로드 API는 선택 입력 `titles`를 `files`/`paths`와 같은 순서로 반복해 받을 수 있습니다. 제목을 보내면 파일 수와 개수가 같아야 하며 빈 제목은 허용하지 않습니다. 생략하면 상대 경로의 파일명을 표시 제목으로 사용합니다.

**가정:** 파일 내용의 SHA-256이 현재 버전과 같으면 업로드로 명시한 `author`, `authoredAt`, `sourceModifiedAt`, `titles`만 문서 메타데이터에 반영합니다. 입력하지 않은 값은 기존 값을 유지합니다. 이 경우 새 버전이나 AI 처리 작업은 만들지 않습니다. 권한 등 나머지 문서 속성은 변경하지 않습니다.

관리 API는 `DOC_ADMIN` 또는 `SYSTEM_ADMIN` 토큰으로 `/api/admin` 아래에서 사용합니다. 소스 종류(`SourceKind`)는 `LOCAL`, `OBSIDIAN`, `NOTION`입니다. 소스 생성은 `POST /api/admin/sources`, 목록은 `GET /api/admin/sources`입니다. `LOCAL`과 `OBSIDIAN`은 생성할 수 있고, `NOTION`은 enum만 있고 생성은 거부합니다(연동은 별도 Issue). 과거 `UPLOAD` 값으로 적재된 소스는 마이그레이션(`V5__source_kind_split.sql`)으로 모두 `LOCAL`로 변환했습니다.

여러 파일 업로드는 `POST /api/admin/sources/{sourceId}/documents`에 multipart `files`와 같은 순서의 `paths`를 반복해 보냅니다. `paths`는 소스 내부 상대 경로입니다. `clearance`, `departmentIds`, `minimumPosition`, `ownerDepartmentId`(소유 부서)를 선택적으로 지정합니다. `ownerDepartmentId`를 지정하지 않으면 소스의 기본 부서(`defaultDepartmentId`)를 사용하며, 둘 다 없으면 소유 부서 없이 생성됩니다. 소유 부서는 열람 권한(`clearance`/`departmentIds`/`minimumPosition`)과 무관합니다. 자세한 내용은 [접근 권한 안내](access-control.md)를 참고하세요. 숨김 파일·폴더, `.obsidian/`, `.trash/`, 허용되지 않은 확장자는 `EXCLUDED`와 사유로 반환합니다. 허용 확장자는 `ALLOWED_EXTENSIONS` 환경 변수로 설정하며 기본값은 `pdf,md`입니다. 파일 하나는 기본 최대 20MB(`MAX_UPLOAD_FILE_SIZE`), 요청 전체는 multipart 오버헤드를 포함해 기본 최대 100MB(`MAX_UPLOAD_REQUEST_SIZE`)입니다. 두 한도는 별도로 설정할 수 있습니다. 기존 `MAX_UPLOAD_SIZE`는 새 변수가 없을 때 두 한도에 대한 하위 호환값으로 사용합니다. 클라이언트는 요청 전체 한도와 multipart 오버헤드를 고려해 파일을 나눠 보내야 합니다. 내용의 SHA-256이 현재 버전과 같으면 `UNCHANGED`, 다르면 새 버전을 만듭니다. 응답은 파일 순서에 맞춘 결과 목록입니다. 이 제외 규칙은 소스 종류(`LOCAL`/`OBSIDIAN`)에 관계없이 동일하게 적용됩니다.

관리 목록·상세·권한 변경·개정·소프트 삭제·다운로드 URL·job 조회는 `/api/admin/documents`와 `/api/admin/ingestion-jobs/{id}`에서 제공합니다. 직원 목록·상세·다운로드 URL·상태는 `/api/documents` 아래에서 제공합니다. 직원 목록은 `AccessScope` SQL 조건으로, 상세와 URL은 `AccessPolicy`로 검사합니다. 목록 필터는 `sourceId`, `sourceKind`, `extension`, `documentType`, `authoredFrom`, `authoredTo`, `status`, `ownerDepartmentId`, `page`, `size`입니다. 목록 크기는 1~100입니다. 소유 부서는 `PATCH /api/admin/documents/{id}/access`의 `ownerDepartmentId` 필드로도 변경할 수 있으며, 값을 보내지 않으면 기존 소유 부서가 유지됩니다.

업로드·개정 API는 `author`(작성자), `authoredAt`(작성일), `sourceModifiedAt`(원본 수정 시각)을 선택 입력으로 받습니다. 값을 보내지 않으면 기존 값을 유지합니다(최초 업로드에서는 `NULL`). AI 처리 콜백(`POST /api/internal/ingestion-jobs/{id}/result`)도 같은 이름의 `author`, `authoredAt`을 선택적으로 보낼 수 있으며, **사용자가 이미 입력한 값이 있으면 덮어쓰지 않습니다**(`author`/`authoredAt`이 이미 채워진 문서는 콜백 값을 무시). **가정(AI 팀 확인 필요, [AI 연동 계약](ai-server-contract.md) 참고):** 이 "사용자 우선" 정책은 임시 결정이며, AI 추출 신뢰도가 더 높은 경우의 처리 방식은 AI 팀과 재논의가 필요합니다. `sourceModifiedAt`은 현재 콜백으로는 채울 수 없고 업로드·개정 API 입력만 반영합니다.

**가정:** 업로드 상대 경로를 소스 내 고유 외부 ID로 씁니다. 소프트 삭제한 같은 경로를 다시 올리면 기존 문서의 다음 버전으로 복구합니다. 개정에는 기존 권한을 유지합니다.

DB 트랜잭션이 롤백되면 업로드한 객체를 삭제합니다. 삭제가 실패하면 `storage_cleanup_jobs`에 남기고 주기적으로 다시 시도합니다. DB까지 사용할 수 없어 정리 예약에 실패한 경우는 로그의 예외 유형으로 확인해야 합니다. 별도 분산 트랜잭션은 사용하지 않습니다.

새 버전으로 교체할 때 기존 문서 유형을 비우고 커밋 후 job을 생성합니다. 프로세스가 그 사이에 종료되거나 PENDING job 전송 전에 중단되면 주기적 복구 작업이 누락 버전·PENDING job을 다시 제출합니다. AI 서버는 동일한 `jobId` 요청을 멱등하게 처리해야 합니다. 완료·실패 job은 뒤늦은 콜백으로 상태가 되돌아가지 않으며, 문서 유형은 현재 버전의 완료 결과만 갱신합니다.

`chunks`와 `document_links`는 AI 팀과 임베딩 차원·링크 계약을 합의한 뒤 추가합니다. 배포 S3 구현과 Notion 연동은 범위 밖입니다.

## 공통 문서 반영

공통 문서 반영은 `DocumentService.upsertDocument`가 담당합니다. 이 함수는 `MultipartFile`에 의존하지 않고 바이트, 콘텐츠 타입, 소스 내 외부 ID, 제목, 표시 경로, 확장자를 받습니다. 현재 multipart API는 어댑터로서 기존 상대 경로 검증·제외 규칙을 적용한 뒤 상대 경로를 외부 ID로 전달하고, 제목은 파일명에서 만듭니다. 외부 소스 연동은 변환된 콘텐츠와 해당 소스의 ID·제목·표시 경로를 같은 처리에 전달할 수 있습니다. 변경된 내용의 버전을 만들 때 제목·표시 경로·확장자도 최신 입력으로 갱신합니다. 동일 해시일 때 메타데이터를 갱신할지는 아직 정하지 않았으며, 현재는 버전과 메타데이터를 모두 그대로 둡니다.
