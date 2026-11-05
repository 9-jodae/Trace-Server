# 문서 관계 그래프 API 계약 (Issue #22)

> **상태: 계약 확정안.** 프론트 의견은 오늘 오후 3시까지 받습니다. 그때까지 의견이 없으면 이 형식으로 구현을 진행합니다.

## 방식

Spring이 PostgreSQL에 저장된 관계로 그래프를 만듭니다. 사람이 지정하는 `REFERENCES`·`CONFLICTS`와, 현재 버전의 문서에 저장된 공통 주제 `TOPIC`을 함께 반환합니다. AI 서버가 별도로 쌓는 Neo4j 그래프(임베딩·탐색용)와는 다른 것입니다. 주제 추출은 AI 적재 단계의 책임이며, `GRAPH_TOPIC_EDGES_ENABLED` 기본값은 `false`입니다.
- 저장 시 문서 버전당 최대 3개 주제, 주제당 최대 100자로 제한됩니다. DB 트리거가 AI 계정의 직접 입력에도 이 상한을 적용합니다.

## 엔드포인트

```http
GET /api/graph
Authorization: Bearer <token>
```

로그인한 사용자가 볼 수 있는 문서 전체로 그래프를 만듭니다. 깊이 제한이 없습니다. **관계가 하나도 없는 접근 가능 문서도 고립 노드로 항상 포함**합니다 — `nodes`는 "호출자가 접근 가능한 모든 문서"의 집합이고, `edges`는 그중 관계가 선언된 쌍만 담는 별개의 집합입니다.

```http
GET /api/graph?documentId={id}&depth=1
Authorization: Bearer <token>
```

`documentId`를 중심으로 `depth`만큼 떨어진 부분 그래프만 돌려줍니다. `depth`는 `1` 또는 `2`만 허용하고 생략하면 `1`입니다. `depth`는 `documentId`와 함께만 쓸 수 있습니다. 중심 문서 자신은 관계가 하나도 없어도 **항상** `nodes`에 포함됩니다(전체 그래프의 고립 노드 포함 규칙과 동일하게, 중심 문서가 호출자에게 보이는 한 포함).

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
- `TOPIC`은 두 문서가 공유하는 주제를 하나의 간선으로 합칩니다. `source`/`target`은 UUID 문자열 오름차순이며, `label`과 `detail.topics`는 정규화된 주제 문자열 순서로 정렬합니다. 주제 매칭은 앞뒤 공백 제거와 영문 대소문자 무시를 적용합니다.
- `TOPIC` 간선 ID는 `UUID.nameUUIDFromBytes("docs199:topic:<source>:<target>")`로 계산하므로, 같은 문서 쌍은 주제 목록이 바뀌어도 같은 ID를 유지합니다.
- `TOPIC.detail`은 `{"topics":[...]}`입니다. 기존 `REFERENCES`와 `CONFLICTS`의 응답 형식은 유지합니다.
- `versionCount`는 삭제되지 않은 모든 버전 수, `currentVersion`은 `documents.current_version`입니다.
- `ownerDepartment`는 문서에 소유 부서가 지정되지 않은 경우 `null`입니다. 소유 부서는 열람 가능 부서 범위와 별개입니다.

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

전체 그래프(`documentId` 없음)는 접근 가능한 문서 전체를 `nodes`에 포함합니다. 관계가 없는 고립 문서도 포함하며, 이때 `edges`는 빈 배열일 수 있습니다. 접근 가능한 문서가 하나도 없을 때만 `nodes`와 `edges`가 모두 빈 배열이고, 어느 경우든 `200`을 반환합니다.

## 프론트 연동

- 노드를 클릭하면 상세는 `GET /api/documents/{id}`로 조회합니다.
- 근거 하이라이트가 필요하면 기존 근거 조회 API `GET /api/evidence/{chunkId}`를 그대로 씁니다. 그래프 API는 청크·근거 정보를 포함하지 않습니다.

## 다음 단계

주제 간선 구현은 기본 비활성화 상태로 제공됩니다. AI 적재기에서 주제 생성·저장을 합의한 뒤 시연 환경에서 설정을 켜 검증합니다.

## `REFERENCES` 경고

`REFERENCES` 엣지에는 `warnings` 배열이 포함됩니다. 경고가 없으면 빈 배열입니다. `source`는 참조하는 문서, `target`은 참조되는 문서입니다.

- `SECURITY_RISK`: 참조 문서의 보안 등급이 대상 문서보다 낮을 때 포함됩니다. 비교 순서는 `PUBLIC < INTERNAL < CONFIDENTIAL`입니다.
- `CONTENT_CHECK`: 대상 문서의 현재 버전 생성 시각이 참조 문서의 현재 버전보다 늦을 때 포함되며, `modifiedDocumentId`에 대상 문서 ID가 담깁니다.
- 다른 관계 유형에는 경고가 적용되지 않습니다. 권한상 볼 수 없는 문서와 그 관계는 응답에 포함되지 않으며, 경고 계산에도 영향을 주지 않습니다. 경고에는 보안 등급 값이 포함되지 않습니다.

예시:

```json
{
  "type": "REFERENCES",
  "warnings": [
    { "type": "SECURITY_RISK" },
    { "type": "CONTENT_CHECK", "modifiedDocumentId": "<참조 대상 문서 UUID>" }
  ]
}
```
