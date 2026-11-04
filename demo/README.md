# 모아온 시연 문서

**모아온**은 이 데모만을 위한 가상 회사입니다. 문서 본문과 사람 이름, 수치는 모두 허구입니다. 문서 19개 파일(논리 문서 18개), 질문 26개를 제공합니다.

## 구성

| 위치 | 내용 |
| --- | --- |
| `documents/local/` | 일반 업로드를 가정한 MD·PDF |
| `documents/obsidian/` | `[[문서 이름]]` 링크가 있는 MD 3개와 업로드 제외 확인용 `.obsidian/app.json` |
| `manifest.json` | 파일 경로, 의도한 출처, 권한, 작성 정보, 버전·참조·충돌 관계 |
| `eval-set.json` | 질문, 기대 답변, 문서·조항·인용구, 답변 가능·불가 계정 |
| `tools/catalog.py` | 문서 본문의 원본 데이터 |
| `tools/generate_documents.py` | MD와 텍스트 PDF 및 매니페스트 재생성 |
| `tools/generate_eval.py` | 평가 세트 재생성 |
| `tools/validate_demo.py` | 파일·근거·권한·PDF 페이지·텍스트 추출 점검 |
| `tools/upload_demo.py` | 현재 관리 API로 업로드하는 스크립트 |

PDF 9개, MD 10개입니다. 각 문서에는 제목, 작성 정보, 조항, 표가 있고 PDF는 A4 1쪽입니다. `출장비 규정`은 v1(숙박비 70,000원)과 v2(90,000원)를 같은 소스 상대 경로로 순서대로 올려 개정합니다. `취업규칙`과 `연차 운영 지침`은 이월 한도가 각각 5일·7일로 충돌합니다. `고객 할인 안내`와 `전략 고객 할인 승인 기준`은 일반 5%·전략 고객 12%를 구별합니다. 두 경우 모두 권한에 따라 같은 질문에 보이는 근거가 달라집니다. 이 차이는 충돌이나 조건을 밝힌 뒤 답해야 하며, 접근할 수 없는 문서의 수치를 누설해서는 안 됩니다.

## 생성 및 확인

Python 3.10 이상과 `pdftotext`(Poppler)가 필요합니다. Windows의 Git 배포본에도 `pdftotext`가 포함될 수 있습니다. 가상 환경에서 생성합니다.

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r demo/tools/requirements.txt
.\.venv\Scripts\python.exe demo/tools/generate_documents.py
.\.venv\Scripts\python.exe demo/tools/generate_eval.py
.\.venv\Scripts\python.exe demo/tools/validate_demo.py
```

PDF는 [Google Fonts의 나눔고딕 Regular](https://github.com/google/fonts/blob/main/ofl/nanumgothic/NanumGothic-Regular.ttf)를 임베드합니다. 폰트 저작권과 SIL Open Font License 1.1 전문은 [`tools/fonts/OFL.txt`](tools/fonts/OFL.txt)에 있으며, 원본 제공처는 [Google Fonts의 nanumgothic 디렉터리](https://github.com/google/fonts/tree/main/ofl/nanumgothic)입니다. PDF에서 한글을 선택·검색하거나 `pdftotext`로 추출할 수 있습니다.

## 업로드

스크립트는 기본적으로 계획만 출력합니다. 실제 호출은 `--execute`를 붙입니다. 관리자 계정의 토큰 또는 이메일·비밀번호를 환경 변수로 전달합니다. 비밀번호와 토큰은 명령 인자에 넣지 않습니다.

```powershell
$env:DOCS199_BASE_URL = 'http://localhost:8080'
$env:DOCS199_EMAIL = 'doc.admin@local.docs199'
$env:DOCS199_PASSWORD = '<로컬 데모 비밀번호>'
.\.venv\Scripts\python.exe demo/tools/upload_demo.py
```

부서 UUID는 `GET /api/departments`로 자동 조회하므로 수동 입력이 필요 없습니다. 이름이 바뀌었거나 로컬 DB와 다른 값을 써야 하면 `--department-id '부서명=UUID'`로 개별 보정할 수 있습니다(반복 가능, 선택 사항).

```powershell
.\.venv\Scripts\python.exe demo/tools/upload_demo.py --execute --check-exclusions
```

스크립트는 이름이 같은 소스를 재사용하고 현재 버전의 해시가 같으면 건너뜁니다. 개정 문서는 v1 다음 v2 순서로 올립니다. `.obsidian/app.json`은 `--check-exclusions`일 때 업로드 API에 보내고 `EXCLUDED` 응답을 확인합니다. 실행 결과에는 토큰·비밀번호·문서 본문·Presigned URL을 출력하지 않습니다. 인증 정보 보호를 위해 HTTPS 또는 localhost 주소만 허용합니다.

### 현재 API와 매니페스트의 차이

- 매니페스트의 `sourceKind`(`LOCAL`·`OBSIDIAN`)대로 소스를 실제로 생성합니다(#9 반영). 이전에는 둘 다 `UPLOAD`였습니다.
- 소유 부서(`ownerDepartmentId`), 작성자(`author`), 작성일(`authoredAt`), 원본 수정 시각(`sourceModifiedAt`)은 현재 업로드·개정 API가 받으므로 매니페스트 값을 그대로 전송합니다(#10·#11 반영).
- 의도한 문서 유형(`documentType`)과 표시 제목(`title`)은 여전히 업로드 API가 받지 않습니다. 문서 유형은 AI 처리 콜백으로만 채워지고, 제목은 파일명에서 그대로 파생되어 매니페스트 값과 다를 수 있습니다. 스크립트는 이 두 필드를 보내지 않고 실행 시 경고를 출력합니다.
- `eval-set.json`의 `shouldNotAnswerAccounts`는 해당 사례의 **전체 기대 답변과 근거**를 볼 수 없는 계정입니다. 이 계정이 공개 문서에 근거한 일부 답변을 볼 수는 있습니다.
- `eval-set.json`의 모든 계정은 이제 실제 시드 계정입니다. 기존 `sales.manager@example.invalid`/`hr.manager@example.invalid` 가상 프로필을 영업팀·인사팀 MANAGER/CONFIDENTIAL 시드 계정(`sales.manager@local.docs199`, `hr.manager@local.docs199`)으로 대체했습니다(`docs/local-setup.md` 참고). 더 이상 남은 가상 프로필은 없습니다.
- 평가 세트의 답변은 기대 결과입니다. 질문·검색 API와 AI 처리는 이 자료의 범위 밖입니다.
