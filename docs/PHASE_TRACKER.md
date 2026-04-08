# MindGraph-AI Phase Tracker

> 이 문서는 프로젝트 진행 상황을 추적하는 살아있는 문서입니다.
> Phase를 진행하면서 업데이트하세요.

---

## 현재 상태: Phase F (Extract/Search 고도화) 완료 (2026-03-24)

| Phase | 상태 | 완료일 |
|-------|------|--------|
| Phase 1: Core RAG Pipeline | ✅ | 2026-02 |
| Phase 2: 안정성 + 성능 최적화 | ✅ | 2026-02 |
| Phase 3: 단위 테스트 커버리지 | ✅ | 2026-02-25 |
| Phase A: LLM-OPT 연결 | ✅ | 2026-03-03 |
| Phase B: Neo4j 하이브리드 RAG | ✅ | 2026-03-03 |
| Phase C: Context-Aware RAG | ✅ | 2026-03-22 |
| Phase D: 노드 임베딩 (한/영 검색) | ✅ | 2026-03-22 |
| Phase E: Frontend 고도화 | ✅ | 2026-03-22 |
| Phase F: Extract/Search 고도화 | ✅ | 2026-03-24 |

---

## Phase 1 — Core RAG Pipeline 구축

**목표:** 로컬 LLM(Qwen 2.5 14B)을 활용한 지식 수집→저장→검색→답변 MVP 완성

**상태:** ✅ 완료

### 구현 체크리스트

- [x] 프로젝트 기반 설정
  - [x] Spring Boot 3.2 + Java 17 + Gradle
  - [x] LangChain4j 0.31 의존성
  - [x] Docker Compose (PostgreSQL+pgvector, Ollama, RabbitMQ, Neo4j)
- [x] 지식 추출 (AI Layer)
  - [x] `KnowledgeExtractor` @AiService 인터페이스
  - [x] Qwen 2.5 14B via Ollama 연동
  - [x] `GraphData` record DTO (nodes[], edges[])
  - [x] 시스템 메시지 영문화 (지시 이행률 향상)
- [x] 그래프 저장 (Graph Layer)
  - [x] `Node` / `Edge` JPA 엔티티 (UniqueConstraint 포함)
  - [x] `GraphService.saveGraph()` — Find-or-Create 패턴
  - [x] 엣지 중복 방지 (`existsBySourceIdAndTargetIdAndRelation`)
- [x] 벡터 저장 (Vector Layer)
  - [x] `VectorService.embedAndSave()` — mxbai-embed-large 임베딩
  - [x] pgvector Native SQL INSERT (`?::vector`)
- [x] 벡터 검색 (Search Layer)
  - [x] `SearchService.search()` — Cosine Distance (`<=>` 연산자)
  - [x] LIMIT 5 상위 결과 반환
- [x] RAG 답변 생성 (RAG Layer)
  - [x] `MindGraphService.ask()` — 벡터+그래프 컨텍스트 병합
  - [x] `extractKeywords()` — LLM 키워드 추출 + 정규식 파싱
  - [x] RAG 프롬프트 (출처 명시, 한국어 응답, Knowledge Graph 우선)
- [x] REST API
  - [x] `POST /api/graph/extract` → 202 비동기
  - [x] `GET /api/graph/search` → 벡터 검색
  - [x] `POST /api/graph/ask` → RAG 답변
- [x] 비동기 파이프라인
  - [x] RabbitMQ Exchange + Queue 설정
  - [x] `ExtractionPublisher` / `ExtractionListener` 구현

### 핵심 기술 결정

| 결정 | 대안 | 근거 |
|------|------|------|
| LangChain4j @AiService | RestTemplate 직접 호출 | 인터페이스 기반 추상화, 테스트 용이 |
| pgvector (PostgreSQL 내장) | Pinecone, Weaviate | 별도 벡터 DB 불필요, JPA 트랜잭션 통합 |
| RabbitMQ 비동기 처리 | 동기 직접 호출 | LLM 6초+ → 202 즉시 응답 + 백그라운드 처리 |
| `record` DTO | `class` DTO | 불변성, 보일러플레이트 제거, Jackson 지원 |

### 트러블슈팅

**LLM 응답 부연 설명 포함 → JSON 파싱 붕괴**
```
문제: @SystemMessage 한국어 → LLM이 "네, 알겠습니다." 부연 설명 추가 → 파싱 실패
해결:
  1. @SystemMessage 영문화 ("Return ONLY valid JSON...")
  2. 정규식 fallback: Pattern.compile("\\[.*?\\]", Pattern.DOTALL)
결과: 부연 설명 포함 케이스도 안정적 파싱
```

**pgvector JPA 불가 → JdbcTemplate 우회**
```
문제: Spring Data JPA/JPQL이 <=> 연산자 미지원
해결: JdbcTemplate native SQL
  "SELECT content, 1 - (embedding <=> ?::vector) as score FROM vector_store ORDER BY score DESC LIMIT 5"
```

### 면접 포인트 (Phase 1)

```
Q: LangChain4j @AiService가 어떻게 동작하나요?
A: @AiService 어노테이션 인터페이스를 선언하면 LangChain4j가 런타임에 프록시를 생성합니다.
   @SystemMessage로 시스템 프롬프트, @UserMessage로 사용자 입력을 정의하면
   Spring 빈으로 자동 주입됩니다. 테스트 시 ChatLanguageModel을 Mock하면
   실제 LLM 없이 단위 테스트가 가능합니다.

Q: pgvector를 JPA 없이 JdbcTemplate으로 쓴 이유는?
A: pgvector의 <=> (cosine distance) 연산자는 표준 SQL이 아닙니다.
   Spring Data JPA/JPQL에서 지원하지 않아 native SQL을 직접 실행하는
   JdbcTemplate을 사용했습니다. 저장/조회 모두 동일 이유입니다.

Q: RabbitMQ를 도입한 이유는?
A: LLM 추론이 평균 6초입니다. 동기 처리하면 API가 6초를 기다립니다.
   RabbitMQ로 처리 요청을 큐에 넣고 202 Accepted를 즉시 반환합니다.
   사용자 경험과 시스템 안정성을 동시에 개선합니다.

Q: GraphData를 record로 정의한 이유는?
A: DTO는 LLM 응답을 받아 전달하는 역할만 합니다. 불변 객체가 적합합니다.
   record는 equals/hashCode/toString 자동 생성, 보일러플레이트 없음,
   Jackson JSON 역직렬화도 지원합니다.
```

