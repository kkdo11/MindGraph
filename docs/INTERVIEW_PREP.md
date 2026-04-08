# MindGraph-AI 면접 준비

> 실측 수치는 "예상"과 "실측"을 반드시 구분해서 답변할 것.
> 과장 금지. 한계도 솔직히 인정.
> 마지막 업데이트: 2026-03-08 (Phase B 반영)

---

## 30초 스토리 (인트로)

> "파편화된 개인 지식을 로컬 LLM이 활용할 수 있도록 구조화하는 지식 그래프 시스템입니다.
> 텍스트를 입력하면 Qwen 2.5 14B가 노드/관계를 추출해 PostgreSQL과 Neo4j에 저장하고,
> 질문 시 벡터 검색 + 2-hop 그래프 탐색으로 컨텍스트를 구성해 답변합니다.
> 운영 중 발견한 LLM 반복 호출 비효율을 해결하기 위해 LLM-OPT 프록시와 연동하여
> 동일 질문 응답을 6초 → 0.43ms로 줄였습니다."

---

## 핵심 실측 수치 요약

| 항목 | 수치 | 비고 |
|------|------|------|
| LLM 직접 호출 레이턴시 | 평균 6,065ms | Qwen 2.5 14B (670~11,059ms) |
| LLM-OPT L1 캐시 히트 | 0.43ms | 실 연동 실측 (2026-03-03) |
| DB IO 절감 (N+1 해결) | ~80% | show-sql 로그 쿼리 수 비교 |
| 단위 테스트 | 50개 통과 | Mockito 49 + contextLoads 1 |
| RabbitMQ 재시도 | 3회 (2s→4s→8s) | 지수 백오프, DLQ 격리 |

---

## Phase 1 — Core RAG Pipeline

**Q: LangChain4j @AiService가 어떻게 동작하나요?**
> `@AiService` 어노테이션 인터페이스를 선언하면 LangChain4j가 런타임에 프록시를 생성합니다.
> `@SystemMessage`로 시스템 프롬프트, `@UserMessage`로 사용자 입력을 정의하면
> Spring 빈으로 자동 주입됩니다.
> 테스트 시 `ChatLanguageModel`을 Mock하면 실제 LLM 없이 단위 테스트가 가능합니다.

**Q: pgvector를 JPA 없이 JdbcTemplate으로 쓴 이유는?**
> pgvector의 `<=>` (cosine distance) 연산자는 표준 SQL이 아닙니다.
> Spring Data JPA/JPQL에서 지원하지 않아 native SQL을 직접 실행하는
> JdbcTemplate을 사용했습니다. 저장/조회 모두 동일한 이유입니다.

**Q: RabbitMQ를 도입한 이유는?**
> LLM 추론이 평균 6초입니다. 동기 처리하면 API가 6초를 기다립니다.
> RabbitMQ로 처리 요청을 큐에 넣고 202 Accepted를 즉시 반환합니다.
> 사용자 경험(즉시 응답)과 시스템 안정성(백프레셔) 모두 개선됩니다.

**Q: GraphData를 record로 정의한 이유는?**
> DTO는 LLM 응답을 받아 전달하는 역할만 합니다. 불변 객체가 적합합니다.
> `record`는 `equals`/`hashCode`/`toString` 자동 생성, 보일러플레이트 없음,
> Jackson JSON 역직렬화도 지원합니다.

---

## Phase 2 — 안정성 강화 + 성능 최적화

**Q: N+1 문제를 어떻게 발견하고 해결했나요?**
> `spring.jpa.show-sql=true` 로그에서 키워드 N개에 SELECT가 N+1번 나가는 것을 확인했습니다.
> `findByNameIn(List)`으로 배치 조회로 바꾸고, 엣지는 JOIN FETCH JPQL로
> 연관 노드를 한 번에 가져오도록 수정해 DB IO를 약 80% 줄였습니다.

**Q: DLQ(Dead Letter Queue)를 왜 도입했나요?**
> LLM 호출 실패 시 메시지를 잃고 싶지 않았습니다.
> `default-requeue-rejected=false`로 실패 메시지를 DLQ에 격리하고,
> Spring AMQP retry를 3회(2s→4s→8s 지수 백오프)로 설정했습니다.
> 일시적 LLM/DB 장애는 재시도로, 영구적 오류는 DLQ에서 수동 처리합니다.
> **함정:** `default-requeue-rejected=true`(기본값)를 그대로 두면 LLM 오류 시 메시지가 큐 앞으로 복귀해 무한 루프가 발생합니다. 이 버그를 운영 중에 발견해 수정했습니다.

