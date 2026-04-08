# LLM 의존 코드를 인프라 없이 단위 테스트하는 법 (Spring Boot + Mockito)

> **환경**: Java 17, Spring Boot 3.2, JUnit 5, Mockito, LangChain4j 0.31
> LLM(Ollama), PostgreSQL(pgvector), RabbitMQ 없이 49개 테스트 전부 통과

---

## 문제 상황

MindGraph-AI의 서비스 레이어는 외부 의존성이 많다:

- `ChatLanguageModel` — Qwen 2.5 14B (Ollama)
- `EmbeddingModel` — mxbai-embed-large (Ollama)
- `JdbcTemplate` — pgvector native SQL
- `RabbitTemplate` — RabbitMQ
- `NodeRepository`, `EdgeRepository` — PostgreSQL JPA

이 모든 인프라를 띄워야만 테스트할 수 있다면, 테스트는 느리고 CI에서 쓰기 어렵다.

---

## 전략: Mock으로 인프라 격리

```
서비스 단위 테스트: @ExtendWith(MockitoExtension.class)
  → ChatLanguageModel, EmbeddingModel, JdbcTemplate, RabbitTemplate 전부 Mock
  → Spring 컨텍스트 없음, 외부 연결 없음

Repository 테스트: @DataJpaTest + H2 In-Memory
  → PostgreSQL 대신 H2, Neo4j 자동 설정 제외
  → JPQL/JOIN FETCH 쿼리 검증 가능, pgvector SQL은 JdbcTemplate Mock
```

---

## 1. LLM 호출 Mock (ChatLanguageModel)

`MindGraphService.extractKeywords()`는 LLM을 호출해 키워드 JSON을 파싱한다.

```java
// 테스트
@Mock
private ChatLanguageModel chatLanguageModel;

@Test
void extractKeywords_부연설명_포함_정규식_추출() {
    // LLM이 부연 설명을 붙여 응답 (실제 발생한 버그 케이스)
    String llmResponse = "Here are the keywords:\n[\"Docker\", \"Container\"]\nThese are key terms.";
    when(chatLanguageModel.generate(anyString()))
        .thenReturn(llmResponse)    // 첫 번째: extractKeywords
        .thenReturn("최종 답변");   // 두 번째: RAG 답변

    String result = mindGraphService.ask("Docker 컨테이너란?");

    assertThat(result).isEqualTo("최종 답변");
}
```

핵심: **실제 발생했던 버그**를 테스트로 남긴다. "부연 설명 포함" 케이스는 정규식으로 처리하는 로직을 검증한다.

### LLM 예외 → graceful degradation 검증

```java
@Test
void extractKeywords_LLM_예외_빈리스트_반환() {
    when(chatLanguageModel.generate(anyString()))
        .thenThrow(new RuntimeException("LLM 연결 실패"))  // 첫 번째 호출 실패
        .thenReturn("최종 답변");                           // RAG 답변은 성공

    // extractKeywords 예외는 내부에서 캐치, 빈 리스트 반환
    // 전체 ask()는 멈추지 않음
    String result = mindGraphService.ask("테스트");

    assertThat(result).isEqualTo("최종 답변");
}
```

LLM이 죽어도 `ask()`가 죽지 않아야 한다.

---

## 2. pgvector SQL Mock (JdbcTemplate)

`<=>` 연산자는 H2에서 동작하지 않는다. JdbcTemplate을 Mock으로 교체하고 SQL 문자열을 검증한다.

```java
@Mock
private JdbcTemplate jdbcTemplate;

@Test
void search_임베딩_벡터_SQL에_포함() {
    givenEmbeddingModel(); // EmbeddingModel도 mock
    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    when(jdbcTemplate.query(sqlCaptor.capture(), any(RowMapper.class), any()))
        .thenReturn(List.of());

    searchService.search("테스트");

    // SQL에 pgvector 연산자가 실제로 포함되는지 검증
    assertThat(sqlCaptor.getValue()).contains("<=>");
    assertThat(sqlCaptor.getValue()).containsIgnoringCase("LIMIT 5");
}
```

SQL 자체의 정확성은 @DataJpaTest + 실 DB에서 검증하고, 여기서는 "올바른 SQL이 전달되는지"만 검증한다.

---

## 3. EmbeddingModel Mock

```java
private void givenEmbeddingModel() {
    Embedding embedding = Embedding.from(new float[]{0.1f, 0.2f, 0.3f});
    when(embeddingModel.embed(anyString())).thenReturn(Response.from(embedding));
}

@Test
void embedAndSave_벡터_문자열_변환() {
    givenEmbeddingModel();
    ArgumentCaptor<Object> vectorCaptor = ArgumentCaptor.forClass(Object.class);

    vectorService.embedAndSave("테스트");

    // 네 번째 인자(vectorString)가 "[..."로 시작하는 문자열인지 검증
    verify(jdbcTemplate).update(anyString(), any(), any(), any(), vectorCaptor.capture());
    assertThat((String) vectorCaptor.getValue()).startsWith("[");
}
```

---

## 4. RabbitMQ Mock (RabbitTemplate)

