# MindGraph-AI: 개인형 지식 그래프 AI 에이전트

> 파편화된 개인 지식을 구조화하여 로컬 LLM의 맥락 이해를 극대화하는 시스템.
> 16GB VRAM 환경에서 클라우드 API 없이 동작하는 것이 핵심 목표.

---

## 프로젝트 구조

```
mindgraph-ai/
├── CLAUDE.md                    ← 이 파일
├── PROGRESS_SUMMARY.md          # 고도화 진행 현황 기록
├── docker-compose.yml           # PostgreSQL, Ollama, RabbitMQ, Neo4j
├── manage.ps1                   # Windows PowerShell 오케스트레이션 스크립트
│
└── MindGraph/                   # Spring Boot 애플리케이션 루트
    ├── build.gradle             # Java 17, Spring Boot 3.2.0, LangChain4j 0.31.0
    ├── settings.gradle
    ├── gradlew / gradlew.bat
    ├── watched-files/           # FileWatcher 감시 대상 디렉토리
    └── src/
        ├── main/
        │   ├── resources/
        │   │   └── application.properties
        │   └── java/org/mg/mindgraph/
        │       ├── MindGraphApplication.java
        │       │
        │       ├── controller/
        │       │   └── MindGraphController.java      # REST API 진입점
        │       │       # POST /api/graph/extract  → 비동기 지식 추출 (202)
        │       │       # GET  /api/graph/search   → 벡터 유사도 검색
        │       │       # POST /api/graph/ask      → RAG 기반 답변 생성
        │       │
        │       ├── ai/
        │       │   └── KnowledgeExtractor.java       # @AiService — LLM 지식 추출 인터페이스
        │       │       # 입력 텍스트 → GraphData(nodes, edges) JSON 추출
        │       │       # Ollama(Qwen 2.5 14B) 호출, temperature=0.0
        │       │
        │       ├── service/
        │       │   ├── MindGraphService.java         # ★ RAG 오케스트레이터
        │       │   │   # ask() → 벡터검색 + 그래프검색 (CompletableFuture 병렬)
        │       │   │   #       → 컨텍스트 병합 → LLM 답변 생성
        │       │   │   # searchGraph() → 키워드 추출 → 엔티티/시계열 분리
        │       │   │   #               → Batch 노드 조회 → JOIN FETCH 엣지 → 필터링
        │       │   │   # extractKeywords() → LLM에 키워드 추출 위임 → 정규식 JSON 파싱
        │       │   │
        │       │   ├── SearchService.java            # pgvector 코사인 유사도 검색
        │       │   │   # JdbcTemplate + native SQL (embedding <=> ?::vector)
        │       │   │
        │       │   ├── GraphService.java             # 지식 그래프 저장 (Find or Create)
        │       │   │   # processNodes() → findByNameAndType() or save()
        │       │   │   # processEdges() → 중복 확인 후 저장
        │       │   │   # syncToNeo4j() — Neo4jClient MERGE, 독립 try-catch
        │       │   │
        │       │   ├── VectorService.java            # 텍스트 → 임베딩 → pgvector 저장
        │       │   │   # mxbai-embed-large 모델, JdbcTemplate INSERT
        │       │   │
        │       │   ├── ExtractionPublisher.java      # RabbitMQ 메시지 발행
        │       │   ├── ExtractionListener.java       # RabbitMQ 소비 + DLQ
        │       │   │   # extract → saveGraph → embedAndSave (3단계 파이프라인)
        │       │   │   # Spring AMQP 자동 재시도 (3회) + Dead Letter Queue
        │       │   │
        │       │   ├── FileWatcherService.java       # WatchService 기반 파일 감지
        │       │   │   # watched-files/ 디렉토리 CREATE/MODIFY → RabbitMQ 발행
        │       │   │
        │       │   └── NotionSyncService.java        # Notion API 연동 (비활성화)
        │       │
        │       ├── config/
        │       │   ├── LangChainConfig.java          # Ollama Chat + Embedding 모델 빈
        │       │   └── rabbitmq/RabbitMQConfig.java   # Exchange, Queue, DLQ 설정
        │       │
        │       ├── entity/
        │       │   ├── Node.java                     # JPA 엔티티 (name, type, description)
        │       │   └── Edge.java                     # JPA 엔티티 (source→target, relation)
        │       │
        │       ├── repository/
        │       │   ├── NodeRepository.java           # findByNameIn() — Batch 조회
        │       │   └── EdgeRepository.java           # findEdgesByNodeNamesIn() — JOIN FETCH
        │       │
        │       ├── dto/
        │       │   ├── GraphData.java                # record (nodes[], edges[])
        │       │   └── SearchResult.java             # record (content, score)
        │       │
        │       ├── neo4j/entity/
        │       │   └── KnowledgeNode.java            # Neo4j 엔티티 (Phase B 완료)
        │       │
        │       └── scheduler/
        │           └── NotionSyncScheduler.java      # 비활성화 상태
        │
        └── test/java/org/mg/mindgraph/
            └── MindGraphApplicationTests.java        # 기본 컨텍스트 로드 테스트만 존재
```

