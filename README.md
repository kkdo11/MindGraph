# MindGraph-AI

> 파편화된 개인 지식을 구조화하여 로컬 LLM의 맥락 이해를 극대화하는 지식 그래프 AI 에이전트.
> 16GB VRAM 환경에서 클라우드 API 없이 동작하는 완전 로컬 RAG 시스템.

---

## 개요

메모, 문서 등 흩어진 개인 지식을 LLM이 자동으로 지식 그래프(노드+관계)로 변환하고,
질문을 받으면 벡터 검색 + 그래프 탐색을 병렬로 실행하여 맥락 있는 답변을 생성합니다.

### 핵심 지표

| 항목 | 수치 |
|------|------|
| LLM-OPT 연동 후 캐시 히트 응답 | 5,510ms → **0.43ms** (12,800x) |
| N+1 쿼리 최적화 DB IO 절감 | **약 80%** |
| 단위 테스트 | **88개** |
| RAG 컨텍스트 최대 예산 | 4,000자 (중복 제거 + 점수순 절단) |

---

## 아키텍처

```
[데이터 수집]
  API (POST /extract) ──┐
  File Watcher ─────────┤
                        ▼
               [RabbitMQ Queue]
                  실패 → DLQ (자동 재시도 3회)
                        │
                        ▼
            [ExtractionListener]
               │         │
               ▼         ▼
        KnowledgeExtractor  VectorService
        (LLM 지식 추출)     (임베딩 저장)
               │
               ▼
        GraphService
        (PostgreSQL + Neo4j 동기 저장)

[질문 응답]
  POST /ask ──────────────────────────────┐
                                          ▼
                         ┌──────────────────────────────┐
                         │  pgvector 코사인 유사도 검색  │  (병렬)
                         │  Neo4j 2-hop 그래프 탐색     │
                         └──────────────────────────────┘
                                          │
                                   RRF 통합 + 중복 제거
                                          │
                                   RAG 프롬프트 생성
                                          │
                                   Qwen 2.5 14B 답변
```

---

## 기술 스택

| 영역 | 기술 |
|------|------|
| 언어 / 프레임워크 | Java 17, Spring Boot 3.2, Gradle |
| LLM 연동 | LangChain4j 0.31, Ollama (Qwen 2.5 14B) |
| 임베딩 | bge-m3 (Ollama, 1024차원, 8192 토큰) |
| 관계형 DB | PostgreSQL 16 + pgvector (벡터 검색) |
| 그래프 DB | Neo4j (2-hop 관계 탐색) |
| 메시지 큐 | RabbitMQ + Dead Letter Queue |
| 프론트엔드 | React 18, TypeScript, Vite, Cytoscape.js, Zustand, TailwindCSS |
| 인프라 | Docker Compose, GPU (RTX 4080 Super 16GB) |

---

## 주요 기능

### RAG 파이프라인
- **다중 채널 수집**: REST API + 로컬 파일 감지(WatchService) 동시 지원
- **비동기 처리**: RabbitMQ 큐 → 자동 재시도(3회) + DLQ 격리로 데이터 유실 없음
- **하이브리드 검색**: pgvector 코사인 유사도 + Neo4j 2-hop 그래프 탐색 병렬 실행
- **RRF 통합**: 이질적인 두 검색 결과를 Reciprocal Rank Fusion으로 통합
- **한/영 교차 검색**: bge-m3 노드 이름 임베딩으로 "도커" ↔ "Docker" 교차 검색

### 성능 최적화
- **N+1 제거**: `findByNameIn` 배치 조회 + `JOIN FETCH` 엣지 조회
- **LLM-OPT 연동**: 동일/유사 쿼리 캐싱 프록시로 L1 히트 0.43ms 실측
- **병렬 검색**: `CompletableFuture`로 벡터/그래프 검색 동시 실행
- **컨텍스트 예산**: 점수순 정렬 + 중복 제거 + 4,000자 절단

### 프론트엔드
- **Cytoscape.js** 기반 인터랙티브 지식 그래프 시각화
- 노드 선택 시 상세 패널 / 미선택 시 통계 대시보드 (타입별 분포)
- Toast 알림, 히스토리, 로컬스토리지 세션 유지

---

## 시작하기

### 사전 요구사항

