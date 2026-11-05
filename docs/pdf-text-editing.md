# 문서 본문 편집

관리자는 `GET /api/admin/documents/{id}/editable-text`로 현재 버전의 본문 텍스트와 `versionId`, `extension`, `saveExtension`을 받습니다. PDF는 페이지별 텍스트를 추출해 빈 줄 두 개로 이어 붙입니다. 이미지 안의 글자, 표의 배치, 서식, 페이지 좌표는 보존되지 않습니다. 텍스트가 없는 스캔 PDF에는 OCR이 적용되지 않습니다.

`POST /api/admin/documents/{id}/text-revision`에 JSON `{"baseVersionId":"<GET 응답의 versionId>","text":"<편집한 본문>"}`을 보냅니다. 다른 관리자가 먼저 저장했다면 409 `STALE_DOCUMENT_VERSION`을 반환하므로 최신 본문을 다시 읽어야 합니다. 운영 환경에서 `DOCUMENT_REVISION_ENABLED=false`이면 409 `REVISION_DISABLED`를 반환합니다.

PDF에서 편집한 텍스트는 새 **Markdown 버전**으로 저장합니다. 원본 PDF는 이전 버전으로 남아 내려받을 수 있습니다. 이후 현재 문서의 확장자는 `md`로 표시됩니다. `md`와 `txt`는 원래 확장자로 저장합니다. 새 버전의 검색 색인은 기존 수집 절차에 따라 갱신됩니다.

PDF 텍스트 추출은 PDFBox를 사용합니다. AI 서버의 PDF 추출기와 페이지 구분은 맞추지만, 추출기별 글자 순서와 공백이 달라 정확한 문자 위치 일치를 보장하지 않습니다. 편집 화면은 반환된 전체 본문을 표시하고, 인용 강조 위치는 별도로 검증해야 합니다.
