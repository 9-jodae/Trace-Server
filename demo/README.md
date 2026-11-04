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

현재 API에는 부서 목록 조회가 없으므로 부서 UUID 4개를 로컬 DB에서 조회해 `--department-id`로 전달해야 합니다. 로컬 Compose의 DB라면 다음으로 조회합니다.

```powershell
docker compose exec -T postgres psql -U docs199 -d docs199 -Atc 'SELECT name, id FROM departments ORDER BY name'
```

조회한 값을 아래처럼 각 부서에 대입합니다. 실제 UUID를 저장소에 커밋하지 않습니다.

```powershell
.\.venv\Scripts\python.exe demo/tools/upload_demo.py --execute --check-exclusions `
  --department-id '경영지원팀=<UUID>' `
  --department-id '인사팀=<UUID>' `
  --department-id '개발팀=<UUID>' `
  --department-id '영업팀=<UUID>'
```

스크립트는 이름이 같은 소스를 재사용하고 현재 버전의 해시가 같으면 건너뜁니다. 개정 문서는 v1 다음 v2 순서로 올립니다. `.obsidian/app.json`은 `--check-exclusions`일 때 업로드 API에 보내고 `EXCLUDED` 응답을 확인합니다. 실행 결과에는 토큰·비밀번호·문서 본문·Presigned URL을 출력하지 않습니다. 인증 정보 보호를 위해 HTTPS 또는 localhost 주소만 허용합니다.

### 현재 API와 매니페스트의 차이

- 매니페스트의 `sourceKind`는 시나리오상 출처인 `LOCAL`·`OBSIDIAN`입니다. 현재 백엔드는 `UPLOAD`만 생성할 수 있어 스크립트는 이름이 다른 `UPLOAD` 소스 두 개를 만듭니다. Obsidian 메타데이터와 링크는 파일 및 매니페스트에 유지합니다.
- 현재 업로드 API는 소유 부서, 작성자·작성일, 의도한 문서 유형과 표시 제목을 입력받지 않습니다. 해당 값은 매니페스트에만 있으며, API 응답과 다를 수 있습니다. #9~#11에서 계약이 정해지면 연결해야 합니다.
- `eval-set.json`의 `shouldNotAnswerAccounts`는 해당 사례의 **전체 기대 답변과 근거**를 볼 수 없는 계정입니다. 이 계정이 공개 문서에 근거한 일부 답변을 볼 수는 있습니다.
- `eval-set.json`의 `@example.invalid` 계정은 **가상 권한 프로필**이며 시드 계정이 아닙니다. 실제 API 평가에는 같은 부서·직급·등급의 계정 준비가 필요합니다. `@local.docs199` 계정은 `docs/local-setup.md`의 시드 프로필을 사용합니다.
- 평가 세트의 답변은 기대 결과입니다. 질문·검색 API와 AI 처리는 이 자료의 범위 밖입니다.
