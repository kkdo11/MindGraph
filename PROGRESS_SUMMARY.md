### MindGraph 프로젝트 고도화 진행 현황 기록 (최신화)

**[프로젝트 개요]**
파편화된 개인 지식(메모, 문서)을 단순 저장을 넘어 지식 그래프로 구조화하여, 로컬 LLM의 맥락 이해도를 극대화하기 위한 개인형 AI 에이전트 구축 프로젝트입니다. 16GB VRAM 환경에서도 클라우드 API 없이 지연 없는 답변을 제공하는 것을 목표로 합니다.

**[담당 역할 (개인 프로젝트)]**
Full-stack AI Engineering & Backend

**[환경 및 역할]**
*   RTX 4080 Super (16GB VRAM) 단일 머신 기반 로컬 LLM(Qwen 2.5 14B) 서빙 및 최적화
*   LangChain4j를 활용한 RAG(Retrieval-Augmented Generation) 파이프라인 설계 및 오케스트레이션
*   다중 채널(API, 로컬 파일) 데이터 수집을 위한 비동기 메시지 큐 시스템 설계 및 구축

**[주요 문제 해결 및 기여]**

*   **개발 환경 자동화 및 오케스트레이션 스크립트 구축:**
    *   여러 Docker 컨테이너(DB, LLM, MQ, Neo4j)로 구성된 복잡한 개발 환경의 설치 및 실행 과정을 단일 명령어로 자동화.
    *   PowerShell 스크립트(`manage.ps1`)를 작성하여, 고정 대기 시간 대신 각 서비스의 포트 가용성을 직접 확인하는 'Wait-for-it' 패턴을 도입, 시스템 기동의 안정성과 속도를 향상시킴.
    *   LLM 및 임베딩 모델을 미리 GPU 메모리에 로드(워밍업)하는 로직을 포함하여, 첫 API 호출 시의 지연 시간을 원천적으로 제거.
    *   환경 변수 분리, 실시간 로그 스트리밍, 관리 대시보드 자동 실행 등 개발 편의성을 극대화하는 다양한 기능을 통합.

*   **안정적인 비동기 데이터 처리 파이프라인 구축:**
    *   API 및 파일 시스템 이벤트를 안정적으로 수집하기 위해 RabbitMQ 기반의 메시지 큐 시스템을 설계 및 도입.
    *   Spring AMQP를 활용하여 데이터 처리 실패 시 자동 재시도(Retry) 및 Dead Letter Queue(DLQ)로의 격리 로직을 구현하여, 일시적인 오류(LLM, DB 등) 발생 시에도 데이터 유실 없는(zero-loss) 견고한 아키텍처를 완성.

*   **다중 채널 데이터 수집 엔진 구현:**
    *   REST API 엔드포인트(`POST /api/graph/extract`)와 로컬 파일 시스템 변경을 실시간으로 감지하는 File Watcher(`WatchService` API)를 동시에 지원하는 다중 채널 데이터 수집 구조를 설계하여, 사용자가 다양한 방식으로 지식을 시스템에 손쉽게 입력할 수 있도록 유연성 확보.

*   **LLM 응답 비정형성 및 파싱 에러 해결:**
    *   키워드 추출 시 LLM의 부연 설명으로 인한 파싱 붕괴 현상 발견
    *   → 정규식 패턴(`[.*? ]`) 도입 및 시스템 메시지 영문화로 지시 이행률(Instruction Following) 극대화 및 파이프라인 안정성 확보

*   **RAG 검색 성능 및 DB 부하 최적화:**
    *   엔티티 추출 후 반복적인 단일 노드 조회로 인한 N+1 쿼리 병목 확인
    *   → `findByNameIn` 기반 배치 조회 및 Lazy Fuzzy Search 로직으로 리팩토링하여 DB IO 오버헤드 약 80% 절감

*   **시계열 맥락(Temporal Context) 인지 능력 고도화:**
    *   "2026년" 등 시계열 키워드를 단순 엔티티로 처리할 때 발생하는 정보 밀도 저하 해결
    *   → 추출 키워드를 Entity와 Temporal로 분리하여 관계 데이터 내 시계열 필터링 로직 구현, 답변 정확도 향상

**[기술 스택]**

*   **AI & Data:** Qwen 2.5 (Ollama), PostgreSQL (pgvector), Neo4j(PoC 진행 중), LangChain4j
*   **Backend:** Java 17, Spring Boot, Spring AMQP, Jackson
*   **Frontend:** React 18, TypeScript, Vite, Cytoscape.js, Zustand, TailwindCSS
*   **DevOps & Automation:** Docker, RabbitMQ, PowerShell

**[UI/UX 전면 고도화 — 2026-03-19]**