---

## 데이터 흐름

```
[데이터 수집]
  API (POST /extract) ──┐
  FileWatcher ──────────┤
  (Notion — 비활성화) ──┘
          │
          ▼
  [RabbitMQ] mindgraph.extraction.queue
          │                        실패 3회 →  DLQ
          ▼
  [ExtractionListener]
          │
          ├── KnowledgeExtractor.extract(text)  ← LLM 호출 ①: 지식 추출
          │       → GraphData { nodes[], edges[] }
          │
          ├── GraphService.saveGraph()           ← PostgreSQL 저장
          │
          └── VectorService.embedAndSave()       ← LLM 호출 ②: 임베딩 생성
                  → pgvector INSERT

[질문 응답]
  POST /ask { question }
          │
          ├── SearchService.search()             ← 임베딩 생성 → pgvector 코사인 검색
          │       (LLM 호출 ③: 질문 임베딩)
          │
          ├── MindGraphService.searchGraph()
          │       ├── extractKeywords()           ← LLM 호출 ④: 키워드 추출
          │       ├── nodeRepository.findByNameIn()      (Batch)
          │       ├── edgeRepository.findEdgesByNodeNamesIn()  (JOIN FETCH)
          │       └── 시계열 필터링 (메모리 상)
          │
          └── RAG Prompt + LLM 호출 ⑤: 최종 답변 생성
```

IMPORTANT: 하나의 질문 응답에 LLM이 최대 3회 호출됨 (임베딩 + 키워드 추출 + 답변 생성).
지식 수집 시에도 2회 호출 (지식 추출 + 임베딩).
→ 이것이 LLM-OPT 프록시 연동의 핵심 동기.

---

## LLM 호출 경로 (LLM-OPT 연동 관점)

| # | 호출 위치 | 모델 | 용도 | 프록시 대상 |
|---|----------|------|------|------------|
| ① | KnowledgeExtractor.extract() | Qwen 2.5 14B (Chat) | 지식 추출 | ✅ 캐싱 가치 높음 (동일 텍스트 재수집) |
| ② | VectorService.embedAndSave() | mxbai-embed-large (Embedding) | 벡터 저장 | ❌ 프록시 우회 (입력마다 다름) |
| ③ | SearchService.search() | mxbai-embed-large (Embedding) | 질문 벡터화 | ❌ 프록시 우회 |
| ④ | MindGraphService.extractKeywords() | Qwen 2.5 14B (Chat) | 키워드 추출 | ✅ 캐싱 가치 높음 (유사 질문) |
| ⑤ | MindGraphService.ask() (RAG Prompt) | Qwen 2.5 14B (Chat) | 최종 답변 | ✅ 캐싱 가치 높음 (유사 질문 + 동일 컨텍스트) |

**연동 원칙:** Chat 모델만 LLM-OPT 프록시 경유, Embedding 모델은 Ollama 직접 호출 유지.

```properties
# application.properties — Phase A 연동 시 변경
langchain4j.ollama.chat-model.base-url=http://localhost:8000       # LLM-OPT 프록시
langchain4j.ollama.embedding-model.base-url=http://localhost:11434  # Ollama 직접 (변경 없음)
```

---

## 현재 상태

| 항목 | 상태 | 비고 |
|------|------|------|
| 핵심 파이프라인 (수집→추출→저장→검색→답변) | ✅ | 안정적 동작 |
| 비동기 메시지 큐 (RabbitMQ + DLQ) | ✅ | 재시도 3회 + Dead Letter Queue |
| 다중 채널 수집 (API + FileWatcher) | ✅ | |
| N+1 해결 (Batch + JOIN FETCH) | ✅ | DB IO ~80% 절감 |
| 시계열 맥락 분리 (Entity vs Temporal) | ✅ | 연도 필터링 |
| 개발환경 자동화 (manage.ps1) | ✅ | Wait-for-it + GPU 워밍업 |
| 단위 테스트 | ✅ | → .claude/SESSION_STATE.md 참조 |
| Neo4j 도입 | ✅ Phase B 완료 | PostgreSQL+Neo4j 동기화, 2-hop RAG |
| Notion 연동 | ⏸️ | 네트워크 이슈로 비활성화 |
| LLM-OPT 연동 | ✅ | Phase A 완료 (2026-03-03, L1 0.43ms) |
| React UI (mindgraph-ui) | ✅ | 2026-03-19 UX 전면 고도화 완료 |