**Q: CompletableFuture 병렬 검색을 구현한 이유는?**
> 벡터 검색(pgvector)과 그래프 검색(PostgreSQL)은 완전히 독립적입니다.
> 순차 실행 시 A+B 시간, `CompletableFuture.supplyAsync()` 병렬 실행 시 `max(A, B)` 시간.
> 두 검색을 동시에 시작하고 `join()`으로 결과를 모읍니다.

**Q: Temporal Context 분리가 왜 필요했나요?**
> "2026년 Manus AI 설립"에서 "2026"을 엔티티로 처리하면 무의미한 노드가 생깁니다.
> 또 "2023년 GDP" 검색 시 "2026년 GDP" 데이터가 함께 노출됩니다.
> 4자리 숫자(`\d{4}`)로 연도를 분리해 엔티티 검색에서 제외하고,
> 텍스트 필터링에만 사용해 두 문제를 동시에 해결했습니다.

---

## Phase 3 — 단위 테스트

**Q: pgvector를 쓰는 Service를 어떻게 단위 테스트했나요?**
> pgvector의 `<=>` 연산자는 H2에서 동작하지 않습니다.
> `JdbcTemplate`을 Mock으로 교체하고, `ArgumentCaptor`로 실행된 SQL 문자열에
> `"<=>"`와 `"LIMIT 5"`가 포함되는지 검증했습니다.

**Q: ExtractionListener 파이프라인 순서를 어떻게 테스트했나요?**
> Mockito의 `InOrder`를 사용했습니다.
> `extract → saveGraph → embedAndSave` 순서가 다르면 테스트가 실패합니다.
> 중간 예외 시 이후 단계가 `never()`로 호출되지 않음도 함께 검증합니다.

**Q: @DataJpaTest에서 Neo4j 설정 충돌 이유는?**
> `build.gradle`에 `spring-boot-starter-data-neo4j`가 포함되어 있어
> 테스트 자동 설정이 Neo4j 연결을 시도합니다.
> `excludeAutoConfiguration`으로 `Neo4jAutoConfiguration`, `Neo4jDataAutoConfiguration`,
> `Neo4jRepositoriesAutoConfiguration`을 명시적으로 제외했습니다.

---

## Phase A — LLM-OPT 연결

**Q: MindGraph를 LLM-OPT 프록시에 연결할 때 가장 큰 문제가 뭐였나요?**
> 두 가지가 있었습니다.
> 첫 번째, LangChain4j의 OllamaChatModel이 Ollama 네이티브 포맷(`/api/chat`)을 쓰므로
> LLM-OPT에 Ollama 호환 엔드포인트가 필요했습니다. LLM-OPT 측에서 구현했습니다.
>
> 두 번째, `LangChainConfig.java`에서 `chat-model.base-url`을 chat/embedding 공용으로 사용하고 있었는데,
> chat만 `:8000`으로 바꾸자 embedding 요청도 `:8000`으로 향해 404가 발생했습니다.
> `@Value("${langchain4j.ollama.embedding-model.base-url}")` 필드를 별도로 추가해 분리했습니다.

**Q: Embedding 요청은 왜 LLM-OPT를 우회하나요?**
> Embedding 입력은 매번 다른 원문 텍스트입니다. 동일 입력이 거의 없어 캐싱 이점이 없습니다.
> 반면 Chat 요청(지식 추출, 키워드 추출, 답변 생성)은 동일하거나 유사한 질문이 반복됩니다.
> 연동 원칙: 캐싱 효과가 있는 Chat만 프록시 경유, Embedding은 Ollama 직접 호출.

**Q: 실측 결과는?**
> 동일 텍스트 재추출 시 5,510ms → 0.43ms 실측했습니다 (2026-03-03).
> Redis에 `llm:cache:*` 5개(L1), `llm:vec:*` 5개(L2), `llm:quota:*` 1개(할당량) 저장 확인.

---

## Phase B — Neo4j 하이브리드 RAG