### 블로그 소재

```
- 로컬 LLM으로 개인 지식 그래프 만들기 (전체 아키텍처)  → blog/phase1-rag-pipeline.md
- LangChain4j @AiService로 구조화된 JSON 추출 (+ LLM 부연 설명 대응법)
- pgvector를 Spring Boot JPA 없이 쓰는 법 (JdbcTemplate + native SQL)
```

---

## Phase 2 — 안정성 강화 + 성능 최적화

**목표:** 운영 중 발생한 실제 문제들 해결 — 데이터 유실, N+1 쿼리, 답변 품질 저하

**상태:** ✅ 완료

### 구현 체크리스트

- [x] 메시지 큐 안정성 (DLQ)
  - [x] Dead Letter Exchange/Queue 설정
  - [x] Spring AMQP 자동 재시도 (3회, 2s→4s→8s 지수 백오프)
  - [x] `default-requeue-rejected: false` (무한 재시도 방지)
- [x] 검색 성능 최적화 (N+1 방지)
  - [x] `NodeRepository.findByNameIn()` — Batch 조회
  - [x] `EdgeRepository.findEdgesByNodeNamesIn()` — JOIN FETCH
  - [x] DB IO 약 80% 절감
- [x] 답변 품질 고도화
  - [x] Temporal Context 분리 (`\d{4}` 패턴으로 연도 분류)
  - [x] `CompletableFuture.supplyAsync()` 병렬 검색
  - [x] RAG 프롬프트 강화 (출처 명시, 충돌 시 Knowledge Graph 우선)
- [x] 다중 채널 수집
  - [x] `FileWatcherService` — Java WatchService API
  - [x] `watched-files/` 디렉토리 이벤트 → RabbitMQ 자동 발행
- [x] 개발환경 자동화
  - [x] `manage.ps1` — Wait-for-it 패턴 (포트 가용성 확인)
  - [x] GPU 워밍업 (첫 API 호출 지연 제거)

### 핵심 기술 결정

| 결정 | 대안 | 근거 |
|------|------|------|
| `findByNameIn` (Batch) | 개별 `findByName` 루프 | N개 노드 → DB 쿼리 1회 (N→1) |
| JOIN FETCH JPQL | @EntityGraph / 2차 쿼리 | 엣지+양쪽 노드 한 쿼리, Lazy 로딩 없음 |
| `CompletableFuture.supplyAsync` | 순차 실행 | 벡터/그래프 독립 → 병렬로 응답 시간 단축 |
| Spring AMQP retry (지수 백오프) | 단순 즉시 재시도 | LLM/DB 일시 장애 시 thundering herd 방지 |
| WatchService API | inotifywait, watchdog 라이브러리 | Java 표준, 추가 의존성 없음 |

### 트러블슈팅

**N+1 쿼리 병목**
```
문제: 키워드 5개 → findByName 5회 + 각 Edge LAZY 로딩 → 수십 개 쿼리
      LLM이 이미 6초를 쓰는 환경에서 DB 병목이 가시적으로 추가됨
해결:
  findByNameIn(keywords) → SELECT WHERE name IN (...)  (1회)
  findEdgesByNodeNamesIn() JOIN FETCH source, target    (1회)
결과: DB IO 약 80% 절감 (측정: show-sql 로그 쿼리 수 비교)
```

**연도 키워드가 무의미한 노드로 저장**
```
문제: "2026년 Manus AI 설립"에서 "2026" 엔티티 노드 생성
      "2023년 GDP" 검색 시 "2026년" 관련 데이터 노출
해결: 키워드를 Entity/Temporal로 분리
  entityKeywords = keywords.filter(k -> !k.matches("\\d{4}"))
  yearKeywords   = keywords.filter(k -> k.matches("\\d{4}"))
  → entityKeywords로만 노드 검색, yearKeywords는 결과 텍스트 필터
결과: 시계열 맥락 유지 + 무의미한 연도 노드 검색 제거
```

**DLQ 미설정 — 무한 재시도 함정**
```
문제: default-requeue-rejected=true(기본) + LLM 오류 → 메시지가 큐 앞으로 복귀 → 무한 루프
해결: default-requeue-rejected=false + DLQ 설정
      실패 3회 → DLQ 격리 → 수동 확인 후 재처리
```

### 면접 포인트 (Phase 2)

