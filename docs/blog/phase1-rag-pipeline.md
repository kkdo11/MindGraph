# 로컬 LLM으로 개인 지식 그래프를 만들었다 (Spring Boot + LangChain4j + pgvector)

> **환경**: Java 17, Spring Boot 3.2, LangChain4j 0.31, Qwen 2.5 14B (Ollama, RTX 4080 Super)
> **GitHub**: https://github.com/kkdo11/mindgraph-ai

---

## 왜 만들었나

업무 메모, 기술 문서, 읽은 글들이 쌓이면서 문제가 생겼다. 분명히 어디선가 봤는데 찾을 수가 없다. 검색하면 키워드가 없으면 못 찾고, 관련 내용이 분산되어 있어서 맥락을 이어가기가 힘들다.

단순히 저장하는 게 아니라 **지식 사이의 관계를 구조화**하면 어떨까?

"Docker는 컨테이너 기술이다" → "컨테이너는 격리된 프로세스 환경이다" → "Kubernetes는 컨테이너를 오케스트레이션한다"

이 연결을 자동으로 만들어주는 시스템을 직접 설계했다.

---

## 전체 아키텍처

```
[데이터 입력]
  API (POST /api/graph/extract)
  FileWatcher (watched-files/ 디렉토리)
         │
         ▼
  RabbitMQ (비동기 처리)
         │
         ▼
  ExtractionListener
    ① KnowledgeExtractor.extract()   ← Qwen 2.5 14B: 지식 그래프 추출
    ② GraphService.saveGraph()        ← PostgreSQL: 노드/엣지 저장
    ③ VectorService.embedAndSave()    ← mxbai-embed-large: 원문 임베딩 저장

[질문 응답]
  POST /api/graph/ask { question }
    병렬 ① SearchService.search()     ← pgvector 코사인 유사도 검색
    병렬 ② MindGraphService.searchGraph() ← 키워드 → 그래프 탐색
    컨텍스트 병합 → Qwen 2.5 14B → 한국어 답변
```

---

## 핵심 구현: LangChain4j @AiService로 구조화된 JSON 추출

가장 중요한 부분이다. 자유 형식 텍스트를 LLM에 보내서 지식 그래프 JSON을 받아야 한다.

### 인터페이스만 선언하면 된다

```java
@AiService
public interface KnowledgeExtractor {

    @SystemMessage("""
        You are a knowledge graph extraction expert.
        Extract key entities (nodes) and their relationships (edges) from the input text.
        Entity types: Person, Project, Technology, Concept.
        Respond ONLY in the specified JSON format.
        """)
    GraphData extract(@UserMessage String text);
}
```

LangChain4j가 런타임에 이 인터페이스의 프록시를 생성한다. `extract()` 호출 시 자동으로 Ollama API를 호출하고 응답을 `GraphData` record로 역직렬화한다.

```java
public record GraphData(List<NodeDTO> nodes, List<EdgeDTO> edges) {
    public record NodeDTO(String name, String type, String description) {}
    public record EdgeDTO(String sourceName, String targetName, String relation) {}
}
```

### 문제: LLM이 부연 설명을 붙인다

처음에 시스템 메시지를 한국어로 썼다.

```
"당신은 전문가입니다. 반드시 JSON 형식으로만 응답하세요."
```

LLM이 지시를 잘 따르지 않았다.

```
기대 응답:
{"nodes": [...], "edges": [...]}

실제 응답:
"네, 알겠습니다. 주어진 텍스트를 분석하여 지식 그래프를 추출하겠습니다.
{"nodes": [...], "edges": [...]}
위와 같이 추출했습니다."
```

JSON 파싱이 즉시 실패한다.

### 해결: 영문화 + 정규식 fallback