*   **React 프론트엔드 버그 수정 및 UX 전면 개선:**
    *   Cytoscape.js 엣지 미표시 버그 해결: PostgreSQL `nodes`/`edges` 테이블이 동일한 IDENTITY 시퀀스를 사용하여 Cytoscape의 통합 ID 네임스페이스에서 충돌 발생 → 엣지 ID에 `'e-'` 접두사 적용으로 근본 해결.
    *   그래프 위치 초기화 버그 해결: `elementKeyRef` 패턴으로 구조 변경 시에만 레이아웃 재실행, `randomize: false` 적용.
    *   필터 검색 미작동 해결: `mergeGraph`(축적) → `setGraph`(교체) 전환으로 검색 시 올바른 뷰 렌더링.
    *   Toast 알림 시스템 신규 구축: Zustand 기반 글로벌 토스트, 3.5초 자동 dismiss, success/error/info 3종.
    *   지식 추출 폴링 도입: 고정 8초 대기 → 2초 간격 최대 6회 폴링으로 응답성 개선.
    *   Cytoscape 줌 컨트롤 추가: 줌인/아웃/전체보기(fit)/레이아웃 재배치 버튼 (캔버스 우하단).
    *   NodeDetail 패널 재설계: 노드 미선택 시 통계 대시보드 (노드/엣지 카운트, 타입별 분포 바 차트, 사용 가이드), 인라인 삭제 확인 UI.
    *   3패널 고정 레이아웃 (Sidebar 320px | Canvas flex-1 | NodeDetail 288px).

**[LLM-OPT 연동 완료 — 2026-03-03]**

*   **MindGraph ↔ LLM-OPT 프록시 연결 완료:**
    *   MindGraph의 모든 Chat 요청(지식 추출, 키워드 추출, RAG 답변)이 LLM-OPT 프록시(`:8000`)를 경유하도록 연결.
    *   동일 질문 재요청 시 응답 시간 5,510ms → 0.43ms (L1 Hash Cache 히트, 약 12,000배 개선).
    *   `LangChainConfig.java` 수정: chat/embedding 모델의 base-url을 분리 (`chatBaseUrl` / `embeddingBaseUrl`). Embedding은 Ollama(`:11434`) 직접 호출 유지.
    *   `application.properties` 변경: `langchain4j.ollama.chat-model.base-url=http://localhost:8000`

**[Context-Aware RAG 고도화 — 2026-03-22]**

*   **벡터+그래프 결과 통합 컨텍스트 조립 (Phase C):**
    *   `RankedContext(content, score, source)` 자료구조 도입으로 이질적인 두 검색 결과를 추상화.
    *   점수 기반 우선순위 정렬 (벡터: cosine similarity, 그래프: PG=0.7 / Neo4j 1-hop=0.6 / 2-hop=0.4).
    *   content 앞 50자 key로 중복 제거 → `MAX_CONTEXT_CHARS=4000` 예산 내 절단.
    *   `buildFullGraphSummary()`에서 `findAll()` 제거 → degree 기준 허브 노드 + 최근 노드 조합으로 OOM 위험 원천 차단.
    *   `SearchService` limit/threshold `@Value` 파라미터화 (하드코딩 제거).
    *   단위 테스트 82개 전체 통과.

*   **한/영 교차 언어 노드 검색 (Phase D — D-1):**
    *   실 운영에서 발견: 한국어 노드("도커")가 영어 키워드("Docker")로 검색되지 않아 RAG 컨텍스트 누락.
    *   `nodes.name_embedding vector(1024)` 컬럼 추가 — bge-m3 멀티링궐 임베딩으로 노드 이름 벡터화.
    *   `findByNameIn()` 정확 매칭 → `findSimilarByNameEmbedding()` 코사인 유사도 검색으로 대체 (threshold=0.6).
    *   임베딩 실패 시 독립 try-catch로 JPA 트랜잭션 격리 (Neo4j 동기화와 동일 패턴).
    *   `POST /api/graph/rebuild-node-embeddings` — 기존 노드 일괄 임베딩 마이그레이션 엔드포인트.
    *   실측: "Docker에 대해 알려줘" 질문 → `[지식 그래프]` 태그 포함 응답 확인 ✅

**[Frontend 고도화 — 2026-03-22]**

*   **질문 히스토리 (F-1):** 단일 답변 표시 → `QAItem[]` 대화 스택. 시간 표시, 개별 복사, 전체 삭제 지원.
*   **localStorage 그래프 캐싱 (F-2):** Zustand `persist` 미들웨어로 nodes/edges 영속화. 새로고침 후 그래프 자동 복원.
*   **의미 검색 ↔ 그래프 연동 (F-3):** 벡터 검색 결과 카드 클릭 시 관련 노드 캔버스 포커스 (pan + zoom 1.8x). 인사이트 탭 노드 클릭도 동일 적용.
*   **신규 노드 강조 (F-4):** 추출 완료 후 새로 추가된 노드 노란 테두리 3초 강조.
*   **임베딩 재생성 버튼 (F-5):** 인사이트 탭 "유지 관리" 섹션에서 `rebuild-node-embeddings` 호출 가능.

