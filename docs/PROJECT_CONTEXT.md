# MindGraph-AI: 기술 컨텍스트 및 아키텍처

> LLM-OPT 연동, Neo4j 도입 등 확장 시 참조하는 기술 스펙 문서.

---

## 시스템 아키텍처

```
[데이터 수집 채널]
  REST API (POST /api/graph/extract)   ──┐
  FileWatcher (watched-files/ 감지)     ──┤ → RabbitMQ → ExtractionListener
  (Notion — 네트워크 이슈로 비활성화)  ──┘

[ExtractionListener 3단계 파이프라인]
  ① KnowledgeExtractor.extract(text)
       → Qwen 2.5 14B (LangChain4j @AiService)
       → GraphData { nodes[], edges[] }
  ② GraphService.saveGraph(graphData)
       → NodeRepository.findByNameAndType() / save()   (Find-or-Create)
       → EdgeRepository.existsBy...() / save()          (중복 방지)
       → PostgreSQL (nodes, edges 테이블)
  ③ VectorService.embedAndSave(text)
       → mxbai-embed-large (Ollama, 1024차원)
       → JdbcTemplate INSERT INTO vector_store (..., embedding::vector)

[질문 응답 파이프라인]
  POST /api/graph/ask { question }
       │
       ├── Task 1 (CompletableFuture): SearchService.search(question)
       │     → mxbai-embed-large 임베딩 → pgvector <=> cosine search → Top 5
       │
       ├── Task 2 (CompletableFuture): MindGraphService.searchGraph(question)
       │     → extractKeywords(): LLM → 정규식 파싱 → [entity, year] 분리
       │     → NodeRepository.findByNameIn(entityKeywords)       (Batch)
       │     → EdgeRepository.findEdgesByNodeNamesIn(nodeNames)  (JOIN FETCH)
       │     → yearKeywords로 메모리 필터링
       │
       └── 컨텍스트 병합 → RAG Prompt → Qwen 2.5 14B → 한국어 답변
```

---

## 데이터 모델

### PostgreSQL 테이블

```sql
-- 지식 노드 (엔티티)
CREATE TABLE nodes (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR NOT NULL,
    type        VARCHAR NOT NULL,    -- Person / Project / Technology / Concept
    description TEXT,
    UNIQUE (name, type)
);

-- 지식 관계 (엣지)
CREATE TABLE edges (
    id        BIGSERIAL PRIMARY KEY,
    source_id BIGINT REFERENCES nodes(id),
    target_id BIGINT REFERENCES nodes(id),
    relation  VARCHAR NOT NULL,
    UNIQUE (source_id, target_id, relation)
);

-- 벡터 저장소 (pgvector)
CREATE TABLE vector_store (
    id        UUID PRIMARY KEY,
    content   TEXT,
    metadata  JSONB,
    embedding vector(1024)          -- mxbai-embed-large 1024차원
);
CREATE INDEX ON vector_store USING ivfflat (embedding vector_cosine_ops);
```

### JPA 엔티티 설계 원칙

- `@NoArgsConstructor(access = AccessLevel.PROTECTED)` + `@Builder` 조합
- LAZY 로딩 기본 (`@ManyToOne(fetch = FetchType.LAZY)`)
- UniqueConstraint는 DB와 JPA 양쪽 모두 선언
- ID 생성: `@GeneratedValue(strategy = GenerationType.IDENTITY)`

---

## LLM 호출 경로 및 횟수

**하나의 `/api/graph/ask` 요청에 LLM 최대 3회 호출:**

| # | 위치 | 모델 | 용도 | LLM-OPT 캐싱 대상 |
|---|------|------|------|-------------------|
| ① | SearchService.search() | mxbai-embed-large | 질문 임베딩 | ❌ 직접 호출 유지 |
| ② | MindGraphService.extractKeywords() | Qwen 2.5 14B (Chat) | 키워드 추출 | ✅ |
| ③ | MindGraphService.ask() | Qwen 2.5 14B (Chat) | RAG 답변 | ✅ |

