# 내가 이 프로젝트에서 실제로 배운 것

> AI 시스템을 "사용"하는 것과 "구축"하는 것은 완전히 다르다.
> MindGraph-AI를 직접 만들면서 처음으로 체감한 것들의 기록.

---

## 1. LLM은 비결정적이다 — 코드처럼 다루면 안 된다

**이전**: LLM 호출 = API 호출. 입력이 같으면 출력이 정해진다.

**이후**: LLM은 같은 입력에도 다른 형식으로 응답한다.

```
기대: ["Docker", "2026"]
실제: "Here are the keywords I extracted:\n[\"Docker\", \"2026\"]\nThese are the main entities."
```

처음에 `objectMapper.readValue(response, String[].class)`로 직접 파싱했다가 즉시 터졌다.
LLM이 부연 설명을 붙이면 JSON 파싱이 실패한다.

해결책을 두 개 만들었다:
1. 시스템 메시지 영문화 (`"Return ONLY a JSON array. No explanations."`)
2. 정규식 fallback (`\[.*?\]`로 배열 부분만 추출)

두 번째가 핵심이다. **LLM 응답 파싱은 항상 실패할 수 있다고 가정해야 한다.**
실패해도 시스템이 멈추지 않도록 `Collections.emptyList()` 반환 — graceful degradation.

---

## 2. N+1은 교과서에서만 나오는 게 아니었다

JPA N+1 문제는 알고 있었다. 조심하면 안 생기는 줄 알았다.

이번에 직접 겪었다. 키워드 10개를 추출하고, 각 키워드마다 `findByName()`을 호출했다.
`spring.jpa.show-sql=true` 로그를 보니 SELECT가 수십 번 나갔다.

LLM이 이미 6초를 쓰는데 DB도 수십 번 왕복하고 있었다.

해결은 두 줄이었다:
```java
nodeRepository.findByNameIn(keywords)       // N → 1 쿼리
edgeRepository.findEdgesByNodeNamesIn()     // JOIN FETCH
```

**교훈**: ORM은 편하지만 어떤 SQL이 나가는지 반드시 확인해야 한다.
LLM처럼 이미 느린 작업이 있는 파이프라인에서 DB 오버헤드는 더 크게 체감된다.

---

## 3. 비동기 처리가 왜 필요한지 — 숫자로 체감했다

LLM 추론이 평균 6초다.

동기 처리: 사용자가 질문을 보내면 6초 대기 → 6초 후 응답.
비동기 처리: 202 Accepted 즉시 반환, 6초 후 결과는 DB에 저장.

API에서 200ms가 한계라는 걸 알고 있었다. 6초짜리 LLM을 동기로 연결해보고서야 체감했다.
RabbitMQ 도입이 단순히 "메시지 큐 써봤다"가 아니라, 실제 UX 문제 해결을 위한 선택이었다.

---

## 4. 장애 내성 설계 — DLQ를 왜 만들었나

처음엔 LLM 오류가 나면 로그 찍고 넘어갔다.

그런데 LLM이 일시적으로 오류를 내면 그 지식은 영구적으로 유실된다.
사용자가 올린 중요한 파일이 처리되지 않고 사라지는 것이다.

RabbitMQ DLQ를 설정하면 달라진다:
- 처리 실패 → 3회 재시도 (지수 백오프, thundering herd 방지)
- 재시도 실패 → DLQ 격리 (데이터 유실 없음)
- 나중에 DLQ에서 수동으로 재처리 가능

"데이터 유실 없는 아키텍처"를 처음으로 직접 설계해봤다.

---

## 5. 기술 스택은 "가장 좋은 것"이 아니라 "이 문제에 맞는 것"을 고른다

pgvector vs Pinecone, RabbitMQ vs Kafka, Neo4j vs 순수 PostgreSQL.

처음엔 가장 좋은 것을 고르려고 했다. 지금은 "이 시스템의 목적과 제약에 맞는 것"을 고른다.

- **pgvector**: 별도 벡터 DB 없이 PostgreSQL 안에서 처리. 운영 복잡도 0.
- **RabbitMQ**: Kafka는 이 규모에서 over-engineering. 단일 소비자, 재시도만 필요.
- **Neo4j**: PoC만 완료. 실제 2-hop 탐색이 답변 품질을 개선하는지 검증 후 본격 도입 결정.

기술 스택 선택 기준이 "최신 트렌드"에서 "실제 문제 해결"로 바뀌었다.

---

## 6. 테스트를 나중에 쓰면 더 힘들다

Phase 3에서 50개 테스트를 한꺼번에 썼다.

Service 코드를 먼저 다 짜고 나서 테스트를 쓰면 의존성이 복잡해서 Mock 구성이 어렵다.
`MindGraphService`는 SearchService, GraphService, NodeRepository, EdgeRepository,
ChatLanguageModel, ObjectMapper를 모두 주입받는다.
테스트 하나 짜는 데 Mock 설정이 길어진다.

설계 시점에 "이걸 어떻게 테스트하지?"를 먼저 생각했다면 의존성이 더 단순해졌을 것이다.

**다음 기능부터는 구현과 테스트를 함께 작성한다.**

---

## 7. 측정값이 있으면 설명이 달라진다

"N+1 쿼리를 최적화했습니다" vs "DB IO를 약 80% 절감했습니다 (show-sql 로그 기준)"
"RabbitMQ로 비동기 처리를 구현했습니다" vs "LLM 평균 6초 추론을 비동기로 분리해 API 응답을 202ms로 단축했습니다"

LLM-OPT 프로젝트에서 배운 것인데 MindGraph에서도 똑같이 적용된다.
수치가 있으면 선택의 이유가 설명되고, 면접에서도 전달력이 달라진다.

**앞으로는 최적화를 적용하기 전에 반드시 측정부터.**

---

## 8. "작동하는 것"과 "설명할 수 있는 것"은 다르다

LangChain4j @AiService가 내부적으로 어떻게 프록시를 생성하는지,
pgvector의 코사인 거리가 어떻게 계산되는지,
DLQ 설정에서 `default-requeue-rejected=false`가 정확히 무엇을 막는지.

처음에는 동작하면 넘어갔다. 문제가 생기거나 면접에서 물어보면 설명을 못했다.

이제는 구현할 때 "왜 이렇게 동작하는가"를 이해하고 문서에 기록한다.
이 TECH_KNOWLEDGE.md가 그 산물이다.
