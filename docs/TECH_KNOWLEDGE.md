# 기술 지식 정리

> MindGraph-AI를 구현하면서 직접 써보고 문제를 해결하면서 쌓인 지식.
> "들어본 적 있다"가 아니라 "직접 구현하고 트러블슈팅했다" 수준의 것들만 기록.

---

## 1. LangChain4j @AiService

### 1-1. 동작 원리

```java
@AiService
public interface KnowledgeExtractor {
    @SystemMessage("You are a knowledge graph expert. Return ONLY valid JSON.")
    GraphData extract(@UserMessage String text);
}
```

런타임에 LangChain4j가 이 인터페이스의 프록시를 생성한다.
`extract()` 호출 → @SystemMessage + @UserMessage → Ollama API 호출 → JSON 응답 → GraphData 역직렬화.

**핵심**: 구현체 없이 인터페이스만 선언하면 LLM 호출 코드가 자동 생성됨.

### 1-2. LLM 응답 비정형성 대응 (실제 발생 버그)

```java
// 문제: LLM이 JSON 외 부연 설명 추가
// "Here are the keywords: [\"Docker\", \"2026\"]. These represent..."

// 해결 1: 시스템 메시지 영문화 (지시 이행률 향상)
// "Return ONLY a JSON array. No explanations, no markdown."

// 해결 2: 정규식 fallback
private static final Pattern JSON_ARRAY_PATTERN =
    Pattern.compile("\\[.*?\\]", Pattern.DOTALL);

Matcher matcher = JSON_ARRAY_PATTERN.matcher(response);
if (matcher.find()) {
    return Arrays.asList(objectMapper.readValue(matcher.group(), String[].class));
}
return Collections.emptyList(); // graceful degradation
```

**교훈**: LLM 응답 파싱은 항상 실패를 가정. 실패해도 시스템이 멈추지 않도록.

### 1-3. 단위 테스트 전략

```java
// ChatLanguageModel을 Mock → @AiService 프록시가 mock 응답 사용
@Mock ChatLanguageModel chatLanguageModel;

when(chatLanguageModel.generate(anyString()))
    .thenReturn("[\"Docker\", \"2026\"]")   // 첫 번째 호출
    .thenReturn("최종 답변");               // 두 번째 호출
```

### 1-4. 프롬프트 언어 선택

| 언어 | 지시 이행률 | 선택 여부 |
|------|------------|----------|
| 한국어 | 낮음 (부연 설명 빈번) | ❌ |
| 영어 | 높음 (JSON only 준수) | ✅ |

Qwen 2.5 14B에서 영문 시스템 메시지로 전환 후 파싱 성공률 크게 향상됨.

---

## 2. pgvector (PostgreSQL 벡터 확장)

### 2-1. JPA 대신 JdbcTemplate을 쓰는 이유

pgvector의 `<=>` (cosine distance) 연산자는 표준 SQL이 아님.
JPQL이나 Spring Data JPA 쿼리 메서드로 표현 불가 → **JdbcTemplate + native SQL 필수**.

```java
// 저장
jdbcTemplate.update("""
    INSERT INTO vector_store (id, content, metadata, embedding)
    VALUES (?, ?, ?::jsonb, ?::vector)
""", UUID.randomUUID(), text, "{}", vectorString);

// 검색 (cosine distance → similarity)
jdbcTemplate.query("""
    SELECT content, 1 - (embedding <=> ?::vector) as score
    FROM vector_store ORDER BY score DESC LIMIT 5
""", rowMapper, vectorString);
```

### 2-2. 코사인 거리 vs 유사도

```
pgvector <=> 연산자 = 코사인 거리 (0 = 완전 동일, 2 = 완전 반대)
유사도 = 1 - 코사인 거리 (1 = 동일, -1 = 반대)
ORDER BY score DESC = 유사도 높은 것부터 정렬
```

### 2-3. 벡터 문자열 변환

```java
Embedding embedding = embeddingModel.embed(text).content();
String vectorString = embedding.vectorAsList().toString();
// → "[0.1234, -0.5678, 0.9012, ...]"
// pgvector가 ?::vector 캐스팅으로 파싱
```

### 2-4. @DataJpaTest에서 pgvector 테스트 불가 → Mock 전략

H2는 pgvector extension 미지원. `<=>` 연산자 실행 시 오류.
→ JdbcTemplate Mock + ArgumentCaptor로 SQL 문자열만 검증.

```java
ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
verify(jdbcTemplate).query(sqlCaptor.capture(), any(RowMapper.class), any());
assertThat(sqlCaptor.getValue()).contains("<=>");
```

---

## 3. JPA N+1 문제와 해결

### 3-1. 문제 발생 상황

```java
// 나쁜 코드: 키워드마다 DB 조회
for (String keyword : keywords) {
    Optional<Node> node = nodeRepository.findByName(keyword);  // N번 쿼리
    node.ifPresent(n -> {
        n.getEdges(); // LAZY → 또 N번 추가 쿼리
    });
}
```

### 3-2. Batch 조회 (findByNameIn)

```java
// IN 절 하나로 N개 노드 조회
List<Node> findByNameIn(Collection<String> names);
// → SELECT * FROM nodes WHERE name IN ('Docker', 'Kubernetes', ...)
```

### 3-3. JOIN FETCH (LazyInitializationException 방지)

```java
@Query("SELECT e FROM Edge e " +
       "JOIN FETCH e.source s " +
       "JOIN FETCH e.target t " +
       "WHERE s.name IN :names OR t.name IN :names")
List<Edge> findEdgesByNodeNamesIn(@Param("names") List<String> names);
```