**[검색 품질 고도화 (Phase F) — 2026-03-24]**

*   **의미 단위 청킹 (F-6a):**
    *   기존 슬라이딩 윈도우(CHUNK_SIZE 매 300자) → `\n\n` 단락 기반 분할로 전면 교체.
    *   소단락 병합(MIN_CHUNK_SIZE=500), 대단락 문장 경계 재분할(fallback), 이전 청크 마지막 문장 오버랩으로 문맥 연속성 보장.
    *   단락 경계를 보존해 검색 정확도 향상 — 문장 중간 절단으로 인한 의미 파편화 제거.

*   **노드 이름 정규화 (F-3):**
    *   지식 추출 시 LLM이 같은 개념을 "Docker", "도커", "docker" 등으로 다르게 추출하여 중복 노드 생성 문제 해결.
    *   2단계 접근: exact name 매칭 실패 시 bge-m3 임베딩 유사도 검색 (threshold=0.95 자동 병합, 0.80~0.95 후보 로그).
    *   `@Value` 기반 임계값 파라미터화 → 운영 로그 기반 튜닝 가능.

*   **Query Expansion + 한/영 언어 불일치 해소 (F-6b):**
    *   RAG 검색 전 키워드 노드의 description을 질문에 prepend → LLM이 더 풍부한 컨텍스트로 검색 벡터 생성.
    *   2단계 매칭: `findByNameIn()` exact match → exact miss 키워드에 대해 `findSimilarByNameEmbedding(threshold=0.7)` semantic fallback.
    *   "도커란?" 질문 시 "Docker" 노드의 description이 query에 자동 포함 → 검색 커버리지 확대.

*   **Neo4j Union 버그 수정 (F-1a) + RRF 동적 스코어링 (F-1c):**
    *   Neo4j 2-hop Cypher UNION 쿼리 파라미터 누락으로 1-hop만 실행되던 버그 수정.
    *   기존 고정 가중치(PostgreSQL 0.7, Neo4j 1-hop 0.6, 2-hop 0.4) → Reciprocal Rank Fusion(`1/(k+rank)`, k=60) 적용.
    *   이질적인 점수 스케일(코사인 유사도 vs 그래프 hop-depth)을 RRF로 정규화 — 순위 기반으로 스케일 불문 통합.

*   **런타임 버그 3종 수정:**
    *   SLF4J `{:.3f}` Python 포맷 → `{}` 수정 (컴파일 통과, 런타임 로그 오출력).
    *   `extractLastSentence()` dead code(`end = i` 루프 내 무효 재할당) 제거.
    *   `expandQuery()` exact miss 키워드 언어 불일치 → semantic fallback 추가.

*   **단위 테스트 88개 통과:** 기존 82개 + 신규 6개 (F-6a 청킹 5개, F-3 자동병합/후보탐지 2개, F-6b semantic fallback 1개).

**[RAG 캐시 오염 해결 — 2026-03-24]**

*   **`ragChatLanguageModel` 빈 분리로 근본 해결 완료:**
    *   `chatLanguageModel` (@Primary) → `:8000` LLM-OPT 경유 (키워드 추출, 지식 추출)
    *   `ragChatLanguageModel` → `:11434` Ollama 직접 호출 (RAG 최종 답변)
    *   DB 업데이트 후 L2 캐시 히트로 이전 답변이 반환되던 문제 원천 차단.

**[현재 상태 — 2026-03-24]**

*   **핵심 파이프라인 안정화:** Qwen 2.5 14B 기반 지식 추출→저장→검색→답변 파이프라인 완전 안정화.
*   **RAG 품질 고도화 완료:** 점수 기반 컨텍스트 통합(RRF), 중복 제거, 토큰 예산 관리, 한/영 교차 검색, Query Expansion.
*   **단위 테스트 88개 통과:** GraphService, MindGraphService, SearchService, VectorService, Repository, Controller 전 레이어.
*   **React 프론트엔드 완성:** 대화 히스토리, localStorage 캐싱, 그래프 연동 검색, 신규 노드 강조, 임베딩 재생성 UI.
*   **LLM-OPT 통합 운영 중:** 캐시 히트 시 5,510ms → 0.43ms (약 12,000배). RAG 캐시 오염 해결 완료.

**[향후 계획]**

*   **WebSocket 실시간 알림:** 추출 완료 폴링(2초×6회) → Push 방식으로 개선.
*   **노드 정규화 임계값 실측 튜닝:** bge-m3 공간에서 0.95 threshold 운영 로그 기반 검증.
*   **입력 채널 확장:** URL/YouTube 요약 수집, Notion 연동 재활성화.