```
Q: N+1 문제를 어떻게 발견하고 해결했나요?
A: spring.jpa.show-sql=true 로그에서 키워드 N개에 SELECT가 N+1번 나가는 것을 확인했습니다.
   findByNameIn(List)으로 배치 조회로 바꾸고, 엣지는 JOIN FETCH JPQL로
   연관 노드를 한 번에 가져오도록 수정해 DB IO를 약 80% 줄였습니다.

Q: DLQ(Dead Letter Queue)를 왜 도입했나요?
A: LLM 호출 실패 시 메시지를 잃고 싶지 않았습니다.
   default-requeue-rejected=false로 실패 메시지를 DLQ에 격리하고,
   Spring AMQP retry를 3회(2s→4s→8s 지수 백오프)로 설정했습니다.
   일시적 LLM/DB 장애는 재시도로, 영구적 오류는 DLQ에서 수동 처리합니다.

Q: CompletableFuture 병렬 검색을 구현한 이유는?
A: 벡터 검색(pgvector)과 그래프 검색(PostgreSQL)은 완전히 독립적입니다.
   순차 실행 시 A+B 시간, CompletableFuture.supplyAsync() 병렬 실행 시 max(A, B) 시간.
   두 검색을 동시에 시작하고 join()으로 결과를 모읍니다.

Q: Temporal Context 분리가 왜 필요했나요?
A: "2026년 Manus AI 설립"에서 "2026"을 엔티티로 처리하면 무의미한 노드가 생깁니다.
   또 "2023년 GDP" 검색 시 "2026년 GDP" 데이터가 함께 노출됩니다.
   4자리 숫자(\d{4})로 연도를 분리해 엔티티 검색에서 제외하고,
   텍스트 필터링에만 사용해 두 문제를 동시에 해결했습니다.
```

### 블로그 소재

```
- RabbitMQ DLQ로 LLM 장애 시 데이터 유실 없애기  → blog/phase2-n1-rabbitmq-dql.md
- JPA N+1을 직접 겪고 findByNameIn + JOIN FETCH로 해결한 과정  → blog/phase2-n1-rabbitmq-dql.md
- LLM이 숫자와 개념을 구분 못할 때: Temporal Context 분리 전략
```

---

## Phase 3 — 단위 테스트 커버리지

**목표:** 핵심 서비스 로직 회귀 테스트 확보, LLM-OPT 통합 전 안전망 구축

**상태:** ✅ 완료 (2026-02-25)

### 구현 체크리스트

- [x] 빌드 설정
  - [x] H2 In-Memory DB 추가 (`testImplementation 'com.h2database:h2'`)
  - [x] `junit-platform-launcher` 추가 (Gradle 9.x 필수)
- [x] 서비스 단위 테스트 (Mockito, 49개)
  - [x] `ExtractionPublisherTest` — 3개 (RabbitTemplate 검증)
  - [x] `ExtractionListenerTest` — 5개 (InOrder 파이프라인 순서)
  - [x] `VectorServiceTest` — 4개 (EmbeddingModel + JdbcTemplate mock)
  - [x] `SearchServiceTest` — 5개 (pgvector SQL `<=>` 포함 검증)
  - [x] `GraphServiceTest` — 10개 (Find-or-Create, 중복 엣지 방지)
  - [x] `MindGraphServiceTest` — 13개 (extractKeywords, yearsMatch, ask)
  - [x] `NodeRepositoryTest` — 4개 (@DataJpaTest + H2)
  - [x] `EdgeRepositoryTest` — 5개 (JOIN FETCH LazyInit 없음 검증)

### 결과

```
측정일: 2026-02-25
총 50개 테스트 (기존 contextLoads 1개 + 신규 49개)

인프라 없이:  49/50 통과 (contextLoads만 실패 — 정상)
docker compose up -d 후: 50/50 통과

핵심 검증 항목:
- LLM 부연 설명 포함 응답 → 정규식 추출 정상 동작
- LLM 예외 → 빈 리스트 반환 (graceful degradation, 파이프라인 계속)
- extract → saveGraph → embedAndSave 실행 순서 보장 (InOrder)
- JOIN FETCH로 LazyInitializationException 없이 source/target 접근
- Find-or-Create: 동일 name+type 노드 중복 저장 방지
```

### 트러블슈팅

**Gradle 9.x — JUnit Platform 로딩 실패**
```
오류: Failed to load JUnit Platform
원인: Gradle 9.x가 junit-platform-launcher를 명시적으로 요구
해결: testRuntimeOnly 'org.junit.platform:junit-platform-launcher' 추가
```

**@DataJpaTest + Neo4j 자동 설정 충돌**
```
문제: spring-boot-starter-data-neo4j가 classpath에 있어 Neo4j 연결 시도
해결: @DataJpaTest(excludeAutoConfiguration = {
        Neo4jAutoConfiguration.class,
        Neo4jDataAutoConfiguration.class,
        Neo4jRepositoriesAutoConfiguration.class
      })
```

### 면접 포인트 (Phase 3)

```
Q: pgvector를 쓰는 Service를 어떻게 단위 테스트했나요?
A: pgvector의 <=> 연산자는 H2에서 동작하지 않습니다.
   JdbcTemplate을 Mock으로 교체하고, ArgumentCaptor로 SQL 문자열에
   "<=>", "LIMIT 5"가 포함되는지 검증했습니다.

Q: ExtractionListener 파이프라인 순서를 어떻게 테스트했나요?
A: Mockito의 InOrder를 사용했습니다.
   InOrder inOrder = inOrder(knowledgeExtractor, graphService, vectorService);
   순서가 다르면 테스트가 실패합니다. 중간 예외 시 이후 단계가
   never()로 호출되지 않음도 함께 검증합니다.

Q: @DataJpaTest에서 Neo4j 설정 충돌 이유는?
A: build.gradle에 spring-boot-starter-data-neo4j가 포함되어 있어
   테스트 자동 설정이 Neo4j 연결을 시도합니다.
   excludeAutoConfiguration으로 명시적으로 제외했습니다.
```

### 블로그 소재

```
- LLM 의존 코드를 인프라 없이 단위 테스트하는 법  → blog/phase3-unit-test-llm.md
- @DataJpaTest + H2로 JOIN FETCH 검증하기 (entityManager.clear() 함정)
- Gradle 9.x에서 Spring Boot 테스트 설정 변경점
```

---

## Phase A — LLM-OPT 연결

**목표:** MindGraph Chat 트래픽을 LLM-OPT 프록시 경유, 캐싱 효과 실측

