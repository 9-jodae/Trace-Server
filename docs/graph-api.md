# 문서 관계 그래프 API 계약 (Issue #22)

> **상태: 계약 확정안.** 프론트 의견은 오늘 오후 3시까지 받습니다. 그때까지 의견이 없으면 이 형식으로 구현을 진행합니다.

## 방식

Spring이 PostgreSQL에 저장된 **선언된 관계**로 그래프를 만듭니다. AI 서버가 별도로 쌓는 Neo4j 그래프(임베딩·탐색용)와는 다른 것입니다. 지금은 사람이 지정한 시연용 관계이고, 자동으로 추출한 관계가 아닙니다. 데모의 원천 데이터는 `demo/manifest.json`의 각 문서 항목에 있는 `references`(참조)와 `conflicts`(충돌, `item`/`thisValue`/`otherValue` 포함)입니다. 나중에 관계를 Neo4j 기반 자동 추출로 바꾸더라도 이 API의 요청·응답 형식은 그대로 유지합니다.

## 엔드포인트

```http
GET /api/graph
Authorization: Bearer <token>
```

로그인한 사용자가 볼 수 있는 문서 전체로 그래프를 만듭니다. 깊이 제한이 없습니다.

```http
GET /api/graph?documentId={id}&depth=1
Authorization: Bearer <token>
```

`documentId`를 중심으로 `depth`만큼 떨어진 부분 그래프만 돌려줍니다. `depth`는 `1` 또는 `2`만 허용하고 생략하면 `1`입니다. `depth`는 `documentId`와 함께만 쓸 수 있습니다.

**탐색 방향**: 깊이는 `REFERENCES`의 방향과 무관하게, 즉 모든 엣지를 **무방향**으로 취급한 홉 수로 계산합니다. 중심 문서를 참조하는 문서든 중심 문서가 참조하는 문서든 1홉이면 모두 포함합니다. 접근 가능한 전체 그래프(`GET /api/graph`와 동일한 권한 필터를 적용한 그래프) 위에서 중심 노드부터 너비 우선 탐색(BFS)으로 `depth` 이내 노드 집합을 구하고, 그 노드 집합 안에서 **양 끝이 모두 포함된 모든 엣지**를 돌려줍니다(탐색 트리에 쓰인 엣지만이 아니라, 선택된 노드들 사이의 모든 엣지). 이는 "엣지는 양 끝 문서가 모두 보일 때만 포함"하는 전체 그래프 규칙을 부분 그래프에도 그대로 적용한 것입니다.

## 응답

공통 응답 래퍼(`{"success","data","error"}`) 안에 다음 형태로 담습니다.

```json
{
  "nodes": [
    {
      "id": "<documentId UUID>",
      "title": "취업규칙",
      "documentType": "ETC",
      "ownerDepartment": {"id": "<UUID>", "name": "인사팀"},
      "clearance": "PUBLIC",
      "currentVersion": 1,
      "versionCount": 1
    }
  ],
  "edges": [
    {
      "id": "<관계 행 UUID>",
      "source": "<documentId UUID>",
      "target": "<documentId UUID>",
      "type": "REFERENCES",
      "label": "참조",
      "detail": null
    },
    {
      "id": "<관계 행 UUID>",
      "source": "<leave-policy documentId>",
      "target": "<leave-operation documentId>",
      "type": "CONFLICTS",
      "label": "충돌",
      "detail": {"item": "연차 이월 가능 일수", "thisValue": "5일", "otherValue": "7일"}
    }
  ]
}
```

- `source`/`target`은 항상 `documentId`(UUID)입니다. `REFERENCES`는 "source가 target을 참조한다"는 방향을 그대로 유지합니다. `CONFLICTS`는 관계 자체에 방향 의미가 없으므로 **같은 문서 쌍 간 같은 충돌 항목은 정확히 한 행으로만 저장**합니다. 저장 시 `source_document_id`를 두 문서 UUID 중 더 작은 값(텍스트 비교 기준)으로 정규화하고, `detail.thisValue`는 그 `source` 쪽 값, `detail.otherValue`는 `target` 쪽 값으로 맞춰 넣습니다. 즉 같은 쌍을 역방향으로 다시 적재해도 같은 행을 갱신할 뿐 새 행이 생기지 않습니다.
- `label`은 `type`에서 바로 계산하는 고정 문자열(`"REFERENCES"` → `"참조"`, `"CONFLICTS"` → `"충돌"`)이며 관계 행에 별도로 저장하지 않습니다.
- `detail`은 `CONFLICTS`에만 있고 `REFERENCES`는 `null`입니다.
- `versionCount`는 삭제되지 않은 모든 버전 수, `currentVersion`은 `documents.current_version`입니다.