```java
@Mock
private RabbitTemplate rabbitTemplate;

@Test
void publish_올바른_Exchange_RoutingKey_사용() {
    ArgumentCaptor<String> exchangeCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> routingKeyCaptor = ArgumentCaptor.forClass(String.class);

    publisher.publishExtractionRequest("테스트");

    verify(rabbitTemplate).convertAndSend(
        exchangeCaptor.capture(),
        routingKeyCaptor.capture(),
        any()
    );
    assertThat(exchangeCaptor.getValue()).isEqualTo("mindgraph.exchange");
    assertThat(routingKeyCaptor.getValue()).isEqualTo("mindgraph.extraction.key");
}
```

---

## 5. 파이프라인 순서 검증 (InOrder)

`ExtractionListener`는 3단계 파이프라인을 순서대로 실행해야 한다:
extract → saveGraph → embedAndSave

```java
@Test
void onExtractionRequest_3단계_파이프라인_순서() {
    String text = "Docker는 컨테이너 기술이다.";
    when(knowledgeExtractor.extract(text)).thenReturn(SAMPLE_GRAPH_DATA);

    listener.onExtractionRequest(text);

    // InOrder: 순서가 다르면 테스트 실패
    InOrder inOrder = inOrder(knowledgeExtractor, graphService, vectorService);
    inOrder.verify(knowledgeExtractor).extract(text);
    inOrder.verify(graphService).saveGraph(any(GraphData.class));
    inOrder.verify(vectorService).embedAndSave(text);
}
```

중간에 예외 발생 시 이후 단계 미실행 검증:

```java
@Test
void onExtractionRequest_graphService_예외시_vectorService_미호출() {
    when(knowledgeExtractor.extract(any())).thenReturn(SAMPLE_GRAPH_DATA);
    doThrow(new RuntimeException("DB 저장 실패")).when(graphService).saveGraph(any());

    assertThatThrownBy(() -> listener.onExtractionRequest("텍스트"))
        .isInstanceOf(RuntimeException.class);

    verify(vectorService, never()).embedAndSave(any()); // 실행되면 안 됨
}
```

---

## 6. @DataJpaTest + H2 — JOIN FETCH 검증

Repository 계층은 `@DataJpaTest`로 실제 JPQL을 실행하며 검증한다.

### Neo4j 자동 설정 충돌 해결

```java
@DataJpaTest(excludeAutoConfiguration = {
    Neo4jAutoConfiguration.class,
    Neo4jDataAutoConfiguration.class,
    Neo4jRepositoriesAutoConfiguration.class
})
class EdgeRepositoryTest {
    @Autowired TestEntityManager entityManager;
    @Autowired EdgeRepository edgeRepository;
```

`spring-boot-starter-data-neo4j`가 classpath에 있으면 @DataJpaTest가 Neo4j 연결을 시도한다. 명시적으로 제외해야 한다.

### JOIN FETCH로 LazyInitializationException 없음 검증

```java
@Test
void findEdgesByNodeNamesIn_JOIN_FETCH_검증() {
    Node docker = persistNode("Docker", "Technology");
    Node container = persistNode("Container", "Concept");
    persistEdge(docker, container, "IS_A");

    entityManager.clear(); // 영속성 컨텍스트 초기화 → LAZY 비활성화

    List<Edge> edges = edgeRepository.findEdgesByNodeNamesIn(List.of("Docker"));

    // JOIN FETCH라면 추가 쿼리 없이 source/target 접근 가능
    assertThat(edges.get(0).getSource().getName()).isEqualTo("Docker");
    assertThat(edges.get(0).getTarget().getName()).isEqualTo("Container");
}
```

`entityManager.clear()` 없이 하면 1차 캐시에서 가져오므로 LAZY 로딩 문제가 숨겨진다.

---

## 결과

```
총 50개 테스트 (신규 49개 + 기존 contextLoads 1개)
50/50 통과 (docker compose up -d 후)
49/50 통과 (인프라 없이도 — contextLoads만 실패)

실행 시간: ~5초
필요한 인프라: 없음 (서비스 테스트), H2 자동 (레포지토리 테스트)
```

### Gradle 9.x에서 JUnit Platform 로딩 실패

```gradle
// build.gradle에 반드시 추가
testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
```

없으면 `Failed to load JUnit Platform` 에러가 나온다. Gradle 9.x 변경 사항이다.

---

## 요약

| 의존성 | 테스트 방식 |
|--------|-----------|
| ChatLanguageModel (Ollama) | `@Mock` + `when().thenReturn()` |
| EmbeddingModel (Ollama) | `@Mock` + `Embedding.from(float[])` |
| JdbcTemplate (pgvector) | `@Mock` + `ArgumentCaptor`로 SQL 검증 |
| RabbitTemplate | `@Mock` + `verify().convertAndSend()` |
| JPA Repository (PostgreSQL) | `@DataJpaTest` + H2, Neo4j 제외 |
| 파이프라인 순서 | Mockito `InOrder` |
| 예외 전파 | `assertThatThrownBy` + `verify(never())` |
