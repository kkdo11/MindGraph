# JPA N+1을 직접 겪고 고친 과정 + RabbitMQ DLQ로 데이터 유실 없애기

> **환경**: Java 17, Spring Boot 3.2, LangChain4j 0.31, PostgreSQL + pgvector, RabbitMQ

개인 지식 그래프 시스템(MindGraph-AI)을 운영하면서 두 가지 문제를 만났다.
하나는 성능, 하나는 안정성이다.

---

## 문제 1: N+1 쿼리 — LLM이 6초인데 DB도 수십 번 조회?

### 어떻게 발생했나

질문 "Docker 컨테이너 배포 방법은?" 처리 시 흐름:

1. LLM에서 키워드 추출: `["Docker", "Container", "Kubernetes", "Deploy", "Image"]`
2. 각 키워드로 Node 조회
3. 각 Node의 관련 Edge 조회

코드로 보면 이랬다:

```java
// 문제 코드 (Phase 1 초기)
for (String keyword : keywords) {
    Optional<Node> node = nodeRepository.findByName(keyword); // ← 여기서 SELECT 1번
    node.ifPresent(n -> {
        List<Edge> edges = edgeRepository.findBySource(n); // ← 또 SELECT 1번
        // edge.getTarget().getName() → LAZY → 또 SELECT 1번
    });
}
```

`spring.jpa.show-sql=true` 켜서 로그 보니:

```
Hibernate: select * from nodes where name=?     ← 1
Hibernate: select * from nodes where name=?     ← 2
Hibernate: select * from nodes where name=?     ← 3
Hibernate: select * from nodes where name=?     ← 4
Hibernate: select * from nodes where name=?     ← 5
Hibernate: select * from edges where source_id=?
Hibernate: select * from nodes where id=?       ← LAZY: target 노드
Hibernate: select * from edges where source_id=?
Hibernate: select * from nodes where id=?       ← LAZY: target 노드
...
```

키워드 5개에 쿼리가 20번 넘게 나갔다.

LLM이 이미 6초를 쓰는 파이프라인에서 DB가 20번 왕복하면 전체 지연이 의미 있게 늘어난다.

### 해결 1: Batch 조회 (findByNameIn)

```java
// NodeRepository에 추가
List<Node> findByNameIn(Collection<String> names);
// → SELECT * FROM nodes WHERE name IN ('Docker', 'Container', 'Kubernetes', ...)
// N번 쿼리 → 1번 쿼리
```

```java
// 사용 코드
List<Node> nodes = nodeRepository.findByNameIn(entityKeywords); // 한 번만 조회
```

### 해결 2: JOIN FETCH (LazyInitializationException 방지)

Edge를 가져올 때 source와 target Node도 함께 로딩해야 한다.
그렇지 않으면 트랜잭션 밖에서 `edge.getSource().getName()` 호출 시 `LazyInitializationException`.

```java
// EdgeRepository
@Query("SELECT e FROM Edge e " +
       "JOIN FETCH e.source s " +
       "JOIN FETCH e.target t " +
       "WHERE s.name IN :names OR t.name IN :names")
List<Edge> findEdgesByNodeNamesIn(@Param("names") List<String> names);
```

하나의 쿼리로 Edge + 양쪽 Node를 모두 가져온다.

```
변경 전: 노드 N번 조회 + 엣지 N번 조회 + LAZY 로딩 N번
변경 후: 노드 1번 (findByNameIn) + 엣지 1번 (JOIN FETCH)
결과: DB IO 약 80% 절감
```

### 테스트에서 JOIN FETCH 검증

```java
@Test
void findEdgesByNodeNamesIn_JOIN_FETCH_검증() {
    // ... 데이터 저장 후
    entityManager.clear(); // 영속성 컨텍스트 초기화 → LAZY 강제 비활성화

    List<Edge> edges = edgeRepository.findEdgesByNodeNamesIn(List.of("Docker"));

    // JOIN FETCH라면 이 시점에서 source/target에 접근해도 추가 쿼리 없음
    assertThat(edges.get(0).getSource().getName()).isEqualTo("Docker"); // 통과
}
```

---

## 문제 2: LLM 오류 시 메시지 유실

### 어떻게 발생했나

초기 구조는 단순했다:

```java
@RabbitListener(queues = QUEUE_NAME)
public void onExtractionRequest(String text) {
    GraphData data = knowledgeExtractor.extract(text); // LLM 호출
    graphService.saveGraph(data);
    vectorService.embedAndSave(text);
}
```

Ollama가 일시적으로 느리거나 메모리 부족으로 오류를 내면?

```
LLM 호출 실패 → 예외 발생 → 메시지 처리 실패 → ???
```