- Docker + Docker Compose
- NVIDIA GPU (16GB+ VRAM 권장) + CUDA 드라이버
- Java 17+, Gradle

### 1. 환경변수 설정

```bash
cp .env.example .env
# .env 편집: DB 비밀번호, Neo4j 비밀번호 등
```

`MindGraph/src/main/resources/application.properties` 생성 (`.env.example` 참조):

```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/mindgraph_db
spring.datasource.username=mindgraph_user
spring.datasource.password=<your-password>
spring.neo4j.uri=bolt://localhost:7687
spring.neo4j.authentication.username=neo4j
spring.neo4j.authentication.password=<your-password>
langchain4j.ollama.chat-model.base-url=http://localhost:11434
langchain4j.ollama.chat-model.model-name=qwen2.5:14b
langchain4j.ollama.embedding-model.model-name=bge-m3
```

### 2. 인프라 기동

```bash
# 전체 스택 원클릭 (권장)
./start.sh

# 또는 직접 기동
docker compose up -d
cd MindGraph && ./gradlew bootRun
```

### 3. LLM 모델 다운로드

```bash
docker exec mindgraph-ollama ollama pull qwen2.5:14b
docker exec mindgraph-ollama ollama pull bge-m3
```

### 4. 프론트엔드 실행

```bash
cd mindgraph-ui
npm install
npm run dev   # http://localhost:5173
```

---

## API

| 메서드 | 경로 | 설명 |
|--------|------|------|
| `POST` | `/api/graph/extract` | 텍스트에서 지식 추출 (비동기, 202) |
| `POST` | `/api/graph/ask` | RAG 기반 질문 응답 |
| `GET` | `/api/graph/search?query=` | 벡터 유사도 검색 |
| `GET` | `/api/graph/nodes` | 전체 노드 목록 |
| `DELETE` | `/api/graph/nodes/{id}` | 노드 삭제 |

```bash
# 지식 추출
curl -X POST http://localhost:8080/api/graph/extract \
  -H "Content-Type: application/json" \
  -d '"Docker는 컨테이너 가상화 기술이다. 2013년 Solomon Hykes가 개발했다."'

# 질문 응답
curl -X POST http://localhost:8080/api/graph/ask \
  -H "Content-Type: application/json" \
  -d '{"question": "Docker는 누가 만들었나요?"}'
```

---

## 포트 맵

| 포트 | 서비스 |
|------|--------|
| 5173 | React UI (Vite) |
| 8080 | MindGraph API (Spring Boot) |
| 11434 | Ollama (LLM) |
| 5432 | PostgreSQL |
| 7474 | Neo4j (HTTP) |
| 7687 | Neo4j (Bolt) |
| 5672 | RabbitMQ |
| 15672 | RabbitMQ Management UI |

---

## 디렉토리 구조

```
mindgraph-ai/
├── MindGraph/                     # Spring Boot 애플리케이션
│   └── src/main/java/org/mg/mindgraph/
│       ├── ai/                    # KnowledgeExtractor (@AiService)
│       ├── service/               # RAG 오케스트레이션, 검색, 그래프, 벡터
│       ├── config/                # LangChain4j, RabbitMQ 빈 설정
│       ├── entity/                # Node, Edge (JPA)
│       ├── neo4j/entity/          # KnowledgeNode (Neo4j)
│       ├── repository/            # Batch 조회, JOIN FETCH
│       ├── dto/                   # GraphData, RankedContext, SearchResult
│       └── controller/            # REST API
├── mindgraph-ui/                  # React 프론트엔드
├── docs/                          # 설계 문서, 블로그 초안, 포트폴리오
├── docker-compose.yml             # PostgreSQL, Ollama, RabbitMQ, Neo4j
├── start.sh / stop.sh             # 원클릭 실행/종료
└── .env.example                   # 환경변수 템플릿
```

---

## 관련 프로젝트

**[llm-opt](https://github.com/kkdo11/llm-opt)** — LLM 추론 트래픽 최적화 프록시

MindGraph 운영 중 발견한 GPU 비효율(동일 질문 반복 추론)을 해결하기 위해 설계한 FastAPI 기반 시맨틱 캐싱 프록시.
`langchain4j.ollama.chat-model.base-url=http://localhost:8000` 한 줄로 연동 가능.