---

## 코딩 규칙

### Java
- Java 17, Spring Boot 3.2.0, Gradle
- Lombok 적극 활용: `@RequiredArgsConstructor`, `@Slf4j`, `@Getter`, `@Builder`
- 엔티티 생성자: `@NoArgsConstructor(access = AccessLevel.PROTECTED)` + `@Builder`
- record 사용: DTO는 가능하면 record로 (GraphData, SearchResult)
- 패키지 구조: `org.mg.mindgraph.{layer}` — ai, service, config, entity, repository, dto, controller, scheduler

### LLM 관련
- LangChain4j `@AiService` 인터페이스 기반 호출
- LLM 응답 파싱 시 정규식 방어 코드 필수 (LLM은 비정형 응답 가능)
- 프롬프트는 영문 작성 (지시 이행률 향상), 응답은 한국어 요청
- `temperature=0.0` (지식 추출), `temperature=0.2` (답변 생성, application.properties)

### DB
- JPA 연관관계: `@ManyToOne(fetch = FetchType.LAZY)` 기본
- N+1 방지: `findByNameIn()` (Batch), `JOIN FETCH` (JPQL)
- pgvector: native SQL + JdbcTemplate (JPA로 커버 불가)
- Neo4j: Spring Data Neo4j 6.x (`@Node`, `@RelationshipProperties`)

### 비동기 처리
- 메시지 큐: RabbitMQ (Spring AMQP)
- 병렬 검색: `CompletableFuture.supplyAsync()` (벡터 + 그래프 동시 조회)
- 파일 감지: `WatchService` + `ExecutorService` (별도 스레드)

### 에러 처리
- RabbitMQ: 자동 재시도 3회 (2s → 4s → 8s) + DLQ 격리
- LLM 파싱 실패: `Collections.emptyList()` 반환 (graceful degradation)
- 엣지 저장 실패: 로그 경고 후 건너뛰기 (노드 없는 경우)

---

## 인프라 (Docker Compose)

| 서비스 | 이미지 | 포트 | 용도 |
|--------|--------|------|------|
| db | pgvector/pgvector:pg16 | 5432 | PostgreSQL + pgvector |
| ollama | ollama/ollama:latest | 11434 | LLM 추론 (GPU) |
| rabbitmq | rabbitmq:3-management | 5672, 15672 | 메시지 큐 |
| neo4j | neo4j:latest | 7474, 7687 | 그래프 DB |

### GPU 설정
```yaml
# docker-compose.yml — Ollama 서비스
deploy:
  resources:
    reservations:
      devices:
        - driver: nvidia
          count: 1
          capabilities: [gpu]
```

IMPORTANT: Ollama 컨테이너는 LLM-OPT와 공유.
LLM-OPT 연동 시 MindGraph의 Chat 트래픽은 프록시(:8000)를 경유하지만,
Ollama(:11434)는 이 docker-compose에서 관리.

---

## 자주 쓰는 명령어

```bash
# 인프라 기동 (Docker)
docker compose up -d

# 인프라 기동 (PowerShell 오케스트레이션 — Windows에서)
.\manage.ps1 -Mode Start        # Docker + Wait-for-it + GPU 워밍업
.\manage.ps1 -Mode Stop
.\manage.ps1 -Mode Logs -Service ollama
.\manage.ps1 -Mode Backup

# Spring Boot 실행
cd MindGraph && ./gradlew bootRun

# 테스트
cd MindGraph && ./gradlew test

# 빌드
cd MindGraph && ./gradlew build

# API 테스트
curl -X POST http://localhost:8080/api/graph/extract \
  -H "Content-Type: application/json" \
  -d '"Docker는 컨테이너 가상화 기술이다."'

curl -X POST http://localhost:8080/api/graph/ask \
  -H "Content-Type: application/json" \
  -d '{"question": "Docker란 무엇인가?"}'

curl "http://localhost:8080/api/graph/search?query=Docker"
```

---

## 환경변수 / 설정 (application.properties)