**상태:** ✅ 완료 (2026-03-03)

### 구현 내용

```
A-1. LLM-OPT: ollama_compat.py 구현 (llm-opt 측)
     POST /api/chat (Ollama 네이티브 포맷 ↔ 캐시 파이프라인 ↔ Ollama 응답)

A-2. MindGraph: 설정 변경
     - application.properties: chat-model base-url → http://localhost:8000
     - LangChainConfig.java: chatBaseUrl / embeddingBaseUrl 필드 분리
       (버그: 기존에 chat-model base-url을 chat/embedding 공용으로 사용 → embedding이 8000으로 잘못 호출)
       (수정: @Value("${langchain4j.ollama.embedding-model.base-url}") 별도 필드 추가)

A-3. 통합 검증
     - 동일 텍스트 재추출 → LLM-OPT 로그에서 L1 Cache Hit 확인
     - Embedding 트래픽은 Ollama(11434) 직접 호출 유지 확인
```

### 성공 기준

- [x] Chat 트래픽이 LLM-OPT(:8000)를 경유
- [x] 동일 질문 반복 → L1 Cache Hit
- [x] Embedding 트래픽은 Ollama 직접 호출 유지

### 실측 결과 (2026-03-03)

```
환경: Qwen 2.5 14B (RTX 4080 Super), Redis Stack 7.4, MindGraph Spring Boot
테스트: POST /api/graph/extract → "Docker는 컨테이너 가상화 기술이다."

[캐시 미스 (첫 번째 호출)]
- event: llm_call
- latency_ms: 5,510

[캐시 히트 (동일 텍스트 재전송)]
- event: cache_hit, tier: l1_hash
- latency_ms: 0.43 ~ 0.62 (약 10,000x 이상 속도 향상)

[라우팅 검증]
- Chat 요청: MindGraph(8080) → LLM-OPT(8000) /api/chat → Ollama(11434) ✅
- Embedding 요청: MindGraph(8080) → Ollama(11434) /api/embeddings (프록시 우회) ✅
```

### 트러블슈팅

```
[2026-03-03] MindGraph embedding 요청이 LLM-OPT(8000)로 잘못 라우팅 → 404
- 원인: LangChainConfig.java에서 chat-model.base-url을 chat/embedding 공용으로 사용
- 수정: @Value("${langchain4j.ollama.embedding-model.base-url}") embeddingBaseUrl 별도 필드 추가
```

### 면접 포인트 (Phase A)

```
Q: MindGraph를 LLM-OPT 프록시에 연결할 때 가장 큰 문제가 뭐였나요?
A: LangChain4j의 OllamaChatModel이 Ollama 네이티브 포맷(/api/chat)을 쓰므로
   LLM-OPT에 Ollama 호환 엔드포인트가 필요했습니다.
   그리고 LangChainConfig.java에서 chat/embedding base-url을 하나로 공유하고 있었는데,
   chat만 프록시로 바꾸면서 embedding이 8000으로 잘못 라우팅되는 버그가 생겼습니다.
   @Value 필드를 분리해 chat은 :8000, embedding은 :11434 직접 호출로 분리했습니다.

Q: Embedding 요청은 왜 LLM-OPT를 우회하나요?
A: Embedding 입력은 매번 다른 원문 텍스트입니다. 동일 입력이 거의 없어 캐싱 이점이 없습니다.
   반면 Chat 요청(지식 추출, 키워드 추출, 답변 생성)은 동일하거나 유사한 질문이 반복됩니다.
   연동 원칙: 캐싱 효과가 있는 Chat만 프록시 경유, Embedding은 Ollama 직접 호출.

Q: 실측 결과는?
A: 동일 텍스트 재추출 시 5,510ms → 0.43ms, 약 10,000배 속도 향상.
   실 운영에서 동일 문서를 재수집하거나 유사한 질문이 반복될 때 즉각 효과가 납니다.
```

---

## Phase B — Neo4j 하이브리드

**목표:** PostgreSQL 관계형 + Neo4j 그래프 탐색 결합

**상태:** ✅ 완료 (2026-03-03)

### 구현 내용

```
B-1. neo4j/entity/ 교체
     Person.java(PoC) 삭제 → KnowledgeNode.java (@Node("KnowledgeNode"), name/type 필드)

B-2. GraphService.java 확장
     - Neo4jClient 주입
     - syncToNeo4j(): Neo4jClient로 MERGE 기반 노드/관계 저장
     - saveGraph() 내 독립 try-catch: Neo4j 실패해도 PostgreSQL 커밋 보장

B-3. MindGraphService.java 확장
     - Neo4jClient 주입
     - searchNeo4jTwoHop(): 1-hop + 2-hop Cypher UNION 쿼리
     - searchGraph(): PostgreSQL + Neo4j 결과 병합
     - Neo4j 실패 시 graceful fallback (PostgreSQL만 반환)

B-4. 테스트
     - KnowledgeNodeNeo4jRepository 삭제 (@DataJpaTest neo4jTemplate 충돌)
     - 50개 테스트 전체 통과 유지
```

### 성공 기준

- [x] Neo4j에 노드/관계 저장 성공 (MERGE)
- [x] 2-hop 탐색 결과가 RAG 컨텍스트에 포함
- [x] Neo4j 장애 시 PostgreSQL 저장 정상 완료 (독립 try-catch)

### 실측 결과 (2026-03-03)

