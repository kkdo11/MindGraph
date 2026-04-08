# MindGraph-AI: 로컬 LLM 기반 개인 지식 그래프 AI 에이전트

> 파편화된 개인 지식을 LLM이 자동으로 지식 그래프로 구조화하고 RAG로 답변하는 로컬 AI 에이전트.
> 클라우드 API 없이 단일 GPU에서 동작, N+1 해결로 **DB IO 80% 절감**, 비동기 처리로 **API 응답 202ms**.

---

## 배경 — 어떤 문제를 해결하려 했나

기술을 공부하다 보면 메모가 쌓인다. 문제는 나중에 그 메모를 찾기가 너무 어렵다는 것이었다. Notion에 쓰고, 마크다운으로 정리하고, 코드에 주석으로 남기고... 정보는 파편화되고 연결되지 않는다.

"Docker를 공부했을 때 Kubernetes와 어떻게 연결했더라?"를 알기 위해서는 직접 찾아 읽어야 한다.

이 문제를 해결하고 싶었다. 메모를 입력하면 LLM이 알아서 개념들을 추출하고 관계를 연결하고, 나중에 질문하면 저장된 지식 그래프 위에서 답해주는 시스템.

조건이 하나 있었다 — 클라우드 API를 쓰지 않는다. RTX 4080 Super(16GB VRAM)가 있으니 로컬에서 직접 돌린다.

---

## 시스템 구조

```
[지식 수집]
  REST API ─────────────────┐
  로컬 파일 감지 (WatchService) ─┤ → RabbitMQ → 비동기 처리
                             │
                     [ExtractionListener]
                             │
              ┌──────────────┼──────────────┐
              ▼              ▼              ▼
        ① 지식 추출      ② 그래프 저장    ③ 벡터 저장
       (Qwen 2.5 14B)  (PostgreSQL)   (pgvector)
        LangChain4j       Node/Edge     1024차원
        @AiService        Find-or-Create  임베딩

[질문 응답]
  POST /api/graph/ask
              │
      ┌───────┴───────┐   CompletableFuture 병렬 실행
      ▼               ▼
  벡터 검색        그래프 검색
  (pgvector)    (키워드 추출 → 배치 조회 → 시계열 필터)
      │               │
      └───────┬───────┘
              ▼
         컨텍스트 병합
              ▼
       RAG 프롬프트 → LLM → 한국어 답변
```

---

## 구현 과정에서 만난 문제들

단순히 코드를 짜는 게 아니라, 실제 동작시키면서 예상하지 못한 문제들을 만났다. 그 문제들을 해결하는 과정이 이 프로젝트의 핵심이다.

---

### 문제 1. LLM이 JSON만 반환하지 않는다

LangChain4j `@AiService`로 키워드를 추출할 때, 기대한 응답은 이것이었다.

```
["Docker", "Kubernetes", "2026"]
```

실제로 받은 응답은 이것이었다.

```
Here are the keywords I extracted from the text:
["Docker", "Kubernetes", "2026"]
These represent the main technical entities mentioned.
```

`objectMapper.readValue(response, String[].class)` — 즉시 파싱 실패.

해결 방법을 두 가지로 구성했다.

**해결 1: 프롬프트 영문화**
```java
@SystemMessage("Return ONLY a JSON array. No explanations, no markdown, no prefix text.")
```
LLM은 한국어 지시보다 영문 지시의 이행률이 높다.

**해결 2: 정규식 fallback**
```java
private static final Pattern JSON_ARRAY_PATTERN =
    Pattern.compile("\\[.*?\\]", Pattern.DOTALL);

private List<String> parseKeywords(String response) {
    Matcher matcher = JSON_ARRAY_PATTERN.matcher(response);
    if (matcher.find()) {
        return objectMapper.readValue(matcher.group(), String[].class);
    }
    return Collections.emptyList(); // 파싱 실패해도 시스템은 멈추지 않는다
}
```

**핵심 설계 원칙**: LLM 응답 파싱은 항상 실패할 수 있다고 가정하고 만든다. 실패해도 파이프라인이 계속 동작해야 한다.

---

### 문제 2. N+1 쿼리 — 교과서에서만 봤는데 직접 겪었다

키워드 10개를 추출하고 각 키워드마다 노드를 조회했다. `spring.jpa.show-sql=true`를 켜놓고 로그를 보니 SELECT가 수십 번 나가고 있었다.