기본 RabbitMQ 설정에서 `default-requeue-rejected=true`면 메시지가 큐 앞으로 돌아간다.
같은 메시지가 같은 오류를 무한 반복한다. 큐가 막힌다.

`default-requeue-rejected=false`로 바꾸면? 메시지가 그냥 사라진다.

어떤 경우든 **데이터가 유실**되거나 **시스템이 마비**된다.

### 해결: DLQ (Dead Letter Queue) 패턴

실패한 메시지를 버리지 않고 별도 큐에 격리한다.

**Step 1: 메인 큐에 DLX 설정**

```java
@Bean
public Queue queue() {
    return QueueBuilder.durable(QUEUE_NAME)
        .withArgument("x-dead-letter-exchange", DEAD_LETTER_EXCHANGE_NAME)
        .withArgument("x-dead-letter-routing-key", DEAD_LETTER_QUEUE_NAME)
        .build();
}
```

**Step 2: 지수 백오프 재시도**

```yaml
spring:
  rabbitmq:
    listener:
      simple:
        retry:
          enabled: true
          max-attempts: 3
          initial-interval: 2000ms   # 2초 후 재시도
          multiplier: 2.0            # 4초, 8초 (지수 증가)
        default-requeue-rejected: false   # 재시도 소진 → DLQ로
```

**Step 3: DLQ 설정**

```java
@Bean
public TopicExchange deadLetterExchange() {
    return new TopicExchange(DEAD_LETTER_EXCHANGE_NAME);
}

@Bean
public Queue deadLetterQueue() {
    return new Queue(DEAD_LETTER_QUEUE_NAME, true);
}
```

### 동작 흐름

```
메시지 도착
    │
    ▼
LLM 호출 실패 → 1차 재시도 (2초 후)
              → 2차 재시도 (4초 후)
              → 3차 재시도 (8초 후)
              → 모두 실패 → DLQ 격리 (메시지 보존)

일시적 오류: 재시도 중 성공 → 정상 처리
영구적 오류: DLQ에서 수동 확인 후 재처리
```

### 왜 지수 백오프인가?

일시적 LLM 오류 후 즉시 재시도하면 이미 부하가 걸린 LLM에 추가 부하가 간다.
2초→4초→8초로 점점 늘리면 LLM이 회복할 시간을 준다.
모든 소비자가 동시에 재시도하는 thundering herd 문제도 줄어든다.

---

## 추가: Temporal Context 분리 — 연도 키워드 처리

Phase 2에서 해결한 또 다른 문제.

### 문제

"2026년 Manus AI 설립" 텍스트에서 키워드 추출:

```
추출 결과: ["Manus", "AI", "2026"]
```

"2026"을 엔티티처럼 처리하면 nodes 테이블에 `{name: "2026", type: "Concept"}` 같은 의미 없는 노드가 생긴다.

"2023년 GDP는?" 검색 시 "2026년 GDP" 관련 데이터도 함께 노출된다.

### 해결: Entity vs Temporal 분리

```java
// 연도 패턴으로 분리
List<String> entityKeywords = keywords.stream()
    .filter(k -> !k.matches("\\d{4}"))  // 연도 제외
    .toList();
List<String> yearKeywords = keywords.stream()
    .filter(k -> k.matches("\\d{4}"))   // 연도만
    .toList();

// 엔티티 키워드로만 노드/엣지 검색
List<Node> nodes = nodeRepository.findByNameIn(entityKeywords);
List<Edge> edges = edgeRepository.findEdgesByNodeNamesIn(nodeNames);

// 결과 텍스트를 연도로 필터링 (메모리 상에서 처리, DB I/O 없음)
return edges.stream()
    .map(this::formatEdgeToKnowledge)
    .filter(info -> yearKeywords.isEmpty() || yearsMatch(info, yearKeywords))
    .collect(Collectors.joining("\n"));
```

"2024년 GDP"와 "2023년 GDP"가 완전히 다른 결과를 반환한다.

---

## 정리

| 문제 | 원인 | 해결 | 효과 |
|------|------|------|------|
| N+1 쿼리 | 반복 단일 조회 | `findByNameIn` + `JOIN FETCH` | DB IO 80% 절감 |
| LLM 오류 시 데이터 유실 | 재시도/격리 없음 | DLQ + 지수 백오프 재시도 | 데이터 유실 0 |
| 연도 키워드 오류 | Entity/Temporal 미분리 | 4자리 숫자 패턴으로 분리 | 시계열 검색 정확도 향상 |

모두 "개발할 때는 몰랐는데 실제로 써보고 나서" 발견한 문제들이다.