```
환경: Neo4j 5.x (Docker), Spring Data Neo4j 6.x, Neo4jClient

[저장 검증]
입력: "Spring Boot는 Java 기반 웹 프레임워크다."
PostgreSQL: Spring Boot(Project), Java(Technology) → 저장 ✅
Neo4j:      Spring Boot --is based on--> Java       → MERGE ✅

[2-hop 검색 검증]
그래프: Docker → is → 컨테이너 가상화 기술
        Spring Boot → is based on → Java

POST /api/ask {"question": "Docker가 무엇인지 설명해줘"}
→ PostgreSQL 1-hop + Neo4j 2-hop 컨텍스트 병합 ✅
→ "[Neo4j 2-hop 확장 연관 개념]" 섹션 포함 응답 ✅

[테스트]
50개 전체 통과 (신규 구현 후 회귀 없음)
```

### 설계 결정

| 결정 | 대안 | 이유 |
|------|------|------|
| `Neo4jClient` 직접 사용 | `Neo4jRepository` + `@Query` | 복잡한 Cypher에서 관계 속성 직접 반환; `@Query` 리턴 타입 제약 없음 |
| `KnowledgeNodeNeo4jRepository` 삭제 | 유지 | `@DataJpaTest`에서 `neo4jTemplate` 미존재로 10개 테스트 실패; 실제 사용처 없음 |
| MERGE (CREATE 대신) | MATCH + CREATE | 중복 없이 멱등 저장; 재처리 시 안전 |
| 독립 try-catch in `saveGraph()` | 트랜잭션 묶기 | 구현 단순; Neo4j 장애가 PostgreSQL 롤백 유발하지 않음 |

### 트러블슈팅

```
[2026-03-03] @DataJpaTest 10개 실패 — "No bean named 'neo4jTemplate'"
- 원인: KnowledgeNodeNeo4jRepository가 컴포넌트 스캔에 잡혀 neo4jTemplate 빈 요구
        → @DataJpaTest 컨텍스트에 없음
- 해결: KnowledgeNodeNeo4jRepository 삭제 (Neo4jClient 직접 사용으로 불필요)
- 결과: 50개 테스트 전체 통과 ✅
```

### 면접 포인트 (Phase B)

```
Q: Neo4j와 PostgreSQL을 동시에 쓰는 이유는?
A: PostgreSQL은 정형 데이터 저장과 벡터 검색에 최적화되어 있고,
   Neo4j는 2-hop 이상의 관계 탐색에 강점이 있습니다.
   예: "Docker" 검색 시 PostgreSQL은 직접 저장된 Docker 노드만 반환하지만,
   Neo4j는 Docker → 컨테이너 가상화 → Kubernetes 경로를 한 쿼리로 탐색해
   RAG 컨텍스트를 자동 확장합니다.

Q: Neo4j 장애 시 서비스가 중단되지 않나요?
A: saveGraph()와 searchGraph() 모두 독립 try-catch로 분리했습니다.
   Neo4j 실패 시 로그만 남기고 PostgreSQL 결과만 반환합니다.
   Neo4j는 "있으면 더 좋은" 추가 컨텍스트로 설계했습니다.

Q: Neo4jRepository 대신 Neo4jClient를 쓴 이유는?
A: 2-hop Cypher 쿼리에서 관계 속성(relation 텍스트)을 포함한 문자열을
   직접 반환해야 해서 @Query의 리턴 타입 제약을 피하기 위해 Neo4jClient 사용.
   MERGE 기반 저장도 Neo4jClient로 제어가 명확합니다.
   부수 효과로 @DataJpaTest neo4jTemplate 충돌도 해결됐습니다.

Q: 테스트에서 Neo4j 관련 충돌을 어떻게 해결했나요?
A: KnowledgeNodeNeo4jRepository가 컴포넌트 스캔에 잡혀 @DataJpaTest에서
   neo4jTemplate 빈을 요구하면서 10개 테스트가 실패했습니다.
   Neo4jClient로 직접 구현하면서 Repository 자체가 불필요해졌고,
   삭제하자 테스트 50개 전체 통과했습니다.

---

## Phase C — Context-Aware RAG (컨텍스트 품질 개선)

**목표:** 벡터+그래프 결과를 단순 연결에서 점수 기반 병합으로 개선.
        토큰 예산 관리로 컨텍스트 오버플로 방지.

**상태:** ✅ 완료 (2026-03-22)

### 구현 체크리스트

- [x] `RankedContext.java` — `record(String content, double score, Source source)` + `Source` enum
  - Source: `VECTOR`, `PG_GRAPH`, `NEO4J_1HOP`, `NEO4J_2HOP`
- [x] `Node.java` — `createdAt`, `updatedAt` 타임스탬프 추가 (`@PrePersist`, `@PreUpdate`)
- [x] `SearchService.search()` — `@Value` 파라미터화 (limit=5, threshold=0.3)
  - `search(query)` 오버로드 유지 (하위 호환)
  - `search(query, limit, threshold)` 오버로드 추가
- [x] `NodeRepository` — `findRecentNodes(Pageable)`, `findTopNodesByDegree(limit)` 추가
- [x] `MindGraphService.searchGraphRanked()` — `List<RankedContext>` 반환
  - PG_GRAPH: score=0.7 / NEO4J_1HOP: score=0.6 / NEO4J_2HOP: score=0.4
- [x] `MindGraphService.assembleContext()` — 통합 컨텍스트 조립
  - 벡터+그래프 병합 → content 앞 50자 key로 중복 제거 → score 내림차순 정렬 → MAX_CONTEXT_CHARS(4000자) 예산 절단
- [x] `MindGraphService.buildFullGraphSummary()` — `findAll()` 제거
  - 최근 노드 10개 + 허브 노드 10개 (degree 기준) + 관계 15개
- [x] 테스트 82개 전체 통과 (Phase C 신규 7개 포함)

### 핵심 기술 결정

| 결정 | 대안 | 근거 |
|------|------|------|
| 그래프 score 고정값 (PG=0.7, 1hop=0.6, 2hop=0.4) | Cross-Encoder Reranker | 16GB VRAM에서 Qwen 14B와 동시 로드 부담; 고정값으로 시작, 교체 용이하도록 Source enum 분리 |
| MAX_CONTEXT_CHARS=4000 (문자 수) | 토큰 카운터 | LangChain4j TokenCountEstimator 추가 의존성 없이 rough estimate; 오차 허용 가능 |
| content 앞 50자 중복 key | 완전 일치 비교 | LLM이 같은 내용을 약간 다른 표현으로 저장하는 케이스 커버 |
| `findTopNodesByDegree` native query | findAll() 후 메모리 정렬 | OOM 방지; 노드 수 무관 O(1) DB 부하 |

### 면접 포인트 (Phase C)

```
Q: 벡터 검색과 그래프 검색 결과를 어떻게 통합했나요?
A: RankedContext(content, score, source) 자료구조로 두 결과를 추상화했습니다.
   벡터 결과는 cosine similarity 그대로, 그래프 결과는 hop 거리 기반 고정 score
   (PG=0.7, Neo4j 1-hop=0.6, 2-hop=0.4)를 부여한 뒤 score 내림차순으로 정렬합니다.
   content 앞 50자를 key로 중복 제거 후 MAX_CONTEXT_CHARS=4000자 예산 내로 절단합니다.