```java
// 나쁜 코드 — N+1 발생
for (String keyword : keywords) {
    Optional<Node> node = nodeRepository.findByName(keyword); // N번
    node.ifPresent(n -> {
        n.getEdges(); // LAZY → 또 N번
    });
}
```

LLM이 이미 6초를 쓰는 파이프라인에서 DB도 수십 번 왕복하고 있었다.

**해결: Batch 조회로 전환**

```java
// 좋은 코드 — 1회 IN 절 조회
List<Node> nodes = nodeRepository.findByNameIn(keywords);
// → SELECT * FROM nodes WHERE name IN ('Docker', 'Kubernetes', ...)

// JOIN FETCH — 엣지 + 양쪽 노드를 단일 쿼리로
@Query("SELECT e FROM Edge e " +
       "JOIN FETCH e.source s " +
       "JOIN FETCH e.target t " +
       "WHERE s.name IN :names OR t.name IN :names")
List<Edge> findEdgesByNodeNamesIn(@Param("names") List<String> names);
```

결과: **DB IO 오버헤드 약 80% 절감** (show-sql 로그 기준)

---

### 문제 3. LLM 추론 6초 — API가 6초씩 블로킹된다

`POST /api/graph/extract`를 호출하면 LLM 추론이 끝날 때까지 연결이 유지됐다. API 응답 시간 기준 200ms가 한계인데 6초짜리 LLM을 동기로 연결해놨던 것이다.

**해결: RabbitMQ 비동기 파이프라인**

```
[요청]                            [백그라운드]
  │                                    │
  ├─→ RabbitMQ 발행 (즉시)             ├─→ ExtractionListener
  │                                    │     └─ LLM 추론 (6초)
  └─→ 202 Accepted 반환 (즉시)         │     └─ DB 저장
                                       └─→ 완료
```

API는 202ms에 응답하고, LLM 처리는 백그라운드에서 진행된다.

추가로, LLM이 일시적으로 오류를 내면 어떻게 될까? 처음엔 로그만 찍고 넘어갔다. 그러면 그 시점에 처리 중이던 지식은 영구 유실된다.

**DLQ(Dead Letter Queue)로 데이터 유실 방지**

```
처리 실패 → 재시도 1회 (2초 대기)
         → 재시도 2회 (4초 대기)
         → 재시도 3회 (8초 대기)
         → DLQ 격리 (데이터 보존, 나중에 수동 재처리)
```

```properties
spring.rabbitmq.listener.simple.retry.max-attempts=3
spring.rabbitmq.listener.simple.retry.initial-interval=2000ms
spring.rabbitmq.listener.simple.retry.multiplier=2.0
spring.rabbitmq.listener.simple.default-requeue-rejected=false
```

`default-requeue-rejected=false` — 이 설정이 없으면 실패한 메시지가 큐에 계속 다시 들어가 무한 루프가 발생한다.

**결과: 데이터 유실 0, API 응답 202ms**

---

### 문제 4. pgvector는 JPA로 쓸 수 없다

pgvector의 코사인 거리 연산자 `<=>`는 표준 SQL이 아니다. JPQL로 표현할 방법이 없다.

```java
// JPA로는 불가능한 쿼리
// embedding <=> ?::vector 를 JPQL로 쓸 수 없다

// JdbcTemplate + native SQL 필수
String sql = """
    SELECT content, 1 - (embedding <=> ?::vector) as score
    FROM vector_store
    ORDER BY score DESC
    LIMIT 5
    """;
```

`<=>` 는 코사인 거리(0 = 완전 동일). `1 - 거리 = 유사도`로 변환해서 높은 유사도 순으로 정렬한다.

---

## 테스트 전략 — 인프라 없이 50개 통과

LLM, 벡터 DB, RabbitMQ 모두 느리거나 비용이 드는 외부 의존성이다. 이 모든 것을 Mock 처리해서 인프라 없이 비즈니스 로직만 검증했다.

**ChatLanguageModel Mock**
```java
@Mock ChatLanguageModel chatLanguageModel;

// LLM이 부연 설명을 붙이는 실제 발생 버그 재현
when(chatLanguageModel.generate(anyString()))
    .thenReturn("Here are the keywords:\n[\"Docker\", \"2026\"]\nThese represent...");

// 정규식 fallback이 ["Docker", "2026"]을 올바르게 추출하는지 검증
```