```properties
# 서버
server.port=8080

# PostgreSQL
spring.datasource.url=jdbc:postgresql://localhost:5432/mindgraph_db
spring.datasource.username=mindgraph_user
spring.datasource.password=mindgraph_password

# JPA
spring.jpa.hibernate.ddl-auto=update
spring.jpa.show-sql=true

# Ollama — Chat 모델 (LLM-OPT 연동 시 base-url만 변경)
langchain4j.ollama.chat-model.base-url=http://localhost:11434
langchain4j.ollama.chat-model.model-name=qwen2.5:14b
langchain4j.ollama.chat-model.temperature=0.2

# Ollama — Embedding 모델 (프록시 우회, 직접 호출 유지)
langchain4j.ollama.embedding-model.base-url=http://localhost:11434
langchain4j.ollama.embedding-model.model-name=mxbai-embed-large

# RabbitMQ
spring.rabbitmq.host=localhost
spring.rabbitmq.port=5672
spring.rabbitmq.listener.simple.retry.enabled=true
spring.rabbitmq.listener.simple.retry.max-attempts=3
spring.rabbitmq.listener.simple.retry.initial-interval=2000ms
spring.rabbitmq.listener.simple.retry.multiplier=2.0
spring.rabbitmq.listener.simple.default-requeue-rejected=false

# Neo4j
spring.neo4j.uri=bolt://localhost:7687
spring.neo4j.authentication.username=neo4j
spring.neo4j.authentication.password=password

# FileWatcher
mindgraph.file-watcher.directory=./watched-files
```

IMPORTANT: `application.properties`는 `.gitignore`에 포함되어 있음.
민감 정보(DB 비밀번호 등)가 들어있으므로 Git에 커밋되지 않음.
새 환경 세팅 시 위 내용을 참고하여 직접 생성해야 함.

---

## 핵심 설계 결정

| 결정 | 대안 | 선택 근거 |
|------|------|-----------|
| pgvector (PostgreSQL 내장) | Pinecone, Weaviate | 별도 벡터 DB 운영 불필요, JPA 트랜잭션과 통합 |
| RabbitMQ 메시지 큐 | 동기 처리 | LLM 호출 6초+ → API 즉시 응답(202) + 백그라운드 처리 |
| DLQ (Dead Letter Queue) | 단순 에러 로그 | LLM/DB 일시 장애 시 데이터 유실 방지 |
| Batch 노드 조회 (findByNameIn) | 개별 조회 루프 | N+1 쿼리 → 1회 배치 조회로 DB IO ~80% 절감 |
| JOIN FETCH 엣지 조회 | Lazy 로딩 | 엣지 + 양쪽 노드를 한 쿼리로 — N+1 원천 차단 |
| 키워드 추출 프롬프트 영문화 | 한국어 프롬프트 | LLM 지시 이행률(Instruction Following) 향상 |
| 정규식 JSON 파싱 (\\[.*?\\]) | Jackson 직접 파싱 | LLM이 부연 설명 추가 시 파싱 붕괴 방지 |
| CompletableFuture 병렬 검색 | 순차 실행 | 벡터 + 그래프 검색 독립 → 병렬 실행으로 응답 시간 단축 |
| 시계열(연도) 분리 필터링 | 단순 엔티티 취급 | "2026년" 같은 키워드가 무의미한 노드가 되는 문제 해결 |

---

## 트러블슈팅 기록

| 문제 | 원인 | 해결 |
|------|------|------|
| LLM 키워드 추출 시 부연 설명으로 파싱 붕괴 | LLM이 JSON 외 텍스트 추가 | 정규식 `\\[.*?\\]` 추출 + @SystemMessage 영문화 |
| 엔티티 추출 후 N+1 쿼리 병목 | 단일 노드 반복 조회 | `findByNameIn` 배치 + `findEdgesByNodeNamesIn` JOIN FETCH |
| "2026년" 키워드가 무의미한 노드 생성 | 시계열 키워드를 엔티티로 처리 | Entity/Temporal 분류 → 관계 데이터 내 시계열 필터링 |
| Notion API 라이브러리 네트워크 이슈 | 외부 Maven 저장소 접근 불가 | 기능 비활성화, jitpack 의존성 주석 처리 |

---

## 참조 문서

| 문서 | 용도 |
|------|------|
| `docs/PHASE_TRACKER.md` | Phase별 진행 상황, 체크리스트, 면접 포인트, 블로그 소재 |
| `docs/PROJECT_CONTEXT.md` | 아키텍처 스펙, LLM 호출 경로, 쿼리 패턴, 면접 스토리 |
| `docs/TECH_KNOWLEDGE.md` | 기술 지식 정리 (LangChain4j, pgvector, JPA, RabbitMQ 등) |
| `docs/GROWTH.md` | 이 프로젝트에서 실제로 배운 것들 |
| `docs/blog/` | 블로그 포스트 초안 3편 |
| `PROGRESS_SUMMARY.md` | 이력서/면접용 고도화 현황 기록 |
| `../CLAUDE.md` (상위) | 통합 워크스페이스 컨텍스트 |
| `../llm-opt/docs/PHASE_TRACKER.md` | LLM-OPT Phase 진행 + 연동 계획 |