LAZY 로딩이 기본이면 트랜잭션 밖 `edge.getSource().getName()` 호출 시 LazyInitializationException.
JOIN FETCH는 쿼리 시점에 연관 엔티티를 함께 로딩.

### 3-4. @DataJpaTest에서 JOIN FETCH 검증 방법

```java
entityManager.clear(); // 1차 캐시 초기화 → LAZY 로딩 강제 비활성화

List<Edge> edges = edgeRepository.findEdgesByNodeNamesIn(List.of("Docker"));

// JOIN FETCH라면 이 시점에서 source/target 접근해도 추가 쿼리 없음
assertThat(edges.get(0).getSource().getName()).isEqualTo("Docker"); // 통과
```

`entityManager.clear()` 없이 하면 1차 캐시에서 가져오므로 LAZY 문제가 숨겨짐.

---

## 4. RabbitMQ + Spring AMQP

### 4-1. Exchange → Queue 라우팅 구조

```
Producer → TopicExchange (mindgraph.exchange)
         → Binding (key: mindgraph.extraction.key)
         → Queue (mindgraph.extraction.queue)
         → Consumer (@RabbitListener)

실패 시:
Consumer → 재시도 3회 (2s→4s→8s)
         → Dead Letter Exchange → DLQ
```

### 4-2. DLQ 설정 핵심

```java
// 메인 큐에 DLX 설정
QueueBuilder.durable(QUEUE_NAME)
    .withArgument("x-dead-letter-exchange", DEAD_LETTER_EXCHANGE_NAME)
    .withArgument("x-dead-letter-routing-key", DEAD_LETTER_QUEUE_NAME)
    .build();

// 지수 백오프 재시도 (application.properties)
spring.rabbitmq.listener.simple.retry.enabled=true
spring.rabbitmq.listener.simple.retry.max-attempts=3
spring.rabbitmq.listener.simple.retry.initial-interval=2000ms
spring.rabbitmq.listener.simple.retry.multiplier=2.0
spring.rabbitmq.listener.simple.default-requeue-rejected=false  // 무한 재시도 방지
```

### 4-3. 테스트 — RabbitMQ 없이

```java
@Mock RabbitTemplate rabbitTemplate;
verify(rabbitTemplate).convertAndSend(EXCHANGE_NAME, ROUTING_KEY, text);
```

---

## 5. CompletableFuture 병렬 실행

### 5-1. 패턴

```java
// 두 작업 동시 시작
CompletableFuture<String> vectorFuture = CompletableFuture.supplyAsync(() ->
    searchService.search(question).stream().map(r -> r.content()).collect(joining("\n"))
);
CompletableFuture<String> graphFuture = CompletableFuture.supplyAsync(() ->
    searchGraph(question)
);

// 둘 다 완료 대기
String vectorContext = vectorFuture.join();
String graphContext = graphFuture.join();
```

### 5-2. 순차 vs 병렬

```
순차: vectorTime + graphTime
병렬: max(vectorTime, graphTime)

벡터/그래프 검색이 각각 100ms씩이면:
- 순차: 200ms
- 병렬: 100ms (2배 빠름)
```

### 5-3. 주의사항

`supplyAsync()`는 ForkJoinPool.commonPool() 기본 사용.
I/O 블로킹이 심한 작업(LLM 호출 등)은 별도 Executor 제공이 더 안전.
현재 구현은 DB 쿼리 위주라 큰 문제 없음.

---

## 6. Java WatchService (파일 감시)

```java
WatchService watchService = FileSystems.getDefault().newWatchService();
path.register(watchService, ENTRY_CREATE, ENTRY_MODIFY);

// 별도 스레드에서 이벤트 폴링
WatchKey key = watchService.take();  // blocking 대기
for (WatchEvent<?> event : key.pollEvents()) {
    Path filePath = (Path) event.context();
    publisher.publishExtractionRequest(Files.readString(dir.resolve(filePath)));
}
key.reset(); // 반드시 호출 (안 하면 다음 이벤트 못 받음)
```

Java 표준 라이브러리, 추가 의존성 없음.

---

## 7. Gradle 9.x + Spring Boot 3.2 테스트 설정

### 7-1. JUnit Platform Launcher 명시 필요

```gradle
testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
// 없으면: Failed to load JUnit Platform 오류
```

### 7-2. @DataJpaTest + Neo4j 충돌 해결

```java
@DataJpaTest(excludeAutoConfiguration = {
    Neo4jAutoConfiguration.class,
    Neo4jDataAutoConfiguration.class,
    Neo4jRepositoriesAutoConfiguration.class
})
```

`spring-boot-starter-data-neo4j`가 classpath에 있으면 @DataJpaTest가 Neo4j 연결 시도.
명시적 제외 필요.

---

## 8. LangChain4j Embedding 모델

### 8-1. EmbeddingModel Mock 방법

```java
@Mock EmbeddingModel embeddingModel;

Embedding embedding = Embedding.from(new float[]{0.1f, 0.2f, 0.3f});
when(embeddingModel.embed(anyString())).thenReturn(Response.from(embedding));
```

### 8-2. mxbai-embed-large 특징

- 1024차원 벡터 (all-MiniLM-L6-v2의 384차원보다 고밀도)
- 한국어 포함 다국어 지원
- pgvector 저장 시 `vector(1024)` 타입 지정 필요