**pgvector SQL 검증**
```java
// H2는 <=> 연산자를 지원하지 않음 → JdbcTemplate Mock 사용
ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
verify(jdbcTemplate).query(sqlCaptor.capture(), any(RowMapper.class), any());
assertThat(sqlCaptor.getValue()).contains("<=>");
```

**파이프라인 순서 검증**
```java
InOrder inOrder = inOrder(knowledgeExtractor, graphService, vectorService);
inOrder.verify(knowledgeExtractor).extract(text);
inOrder.verify(graphService).saveGraph(any(GraphData.class));
inOrder.verify(vectorService).embedAndSave(text);
// 지식 추출 → 그래프 저장 → 벡터 저장 순서가 보장되는지 검증
```

**JOIN FETCH 검증 (LazyInitializationException 강제 유발)**
```java
entityManager.clear(); // 1차 캐시 초기화 → LAZY 로딩 강제 비활성화

List<Edge> edges = edgeRepository.findEdgesByNodeNamesIn(List.of("Docker"));

// JOIN FETCH가 없으면 이 시점에서 LazyInitializationException 발생
// JOIN FETCH가 있으면 이미 로딩됐으므로 정상
assertThat(edges.get(0).getSource().getName()).isEqualTo("Docker");
```

---

## 결과

| 항목 | 수치 |
|------|------|
| N+1 쿼리 해결 | DB IO 약 **80% 절감** (show-sql 기준) |
| 비동기 분리 | API 응답 **202ms** (LLM 6초 백그라운드) |
| 데이터 유실 | **0** (DLQ 3회 재시도 + 격리) |
| 단위 테스트 | **50개** (인프라 없이 실행) |
| LLM | Qwen 2.5 14B (로컬, 클라우드 비용 0) |
| 임베딩 | mxbai-embed-large (1024차원) |

---

## 배운 것

**LLM은 코드처럼 다루면 안 된다**

API 호출이라도 같은 입력에 같은 출력이 보장되지 않는다. 파싱은 항상 실패를 가정하고, 실패해도 시스템이 멈추지 않게 설계해야 한다.

**N+1은 ORM을 쓰면 자연히 생긴다**

조심한다고 생기지 않는 게 아니었다. `show-sql=true`로 실제 쿼리를 확인하는 습관이 없으면 발견조차 못한다. ORM의 편의성은 SQL 비용을 숨긴다.

**비동기 처리의 필요성은 숫자로 체감해야 납득된다**

6초짜리 LLM을 동기로 연결해서 직접 6초를 기다려보고 나서야 메시지 큐의 필요성을 체감했다.

**테스트를 나중에 쓰면 더 힘들다**

서비스 코드를 다 짠 후 테스트를 작성하니 Mock 구성이 복잡했다. `MindGraphService`는 6개 의존성을 주입받는다. 구현할 때 "이걸 어떻게 테스트하지?"를 먼저 생각했다면 의존성이 더 단순해졌을 것이다.

---

## 기술 스택

| 영역 | 선택 | 이유 |
|------|------|------|
| 언어/프레임워크 | Java 17, Spring Boot 3.2 | 안정적인 생태계, 풍부한 LLM 통합 라이브러리 |
| LLM 연동 | LangChain4j 0.31 @AiService | 구현체 없이 인터페이스만 선언하면 LLM 호출 자동 생성 |
| 벡터 저장소 | PostgreSQL + pgvector | 별도 벡터 DB 없이 기존 DB에서 처리, 운영 복잡도 감소 |
| 그래프 저장소 | PostgreSQL (nodes/edges 테이블) | PoC 단계 — Neo4j는 2-hop 탐색 검증 후 도입 결정 예정 |
| 메시지 큐 | RabbitMQ | Kafka는 이 규모에서 과도한 설계, 재시도+DLQ만 필요 |
| LLM (Chat) | Qwen 2.5 14B (Ollama) | 로컬 서빙, 16GB VRAM에서 안정적 동작 |
| LLM (Embedding) | mxbai-embed-large (1024차원) | 한국어 포함 다국어 지원 |
| 테스트 | JUnit 5, Mockito, @DataJpaTest + H2 | 인프라 없이 전체 서비스 로직 검증 |