## 권한 규칙

- 호출자가 볼 수 없는 문서는 **노드도 엣지도 응답에 전혀 포함하지 않습니다**(존재 자체를 숨김, `GET /api/documents/{id}`의 404 원칙과 동일).
- 엣지는 양 끝 문서가 **모두** 보일 때만 포함합니다. 한쪽만 보이면 그 엣지도, 반대쪽 노드도 나가지 않습니다.
- `CONFLICTS`의 `detail`(실제 값, 예: "5일"/"7일")은 양쪽 문서가 모두 보이는 사용자에게만 나갑니다. 계산 기준은 엣지 포함 기준과 같으므로, `detail`이 숨겨지는 경우는 사실상 엣지 자체가 응답에서 빠지는 경우와 같습니다. 즉 응답에 포함된 `CONFLICTS` 엣지는 항상 `detail`을 채워서 보냅니다.
- 접근 판정은 기존 `AccessPolicy`/`accessible_document_versions` SQL 함수와 **완전히 같은 기준**을 재사용합니다. 그래프 전용 접근 규칙을 새로 만들지 않습니다.
- `SYSTEM_ADMIN`은 전체를 봅니다. `DOC_ADMIN`은 이 규칙에서 자동 예외가 아닙니다(문서 열람 규칙과 동일).

## 오류

| 상황 | 코드 | HTTP |
| --- | --- | --- |
| `documentId`가 UUID 형식이 아님 | `INVALID_INPUT` | 400 |
| `depth`가 1·2가 아님, 또는 `documentId` 없이 `depth`만 보냄 | `INVALID_INPUT` | 400 |
| `documentId`가 존재하지 않거나 호출자가 볼 수 없음 | `NOT_FOUND` | 404 |

전체 그래프(`documentId` 없음)는 결과가 비어 있어도(볼 수 있는 문서가 하나도 없거나 관계가 없음) `200`과 빈 `nodes`/`edges` 배열입니다.

## 프론트 연동

- 노드를 클릭하면 상세는 `GET /api/documents/{id}`로 조회합니다.
- 근거 하이라이트가 필요하면 기존 근거 조회 API `GET /api/evidence/{chunkId}`를 그대로 씁니다. 그래프 API는 청크·근거 정보를 포함하지 않습니다.

## 다음 단계(구현 PR 예고)

아래는 구현 PR에서 그대로 따를 초안이며, 이 문서가 머지된 뒤 코드로 옮겨집니다.

- 마이그레이션 `V14__document_relations.sql`: `document_relations(id uuid pk, source_document_id uuid, target_document_id uuid, relation_type text, detail jsonb null, created_at timestamptz)`. `(source_document_id, target_document_id, relation_type)` 유니크 제약으로 중복 등록을 막고, 양쪽 컬럼에 인덱스를 둡니다. `CONFLICTS`는 위 정규화 규칙(작은 UUID를 `source`로)으로 적재·갱신해 역방향 중복이 생기지 않게 합니다.
- **`ai_app`에는 이 테이블에 어떤 권한도 부여하지 않습니다.** 그래프는 Spring이 `AccessPolicy`로 필터링해서만 내보내는 기능이고, AI 서버가 원본 `document_relations`를 직접 읽으면 권한 밖 문서의 `detail`(연차 이월 일수 같은 실제 값)까지 그대로 노출되어 API가 보장하는 존재 은닉·내용 보호가 DB 경로에서 무력화됩니다. AI 서버가 그래프 데이터가 필요해지면 그때 별도 합의로 권한 필터링된 인터페이스를 추가합니다(`docs/ai/rules/security.md`, `docs/ai-server-contract.md`의 `ai_app` 권한 원칙과 동일).
- 조회 서비스는 접근 가능 문서 목록(`accessible_document_versions` 등 기존 함수)로 먼저 필터링한 뒤 그 범위 안의 관계만 그래프로 구성합니다. 별도 권한 규칙을 새로 만들지 않습니다.
- 관리자 전용 등록·삭제: `POST /api/admin/document-relations`, `DELETE /api/admin/document-relations/{id}` (`DOC_ADMIN` 이상). 등록 API도 `CONFLICTS` 정규화 규칙을 적용해 역방향 재등록을 갱신으로 처리합니다.
- 적재 스크립트 `scripts/load-demo-relations.py`: `demo/manifest.json`의 `references`/`conflicts`를 관리자 API로 멱등 적재(이미 있는 관계는 건너뜀, `CONFLICTS`는 방향이 달라도 같은 쌍으로 인식).