```java
// 1. 시스템 메시지 영문화로 지시 이행률 향상
@SystemMessage("""
    You are a knowledge graph extraction expert.
    IMPORTANT: Return ONLY valid JSON. No explanations, no markdown, no extra text.
    """)

// 2. 정규식 fallback (부연 설명이 있어도 JSON 배열만 추출)
private static final Pattern JSON_ARRAY_PATTERN =
    Pattern.compile("\\[.*?\\]", Pattern.DOTALL);

private List<String> extractKeywords(String question) {
    try {
        String response = chatLanguageModel.generate(prompt.text());
        Matcher matcher = JSON_ARRAY_PATTERN.matcher(response);
        if (matcher.find()) {
            return Arrays.asList(objectMapper.readValue(matcher.group(), String[].class));
        }
        return Collections.emptyList(); // graceful degradation
    } catch (Exception e) {
        log.error("Keyword extraction failed: {}", e.getMessage());
        return Collections.emptyList();
    }
}
```

**교훈**: LLM 응답은 항상 실패를 가정하고 방어 코드를 준비해야 한다.

---

## 벡터 검색: pgvector + JdbcTemplate

pgvector의 `<=>` 연산자는 Spring Data JPA가 지원하지 않는다. JdbcTemplate으로 native SQL을 직접 실행한다.

```java
// 저장 (VectorService)
jdbcTemplate.update("""
    INSERT INTO vector_store (id, content, metadata, embedding)
    VALUES (?, ?, ?::jsonb, ?::vector)
""", UUID.randomUUID(), text, "{}", vectorString);

// 검색 (SearchService)
// <=> = 코사인 거리, 1 - 거리 = 유사도
return jdbcTemplate.query("""
    SELECT content, 1 - (embedding <=> ?::vector) as score
    FROM vector_store
    ORDER BY score DESC
    LIMIT 5
""", (rs, rowNum) -> new SearchResult(
    rs.getString("content"),
    rs.getDouble("score")
), vectorString);
```

pgvector는 PostgreSQL 내장 확장이다. Pinecone 같은 별도 벡터 DB 없이 기존 PostgreSQL에 추가할 수 있어서 운영 복잡도가 없다.

---

## 비동기 처리: RabbitMQ

LLM 추론이 평균 6초다. 동기로 처리하면 API 응답도 6초다. RabbitMQ로 분리했다.

```java
// Controller: 즉시 202 반환
@PostMapping("/extract")
public ResponseEntity<String> extract(@RequestBody String text) {
    publisher.publishExtractionRequest(text);
    return ResponseEntity.accepted().body("처리 중");
}

// Listener: 백그라운드에서 실제 처리
@RabbitListener(queues = RabbitMQConfig.QUEUE_NAME)
public void onExtractionRequest(String text) {
    GraphData graphData = knowledgeExtractor.extract(text); // 6초
    graphService.saveGraph(graphData);
    vectorService.embedAndSave(text);
}
```

사용자는 6초를 기다리지 않는다.

---

## 병렬 검색: CompletableFuture

질문 응답 시 벡터 검색과 그래프 검색은 완전히 독립적이다.

```java
// 두 검색을 동시에 시작
CompletableFuture<String> vectorFuture = CompletableFuture.supplyAsync(() ->
    searchService.search(question).stream()
        .map(SearchResult::content)
        .collect(Collectors.joining("\n"))
);
CompletableFuture<String> graphFuture = CompletableFuture.supplyAsync(() ->
    searchGraph(question)
);

// 둘 다 끝날 때까지 대기
String vectorContext = vectorFuture.join();
String graphContext = graphFuture.join();
```

순차 실행 시 `시간A + 시간B`, 병렬 실행 시 `max(시간A, 시간B)`.

---

## 결과

- 텍스트 입력 → LLM이 노드/엣지 추출 → PostgreSQL 저장
- 동일 텍스트의 임베딩 → pgvector 저장
- "Docker 컨테이너란?" 질문 → 벡터+그래프 컨텍스트 병합 → LLM 한국어 답변
- 전체 파이프라인이 로컬에서 (Qwen 2.5 14B, RTX 4080 Super) 동작

---

## 다음 단계

- LLM-OPT 프록시 연결 (반복 질문 캐싱 → 6초 → 25ms)
- Neo4j 하이브리드 (2-hop 그래프 탐색)
- 에이전트 확장 (자율 정보 검색)

소스 코드: https://github.com/kkdo11/mindgraph-ai