**하나의 `/api/graph/extract` 요청에 LLM 최대 2회 호출:**

| # | 위치 | 모델 | 용도 | LLM-OPT 캐싱 대상 |
|---|------|------|------|-------------------|
| ① | KnowledgeExtractor.extract() | Qwen 2.5 14B (Chat) | 지식 추출 | ✅ |
| ② | VectorService.embedAndSave() | mxbai-embed-large | 임베딩 저장 | ❌ 직접 호출 유지 |

**연동 원칙:** Chat 모델만 LLM-OPT(:8000) 경유, Embedding 모델은 Ollama(:11434) 직접 호출.
임베딩 입력은 매번 다른 원문 텍스트 → 캐싱 이점 낮음.

---

## 핵심 쿼리 패턴

### Batch 조회 (N+1 방지)

```java
// NodeRepository — 키워드 N개를 한 번에 조회
List<Node> findByNameIn(Collection<String> names);
// → SELECT * FROM nodes WHERE name IN ('Docker', 'Kubernetes', ...)
```

### JOIN FETCH (LazyInitializationException 방지)

```java
@Query("SELECT e FROM Edge e " +
       "JOIN FETCH e.source s " +
       "JOIN FETCH e.target t " +
       "WHERE s.name IN :names OR t.name IN :names")
List<Edge> findEdgesByNodeNamesIn(@Param("names") List<String> names);
// Edge + source/target Node를 한 쿼리로 로딩
```

### pgvector 코사인 거리 검색

```java
String sql = """
    SELECT content, 1 - (embedding <=> ?::vector) as score
    FROM vector_store
    ORDER BY score DESC
    LIMIT 5
""";
// <=> : 코사인 거리 (작을수록 유사) → 1 - 거리 = 유사도 (클수록 유사)
```

---

## RabbitMQ 설정

```
Producer → TopicExchange (mindgraph.exchange)
         → Binding (key: mindgraph.extraction.key)
         → Queue (mindgraph.extraction.queue)
         → Consumer (@RabbitListener)

실패 시:
Consumer → 재시도 3회 (2s→4s→8s, 지수 백오프)
         → Dead Letter Exchange (mindgraph.exchange.dlx)
         → Dead Letter Queue (mindgraph.extraction.queue.dlq)
```

---

## 환경변수 / 설정 참조

```properties
# Ollama Chat 모델 (Phase A 연동 시 base-url만 변경)
langchain4j.ollama.chat-model.base-url=http://localhost:11434   # → Phase A: :8000
langchain4j.ollama.chat-model.model-name=qwen2.5:14b
langchain4j.ollama.chat-model.temperature=0.2

# Ollama Embedding 모델 (프록시 우회, 변경 없음)
langchain4j.ollama.embedding-model.base-url=http://localhost:11434
langchain4j.ollama.embedding-model.model-name=mxbai-embed-large

# RabbitMQ 재시도 정책
spring.rabbitmq.listener.simple.retry.enabled=true
spring.rabbitmq.listener.simple.retry.max-attempts=3
spring.rabbitmq.listener.simple.retry.initial-interval=2000ms
spring.rabbitmq.listener.simple.retry.multiplier=2.0
spring.rabbitmq.listener.simple.default-requeue-rejected=false
```

---

## 인프라 포트 맵

| 포트 | 서비스 | 비고 |
|------|--------|------|
| 5173 | Vite Dev Server | mindgraph-ui React 프론트엔드 |
| 5432 | PostgreSQL (pgvector) | nodes, edges, vector_store |
| 5672 | RabbitMQ (AMQP) | mindgraph.extraction.queue |
| 7474 | Neo4j (HTTP) | PoC — Phase B에서 활성화 |
| 7687 | Neo4j (Bolt) | PoC — Phase B에서 활성화 |
| 8000 | LLM-OPT Proxy (FastAPI) | Chat 모델 캐싱 프록시 |
| 8080 | MindGraph (Spring Boot) | REST API |
| 11434 | Ollama | Qwen 2.5 14B + mxbai-embed-large |
| 15672 | RabbitMQ Management UI | 재시도/DLQ 상태 확인 |