Q: buildFullGraphSummary()에서 findAll()을 왜 제거했나요?
A: 노드가 늘어나면 findAll()이 전체를 메모리에 로드해 OOM 위험이 있었습니다.
   대신 degree 기준 상위 10개(허브 노드)와 최근 추가 10개를 native query로 조회합니다.
   허브 노드는 지식 그래프에서 가장 연결이 많은 핵심 개념이므로 컨텍스트 대표성이 높습니다.
```

---

## Phase D — 노드 이름 임베딩 (한/영 교차 검색)

**목표:** 한국어 노드("도커")가 영어 키워드("Docker")로 검색되지 않는 문제 해결.
        bge-m3 멀티링궐 임베딩으로 언어 불문 유사도 기반 노드 검색.

**상태:** ✅ 완료 (2026-03-22)

### 배경

Phase C 이후 실 테스트에서 발견:
- "Docker에 대해 알려줘" 질문 시 노드명 "도커"가 키워드 매칭 실패
- `findByNameIn(["Docker"])` → 0개 결과 → 그래프 컨텍스트 누락
- RAG 응답에 `[지식 그래프]` 섹션 없음

### 구현 체크리스트

- [x] `nodes.name_embedding vector(1024)` 컬럼 — `VectorService.@PostConstruct`로 자동 DDL
  - `ALTER TABLE nodes ADD COLUMN IF NOT EXISTS name_embedding vector(1024)` (멱등)
- [x] `GraphService.saveNameEmbedding(nodeId, name)` — 노드 저장 후 즉시 이름 임베딩
  - 독립 try-catch: 임베딩 실패해도 JPA 트랜잭션 롤백 없음
- [x] `NodeRepository.findSimilarByNameEmbedding(embedding, threshold, limit)` — native query
  - `CAST(:embedding AS vector)` (`:param::vector` 형식 Spring named-param 파싱 충돌 방지)
  - cosine similarity threshold=0.6 이상만 반환
- [x] `MindGraphService.findNodesBySimilarity(keywords)` — `findByNameIn` 대체
  - 각 키워드 임베딩 → 유사 노드 top-3 → ID 기준 중복 제거 (`LinkedHashMap<Long, Node>`)
- [x] `GraphService.rebuildNodeEmbeddings()` — 기존 노드 일괄 임베딩 (마이그레이션용)
- [x] `POST /api/graph/rebuild-node-embeddings` — 컨트롤러 엔드포인트
- [x] `MindGraphService.rebuildNodeEmbeddings()` — GraphService 위임
- [x] 테스트 82개 전체 통과 (D-1 신규 3개 포함)

### 실측 결과 (2026-03-22)

```
환경: bge-m3 (Ollama, 1024차원), PostgreSQL pgvector
마이그레이션: POST /api/graph/rebuild-node-embeddings → embedded=3, skipped=0

[언어 불일치 해결 전]
POST /ask {"question": "Docker에 대해 알려줘"}
→ [지식 그래프] 섹션 없음 (findByNameIn("Docker") → 도커 노드 미매칭)

[언어 불일치 해결 후]
POST /ask {"question": "Docker에 대해 알려줘"}
→ "Docker는 컨테이너 기술입니다. Kubernetes는 Docker를 사용하여 여러 Docker 컨테이너들을 효과적으로 관리하고 실행합니다. [지식 그래프]"
→ [지식 그래프] 태그 포함 ✅
```

### 핵심 기술 결정

| 결정 | 대안 | 근거 |
|------|------|------|
| bge-m3 (1024차원) | paraphrase-multilingual-MiniLM (384차원) | 이미 임베딩 모델로 사용 중; 추가 모델 없음; 한/영/일 멀티링궐 지원 |
| threshold=0.6 | 더 높게 | 언어 불일치 케이스에서 같은 개념의 유사도가 0.6~0.8 범위에 분포 |
| ID 기준 Map 중복 제거 | `.distinct()` | JPA 엔티티 `equals/hashCode` 미구현 → 객체 동일성 비교로 중복 제거 불가 |
| `CAST(:embedding AS vector)` | `:embedding::vector` | Spring Data JPA가 `::`를 named-param 구분자로 파싱 → 런타임 에러 |

### 면접 포인트 (Phase D)

```
Q: 한국어/영어 키워드 불일치를 어떻게 해결했나요?
A: 기존에는 findByNameIn()으로 노드 이름을 정확히 매칭했습니다.
   "Docker" 키워드로 "도커" 노드를 찾지 못했습니다.
   bge-m3 멀티링궐 임베딩으로 각 노드 이름을 벡터화해 name_embedding 컬럼에 저장하고,
   질문에서 추출한 키워드도 같은 모델로 임베딩 후 cosine similarity로 비교합니다.
   같은 개념의 한/영 표현은 벡터 공간에서 유사하게 위치해 threshold=0.6으로 매칭됩니다.

