# MindGraph UI

MindGraph-AI의 React 프론트엔드. Cytoscape.js 기반 대화형 지식 그래프 시각화 도구.

## 스택

- React 18 + TypeScript + Vite
- Cytoscape.js — 그래프 렌더링
- Zustand — 전역 상태 관리 (nodes, edges, selectedNode, toasts)
- TailwindCSS — 스타일링

## 실행

```bash
cd mindgraph-ui
npm install
npm run dev   # http://localhost:5173
```

백엔드(Spring Boot :8080)가 먼저 실행되어야 한다.

## 컴포넌트 구조

```
App.tsx
├── Sidebar.tsx       — 추출 / 탐색 / 질문 3탭
│   ├── 추출: 텍스트 입력 → POST /api/graph/extract → 폴링(2초×6회)
│   ├── 탐색: 키워드 필터 또는 전체 로드 → GET /api/graph/nodes
│   └── 질문: RAG 답변 → POST /api/graph/ask + 복사 버튼
├── GraphCanvas.tsx   — Cytoscape.js 캔버스
│   ├── elementKeyRef: 구조 변경 시에만 레이아웃 재실행 (위치 유지)
│   ├── 엣지 ID: 'e-' 접두사 (노드 ID와 네임스페이스 분리)
│   └── 줌 컨트롤: +/−/fit/재배치 버튼 (우하단)
├── NodeDetail.tsx    — 우측 패널
│   ├── 미선택: 통계 대시보드 (노드/엣지 카운트, 타입 분포)
│   └── 선택: 상세/편집/삭제 (인라인 삭제 확인 UI)
├── StatusBar.tsx     — 하단 중앙 상태바 (카운트 + 선택 노드)
└── Toast.tsx         — 우상단 알림 (3.5초 자동 dismiss)

store/graphStore.ts   — Zustand store (nodes, edges, selectedNode, toasts, status)
api/mindgraph.ts      — axios 기반 API 클라이언트
```

## 주요 설계 결정

| 결정 | 이유 |
|------|------|
| 엣지 ID `'e-' + id` | PostgreSQL nodes/edges 동일 IDENTITY 시퀀스 → Cytoscape 통합 ID 네임스페이스 충돌 방지 |
| `elementKeyRef` early return | mergeGraph가 동일 데이터로도 새 배열 참조 생성 → useEffect 재실행 방지 |
| 검색에 `setGraph` (교체) | mergeGraph(축적) 사용 시 elementKey 불변 → 필터 결과 미반영 |
| 추출에 `mergeGraph` (축적) | 기존 그래프 유지하며 신규 노드만 추가 |
| 폴링 (2초×6회) | LLM 처리 시간이 가변적 → 고정 대기 대신 완료 감지 |