**Q: Neo4j와 PostgreSQL을 동시에 쓰는 이유는?**
> PostgreSQL은 정형 데이터 저장과 pgvector 기반 코사인 유사도 검색에 최적화되어 있습니다.
> Neo4j는 2-hop 이상의 관계 탐색에 강점이 있습니다.
> 지식이 쌓일수록 Neo4j의 2-hop 탐색이 RAG 컨텍스트를 자동 확장합니다.
> 예: "Docker" 검색 → `Docker → 컨테이너 가상화 → Kubernetes` 경로를 한 쿼리로 가져옵니다.

**Q: Neo4j 장애 시 서비스가 중단되지 않나요?**
> `saveGraph()`와 `searchGraph()` 모두 독립 `try-catch`로 분리했습니다.
> Neo4j 실패 시 로그만 남기고 PostgreSQL 결과만 반환합니다. 데이터 유실도 없습니다.
> Neo4j는 "있으면 더 좋은" 추가 컨텍스트로 설계했습니다.

**Q: Neo4jRepository 대신 Neo4jClient를 쓴 이유는?**
> 2-hop Cypher 쿼리에서 관계 속성(relation 텍스트)을 포함한 문자열을 직접 반환해야 해서
> `@Query`의 리턴 타입 제약을 피하기 위해 Neo4jClient를 사용했습니다.
> MERGE 기반 저장도 Neo4jClient로 제어가 명확합니다.
> 부수 효과로 `@DataJpaTest`에서 `neo4jTemplate` 충돌도 해결됐습니다.

**Q: @DataJpaTest 10개가 실패했는데 어떻게 해결했나요?**
> `KnowledgeNodeNeo4jRepository`가 컴포넌트 스캔에 잡혀 `@DataJpaTest` 컨텍스트에서
> `neo4jTemplate` 빈을 요구했습니다. Neo4jClient 직접 사용으로 Repository가 불필요해졌고,
> 삭제하자 50개 테스트 전체 통과했습니다.

---

## 설계 Trade-off 요약

| 결정 | 대안 | 이유 |
|------|------|------|
| pgvector (PostgreSQL 내장) | Pinecone, Weaviate | 별도 Vector DB 운영 불필요, JPA 트랜잭션 통합 |
| RabbitMQ 메시지 큐 | 동기 처리 | LLM 6초+ → API 즉시 202 응답 |
| DLQ + 재시도 3회 | 단순 에러 로그 | 일시 장애 시 데이터 유실 방지 |
| findByNameIn (Batch) | 개별 findByName 루프 | N+1 → 1회 배치 (DB IO ~80% 절감) |
| JOIN FETCH JPQL | Lazy 로딩 | 엣지 + 양쪽 노드 한 쿼리, N+1 원천 차단 |
| 정규식 JSON 파싱 | Jackson 직접 파싱 | LLM 부연 설명 추가 시 파싱 붕괴 방지 |
| CompletableFuture 병렬 검색 | 순차 실행 | 벡터+그래프 독립 → 응답 시간 단축 |
| 연도 Temporal 분리 | 단순 엔티티 취급 | 무의미한 연도 노드 + 시계열 혼용 동시 해결 |
| Neo4jClient 직접 사용 | Neo4jRepository | 복잡한 Cypher 리턴 타입 제약 없음 |
| Embedding 직접 호출 | 프록시 경유 | 입력이 매번 다른 원문, 캐싱 이점 없음 |

---

## 면접 직전 체크리스트

```
✅ "0.43ms" → "LLM-OPT L1 캐시 히트 시, 실 연동 실측 (2026-03-03)"
✅ "80% DB IO 절감" → "show-sql 로그 쿼리 수 비교 기준, 정밀 측정 아님"
✅ "6초" → "Qwen 2.5 14B Ollama 추론, 670ms~11,059ms 편차 큼"
✅ "50개 테스트" → "Mockito 49개 + contextLoads 1개"
```

강조할 차별점:

```
1. 실제 운영(MindGraph) 중 문제 발견 → 직접 해결(LLM-OPT 설계)
2. Java(Spring) ↔ Python(FastAPI) 크로스 스택 실 연동 (base-url 한 줄 변경)
3. N+1 발견 → Batch + JOIN FETCH → 80% 절감 (측정 기반 의사결정)
4. DLQ 무한 루프 버그를 운영 중 발견하고 수정한 경험
5. Neo4j 장애가 PostgreSQL에 영향 없도록 독립 try-catch 설계
```