Q: 왜 임베딩 실패를 독립 try-catch로 처리했나요?
A: Neo4j 동기화와 같은 이유입니다. 임베딩은 "있으면 더 좋은" 부가 기능입니다.
   Ollama가 일시적으로 불안정하더라도 노드 저장 자체는 성공해야 합니다.
   독립 try-catch로 임베딩 실패를 격리하면 PostgreSQL JPA 트랜잭션에 영향이 없습니다.
```

---

## Phase E — Frontend 고도화

**목표:** 단일 답변 표시 → 대화 히스토리; 새로고침 후 그래프 소실 문제 해결;
        의미 검색 결과와 그래프 캔버스 연동.

**상태:** ✅ 완료 (2026-03-22)

### 구현 체크리스트

- [x] **F-1: 질문 히스토리** — `answer: string` → `answers: QAItem[]`
  - QAItem: `{id, question, answer, timestamp(ISO)}` — 최신 순 prepend
  - 질문/답변 카드 스택, 시간 표시, 개별 복사 버튼, 전체 삭제 버튼
- [x] **F-2: localStorage 캐싱** — Zustand `persist` 미들웨어
  - `nodes`, `edges`만 영속화 (UI 상태 제외)
  - 새로고침 후 마지막 그래프 상태 자동 복원
- [x] **F-3: 의미 검색 → 그래프 연동** — `focusNodeId: number | null`
  - 벡터 검색 결과 카드 클릭 → content에서 노드 이름 매칭 → `setFocusNodeId(id)`
  - GraphCanvas: `focusNodeId` watch → `cy.animate({ center, zoom: 1.8 })` + 선택
  - 인사이트 탭 노드 클릭도 동일 포커스 적용
- [x] **F-4: 신규 노드 강조** — `newNodeIds: number[]`
  - `mergeGraph()`: 신규 노드 ID diff 계산 → `newNodeIds` 저장
  - GraphCanvas: 노란 테두리 (`#fbbf24`, 4px) 3초 → 원래 스타일 복원
- [x] **F-5: 임베딩 재생성 버튼** — 인사이트 탭 하단 "유지 관리" 섹션
  - `POST /api/graph/rebuild-node-embeddings` → 결과 Toast 표시

### 핵심 기술 결정

| 결정 | 대안 | 근거 |
|------|------|------|
| Zustand `persist` (F-2) | localStorage 수동 관리 | 미들웨어로 자동 직렬화/역직렬화; `partialize`로 영속화 범위 제한 |
| content 노드 이름 매칭 (F-3) | 별도 API | 추가 백엔드 불필요; 현재 규모에서 충분 |
| F-4 useEffect 선언 순서 | useLayoutEffect | nodes effect 먼저 → `cy.add()` 완료 → newNodeIds effect에서 스타일 적용 (순서 보장) |
| timestamp ISO string (F-1) | Date 객체 | localStorage JSON 직렬화 시 Date → string 자동 변환 방지 |

---

---

## Phase F — Extract/Search 고도화 (비판적 분석 기반)

**목표:** 실제 운영에서 발견한 RAG 검색 품질 문제 10가지 중 가장 영향 큰 5가지 해결.
        각 수정은 독립적으로 배포 가능하도록 설계.

**상태:** ✅ 완료 (2026-03-24)

### 구현 체크리스트

- [x] **F-1a: Neo4j union 검색** — PG+Neo4j 교차 누락 방지
  - 기존: `searchNeo4jTwoHop(entityKeywords)` — raw keyword exact match
  - 변경: `entityKeywords + nodeNames` union → PG에만 있는 노드, Neo4j에만 있는 노드 모두 커버
  - 동기: PG는 임베딩 유사도로 노드를 찾지만 Neo4j는 raw keyword exact match였음 → 불일치
- [x] **F-1c: RRF 기반 동적 스코어링** — 고정 score 제거
  - 기존: PG_GRAPH=0.7, NEO4J_1HOP=0.6, NEO4J_2HOP=0.4 하드코딩
  - 변경: RRF(Reciprocal Rank Fusion) `1/(k+rank)` — scale 차이 없이 다중 소스 통합
  - `NodeWithScore(Node, similarity)` DTO 신규 추가
  - `mindgraph.search.rrf-k=60` (application.properties 설정값)
  - `NodeRepository.findSimilarWithScore()` — id/name/similarity 반환 native query 추가
- [x] **F-6a: 의미 단위 청킹** — 2000자 슬라이딩 윈도우 → 단락 기반
  - 기존: 문자 수 기준 슬라이딩, 문장부호에서 자르기
  - 변경: `\n\n` 단락 분리 → 인접 병합(MIN_CHUNK_SIZE=500) → CHUNK_SIZE 초과 시 문장경계 fallback
  - 오버랩: 이전 청크 마지막 문장을 다음 청크 앞에 추가 (문맥 연속성)
- [x] **F-3: 노드 이름 정규화 (2단계)** — 그래프 파편화 방지
  - 4a — 자동 병합 (threshold=0.95): "도커"/"Docker" 표기 차이 → 기존 노드 재사용 + 로그
  - 4b — 후보 탐지 (threshold=0.80~0.95): 병합 후보 로그 기록 (수동 확인용)
  - `mindgraph.node.merge-threshold=0.95`, `mindgraph.node.candidate-threshold=0.80` (설정값)