---

## 테스트 전략

| 레이어 | 방식 | 도구 |
|--------|------|------|
| Service 단위 테스트 | @ExtendWith(MockitoExtension.class) | Mockito, JUnit 5 |
| Repository 통합 테스트 | @DataJpaTest + H2 | H2, TestEntityManager |
| SearchService, VectorService | JdbcTemplate Mock | ArgumentCaptor |
| 파이프라인 순서 검증 | Mockito InOrder | InOrder |
| 전체 통합 테스트 | @SpringBootTest | docker compose up 필요 |

**pgvector 단위 테스트 불가 이유**: H2가 `<=>` 연산자 미지원 → JdbcTemplate Mock으로 SQL 문자열 검증.

---

## React UI 아키텍처 (mindgraph-ui)

**스택**: React 18 + TypeScript + Vite + Cytoscape.js + Zustand + TailwindCSS

**핵심 설계 결정 및 버그 수정 (2026-03-19)**

| 이슈 | 원인 | 해결책 |
|------|------|--------|
| 엣지 미표시 | PostgreSQL nodes/edges 동일 IDENTITY 시퀀스 → Cytoscape 통합 ID 네임스페이스 충돌 | 엣지 ID `'e-' + String(e.id)` 접두사 |
| LAZY 로딩 N+1 | `edgeRepository.findAll()` → 연관 Node lazy load | `findAllWithNodes()` JOIN FETCH 추가 |
| 로드마다 위치 초기화 | `mergeGraph` 새 배열 참조 → `useEffect` 재실행 → `randomize:true` | `elementKeyRef` 구조 변경 감지 + `randomize:false` |
| 노드 한점 겹침 | early return 전에 `cy.elements().remove()` 실행 | early return → remove() 순서 수정 |
| 필터 검색 무효 | `mergeGraph` (축적) → 동일 `elementKey` → early return | `setGraph` (교체)로 변경 |

**컴포넌트 구조**
```
App.tsx (3패널 고정: Sidebar 320px | GraphCanvas flex-1 | NodeDetail 288px)
├── Sidebar.tsx        — 추출/탐색/질문 탭, Toast 연동, 폴링
├── GraphCanvas.tsx    — Cytoscape.js 렌더링, elementKeyRef, 줌 컨트롤 4버튼
├── NodeDetail.tsx     — 노드 상세/편집/삭제, empty state 통계 대시보드
├── StatusBar.tsx      — 노드/엣지 카운트, 선택 노드 표시, 상태 메시지
└── Toast.tsx          — Zustand 기반 글로벌 알림 (3.5초 자동 dismiss)
```

---

## 면접 통합 스토리

> "개인 지식 관리 문제(파편화된 메모)를 해결하기 위해 로컬 LLM 기반 지식 그래프 시스템을 설계·구현했습니다.
> LangChain4j @AiService로 텍스트에서 지식을 자동 추출하고, pgvector로 의미 검색,
> PostgreSQL 그래프로 관계 탐색을 결합한 하이브리드 RAG 시스템입니다.
> 운영 중 N+1 쿼리, LLM 응답 비정형성, 데이터 유실, Cytoscape ID 충돌 등 실제 문제를 발견하고 해결했습니다."

**어필 포인트:**
- 문제 발견 → 해결책 설계 → 실제 구현의 완전한 루프
- Java(Spring) 백엔드 + React(TypeScript) 프론트엔드 풀스택 구현
- N+1, DLQ, 병렬 처리, Cytoscape ID 충돌 등 다양한 레이어의 버그 해결
- 50개 단위 테스트 (인프라 없이 실행 가능)