- [x] **F-6b: Query Expansion** — 짧은 질문 vs 긴 청크 유사도 저하 완화
  - 벡터 검색 전 키워드 노드의 description을 질문에 concat
  - 1차: `findByNameIn` exact match / 2차: `findSimilarByNameEmbedding(threshold=0.7)` fallback
  - 언어 불일치 케이스 ("도커" 키워드 → "Docker" 노드 description 확장) 대응
  - LLM 추가 호출 없이 DB 데이터만 활용
- [x] **버그 수정 3건**
  - SLF4J 로그 포맷: `{:.3f}` (Python 문법) → `{}` 런타임 버그 수정
  - `extractLastSentence` dead code(`end = i`) 제거
  - `expandQuery` 언어 불일치 → semantic fallback 추가 (Fix 2와 통합)

### 핵심 기술 결정

| 결정 | 대안 | 근거 |
|------|------|------|
| RRF(k=60) | Weighted Sum | scale 차이 없이 다중 소스 통합; k=60은 IR 표준값 |
| 단락(\n\n) 기반 청킹 | 토픽 모델링 | 추가 의존성 없음; 일반 문서는 단락이 의미 단위 |
| 자동 병합 threshold=0.95 | 병합 없음 | 0.95+는 표기 차이(대소문자, 한/영)만; "Java"↔"JavaScript"는 0.95 미만 (예상, 미검증) |
| expandQuery 1000자 제한 | 무제한 | bge-m3 8192토큰이지만 너무 긴 쿼리는 임베딩 품질 저하 가능 |

### 테스트 현황

```
88개 통과 (contextLoads 제외) — 신규 9개 포함
- VectorServiceTest: 청킹 로직 5개 신규
- GraphServiceTest: F-3 정규화 2개 신규
- MindGraphServiceTest: F-6b Query Expansion 3개 신규 (semantic fallback 포함)
```

### 트러블슈팅

**Java 17 API 미지원 오류**
```
문제: getFirst(), removeLast() 사용 → Java 21+ API, 컴파일 실패
해결: get(0), remove(size()-1) 으로 대체
```

**List<Object[]> 타입 추론 오류 (테스트)**
```
문제: List.of(new Object[]{...})에서 제네릭 추론 실패
해결: List.<Object[]>of(new Object[]{...}) 명시적 타입 파라미터
```

**expandQuery가 parallel future를 직렬화**
```
문제: extractKeywords()가 parallel future 앞에서 blocking → 전체 지연 증가 가능성
현황: LLM-OPT 캐시 환경에서 0.43ms라 실질 영향 미미
향후: LLM-OPT 미사용 환경에서는 개선 필요
```

### 면접 포인트 (Phase F)

```
Q: RAG 검색 결과 통합을 어떻게 개선했나요?
A: 기존에는 PG_GRAPH=0.7, Neo4j 1-hop=0.6, 2-hop=0.4 고정 score를 썼습니다.
   벡터 검색의 cosine similarity(0.0~1.0)와 scale이 달라 그래프 결과가 벡터 결과보다
   항상 우선했습니다. RRF(Reciprocal Rank Fusion)로 바꾸면서 각 소스의 순위(rank)만
   보므로 scale 차이가 없어졌습니다. 같은 내용이 여러 소스에서 나오면 score가 합산됩니다.

Q: 청킹 방식을 바꾼 이유는?
A: 2000자 슬라이딩 윈도우는 의미 경계를 무시했습니다.
   논문 제목 다음 라인에서 잘리거나, 코드 블록 중간에서 분리되는 문제가 있었습니다.
   \n\n(단락) 기준으로 1차 분리 후 인접 단락을 CHUNK_SIZE 이내로 병합하고,
   큰 단락만 문장 경계 fallback으로 분할합니다.
   오버랩은 이전 청크의 마지막 문장 1개를 다음 청크 앞에 붙여 문맥을 유지합니다.

Q: 노드 정규화에서 threshold를 어떻게 정했나요?
A: 0.95는 사실상 같은 단어의 표기 차이(대소문자, 띄어쓰기, 한/영)에 해당합니다.
   "Java"와 "JavaScript"처럼 접두사가 같은 단어들은 0.95 미만일 것으로 예상해
   false positive를 막습니다. 다만 실제 bge-m3 임베딩 공간에서 검증은 미완으로,
   운영 로그의 병합 케이스를 보며 조정할 계획입니다.
```

---

## 향후 계획

| 항목 | 우선순위 | 비고 |
|------|----------|------|
| WebSocket 실시간 추출 완료 알림 | 중간 | 현재 폴링(2초×6회) → Push 방식으로 개선 |
| Cross-Encoder Reranker | 낮음 | 16GB VRAM 여유 생길 때 RRF 대체 |
| F-3 threshold 실측 검증 | 중간 | 운영 로그에서 자동 병합 케이스 수집 후 조정 |
| URL/YouTube/Notion 입력 채널 추가 | 낮음 | Extract/Search 고도화 이후 단계 |
Q: 벡터 검색과 그래프 검색 결과를 어떻게 합쳤나요?
A: 두 결과를 RankedContext로 통합 후 score 기준 정렬 → 중복 제거 → 토큰 예산 내 상위 항목만 선택.
   벡터 검색은 pgvector cosine similarity 실측값을 쓰고,
   그래프는 hop 수에 따라 0.7/0.6/0.4 고정값을 임시 할당했습니다.
   Cross-Encoder Reranker가 이상적이지만 16GB VRAM에서 Qwen 14B와 동시 실행 부담으로 보류.

Q: 토큰 제한은 어떻게 처리했나요?
A: 전체 컨텍스트 2,000 토큰 예산을 벡터/그래프 각 1,000씩 배분.
   토큰 추정은 문자 수 / 2 (한국어 rough estimate).
   score 낮은 항목부터 제거합니다.
   정밀 카운팅은 tiktoken 도입이 필요하나 현재 규모에서 오차 허용 가능합니다.
```
```
