import { useState } from 'react'
import {
  extractKnowledge, getAllNodes, askQuestion,
  searchSimilar, getInsights, rebuildNodeEmbeddings,
} from '../api/mindgraph'
import type { SearchResult } from '../api/mindgraph'
import { useGraphStore } from '../store/graphStore'

type Tab = 'extract' | 'search' | 'ask' | 'insights'
type SearchMode = 'graph' | 'vector'

export default function Sidebar() {
  const [tab, setTab] = useState<Tab>('extract')
  const [extractText, setExtractText] = useState('')
  const [searchQuery, setSearchQuery] = useState('')
  const [searchMode, setSearchMode] = useState<SearchMode>('graph')
  const [vectorQuery, setVectorQuery] = useState('')
  const [vectorResults, setVectorResults] = useState<SearchResult[]>([])
  const [isVectorSearching, setIsVectorSearching] = useState(false)
  const [question, setQuestion] = useState('')
  const [copiedId, setCopiedId] = useState<string | null>(null)  // F-1
  const [isRebuildingEmbeddings, setIsRebuildingEmbeddings] = useState(false) // F-5

  const {
    nodes,
    isExtracting, isSearching, isAsking, isLoadingInsights,
    setExtracting, setSearching, setAsking, setLoadingInsights,
    setGraph, mergeGraph, addAnswer, clearAnswers, answers,  // F-1
    setStatus, addToast,
    selectNode, setFocusNodeId,  // F-3
    insights, setInsights,
  } = useGraphStore()

  const handleExtract = async () => {
    if (!extractText.trim()) return
    setExtracting(true)
    setStatus('지식 추출 요청 중...')
    const prevCount = nodes.length
    try {
      await extractKnowledge(extractText)
      setExtractText('')
      setStatus('LLM 처리 중...')

      let attempt = 0
      const poll = async () => {
        attempt++
        try {
          const res = await getAllNodes()
          const newNodes = res.data.nodes ?? []
          const newEdges = res.data.edges ?? []
          if (newNodes.length > prevCount) {
            mergeGraph(newNodes, newEdges)
            setStatus('')
            setExtracting(false)
            addToast('success', `추출 완료 · 노드 ${newNodes.length - prevCount}개 추가됨`)
          } else if (attempt < 6) {
            setStatus(`LLM 처리 중... (${attempt}/6)`)
            setTimeout(poll, 2000)
          } else {
            setStatus('')
            setExtracting(false)
            addToast('info', '처리 중입니다. 탐색 탭에서 새로고침하세요.')
          }
        } catch {
          setStatus('')
          setExtracting(false)
          addToast('error', '그래프 갱신 실패.')
        }
      }
      setTimeout(poll, 2000)
    } catch {
      setStatus('')
      setExtracting(false)
      addToast('error', '추출 실패. 백엔드 연결을 확인하세요.')
    }
  }

  const handleSearch = async () => {
    setSearching(true)
    setStatus('불러오는 중...')
    try {
      const res = await getAllNodes()
      const allNodes = res.data.nodes ?? []
      const allEdges = res.data.edges ?? []

      if (searchQuery.trim()) {
        const q = searchQuery.toLowerCase()
        const filtered = allNodes.filter(
          (n) => n.name.toLowerCase().includes(q) || n.type.toLowerCase().includes(q)
        )
        if (filtered.length === 0) {
          setGraph([], [])
          setStatus(`"${searchQuery}" 검색 결과 없음`)
        } else {
          const filteredNames = new Set(filtered.map((n) => n.name))
          const filteredEdges = allEdges.filter(
            (e) => filteredNames.has(e.sourceName) || filteredNames.has(e.targetName)
          )
          const edgeNodeNames = new Set([
            ...filteredEdges.map((e) => e.sourceName),
            ...filteredEdges.map((e) => e.targetName),
          ])
          const nodesForCanvas = allNodes.filter((n) => edgeNodeNames.has(n.name))
          setGraph(nodesForCanvas, filteredEdges)
          setStatus(`"${searchQuery}" · 노드 ${filtered.length}개 · 엣지 ${filteredEdges.length}개`)
        }
      } else {
        setGraph(allNodes, allEdges)
        setStatus(`노드 ${allNodes.length}개 · 엣지 ${allEdges.length}개`)
      }
    } catch {
      addToast('error', '불러오기 실패. 서버 연결을 확인하세요.')
      setStatus('')
    } finally {
      setSearching(false)
    }
  }

  const handleVectorSearch = async () => {
    if (!vectorQuery.trim()) return
    setIsVectorSearching(true)
    try {
      const res = await searchSimilar(vectorQuery)
      setVectorResults(res.data)
      if (res.data.length === 0) addToast('info', '유사한 문서가 없습니다.')
    } catch {
      addToast('error', '벡터 검색 실패. 서버 연결을 확인하세요.')
    } finally {
      setIsVectorSearching(false)
    }
  }

  // F-3: 의미 검색 결과 클릭 → 그래프 노드 포커스
  const handleResultClick = (content: string) => {
    const match = nodes.find((n) => content.includes(n.name))
    if (match) {
      selectNode(match)
      setFocusNodeId(match.id)
    } else {
      addToast('info', '관련 노드가 그래프에 없습니다. 탐색 탭에서 먼저 로드하세요.')
    }
  }

  const handleAsk = async () => {
    if (!question.trim()) return
    setAsking(true)
    const q = question
    setQuestion('')  // 입력창 즉시 초기화
    setStatus('답변 생성 중...')
    try {
      const res = await askQuestion(q)
      addAnswer(q, res.data.answer)  // F-1: 히스토리에 추가
      setStatus('')
    } catch {
      addToast('error', '질문 처리 실패. 서버 연결을 확인하세요.')
      setStatus('')
    } finally {
      setAsking(false)
    }
  }

  const handleLoadInsights = async () => {
    setLoadingInsights(true)
    try {
      const res = await getInsights(10)
      setInsights(res.data.insights)
      if (res.data.insights.length === 0) addToast('info', '그래프에 노드가 없습니다.')
    } catch {
      addToast('error', '인사이트 로드 실패. 서버 연결을 확인하세요.')
    } finally {
      setLoadingInsights(false)
    }
  }

  const handleInsightNodeClick = (name: string) => {
    const node = nodes.find((n) => n.name === name)
    if (node) {
      selectNode(node)
      setFocusNodeId(node.id)  // F-3: 캔버스 포커스도 함께
    } else {
      addToast('info', '탐색 탭에서 그래프를 먼저 로드해주세요.')
    }
  }

  // F-5: 노드 이름 임베딩 일괄 재생성
  const handleRebuildEmbeddings = async () => {
    setIsRebuildingEmbeddings(true)
    try {
      const res = await rebuildNodeEmbeddings()
      addToast('success', `임베딩 재생성 완료 · ${res.data.embedded}개 처리, ${res.data.skipped}개 스킵`)
    } catch {
      addToast('error', '임베딩 재생성 실패. 서버 연결을 확인하세요.')
    } finally {
      setIsRebuildingEmbeddings(false)
    }
  }

  // F-1: 답변 복사
  const handleCopyAnswer = (id: string, answer: string) => {
    navigator.clipboard.writeText(answer)
    setCopiedId(id)
    setTimeout(() => setCopiedId(null), 2000)
  }

  const tabs: { id: Tab; label: string; icon: string }[] = [
    { id: 'extract',  label: '추출',    icon: '📝' },
    { id: 'search',   label: '탐색',    icon: '🔍' },
    { id: 'ask',      label: '질문',    icon: '💬' },
    { id: 'insights', label: '인사이트', icon: '📊' },
  ]

  return (
    <div className="flex flex-col h-full bg-[#1a1a1a] border-r border-gray-800">
      {/* 헤더 */}
      <div className="px-4 py-4 border-b border-gray-800">
        <h1 className="text-base font-semibold text-white">MindGraph</h1>
        <p className="text-xs text-gray-500 mt-0.5">지식 그래프 AI 에이전트</p>
      </div>

      {/* 탭 */}
      <div className="flex border-b border-gray-800">
        {tabs.map((t) => (
          <button
            key={t.id}
            onClick={() => setTab(t.id)}
            className={`flex-1 py-2.5 text-xs font-medium transition-colors ${
              tab === t.id
                ? 'text-indigo-400 border-b-2 border-indigo-500 bg-[#1f1f2e]'
                : 'text-gray-500 hover:text-gray-300'
            }`}
          >
            {t.icon}
          </button>
        ))}
      </div>
      {/* 탭 라벨 */}
      <div className="flex border-b border-gray-800 bg-[#1a1a1a]">
        {tabs.map((t) => (
          <div
            key={t.id}
            className={`flex-1 text-center text-[10px] pb-1 ${
              tab === t.id ? 'text-indigo-400' : 'text-gray-600'
            }`}
          >
            {t.label}
          </div>
        ))}
      </div>

      {/* 탭 콘텐츠 */}
      <div className="flex-1 overflow-y-auto p-4 flex flex-col gap-3">

        {/* ── 추출 탭 ── */}
        {tab === 'extract' && (
          <>
            <p className="text-xs text-gray-500">
              텍스트를 입력하면 LLM이 노드·관계를 추출해 그래프에 저장합니다.
            </p>
            <textarea
              value={extractText}
              onChange={(e) => setExtractText(e.target.value)}
              onKeyDown={(e) => { if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) handleExtract() }}
              placeholder="예: Docker는 컨테이너 가상화 기술이다. Kubernetes는 Docker를 오케스트레이션한다."
              className="w-full h-44 bg-[#242424] border border-gray-700 rounded-lg px-3 py-2 text-sm text-gray-200 placeholder-gray-600 resize-none focus:outline-none focus:border-indigo-500"
            />
            <button
              onClick={handleExtract}
              disabled={isExtracting || !extractText.trim()}
              className="w-full py-2.5 rounded-lg bg-indigo-600 hover:bg-indigo-500 disabled:opacity-40 disabled:cursor-not-allowed text-sm font-medium transition-colors"
            >
              {isExtracting ? '처리 중...' : '지식 추출 (Ctrl+Enter)'}
            </button>
          </>
        )}

        {/* ── 탐색 탭 ── */}
        {tab === 'search' && (
          <>
            {/* 모드 토글 */}
            <div className="flex rounded-lg bg-[#242424] p-0.5 gap-0.5">
              <button
                onClick={() => setSearchMode('graph')}
                className={`flex-1 py-1.5 rounded-md text-xs font-medium transition-colors ${
                  searchMode === 'graph' ? 'bg-indigo-600 text-white' : 'text-gray-500 hover:text-gray-300'
                }`}
              >
                노드 탐색
              </button>
              <button
                onClick={() => setSearchMode('vector')}
                className={`flex-1 py-1.5 rounded-md text-xs font-medium transition-colors ${
                  searchMode === 'vector' ? 'bg-indigo-600 text-white' : 'text-gray-500 hover:text-gray-300'
                }`}
              >
                의미 검색
              </button>
            </div>

            {/* 노드 탐색 모드 */}
            {searchMode === 'graph' && (
              <>
                <p className="text-xs text-gray-500">
                  키워드로 노드를 필터링하거나, 비워두면 전체를 불러옵니다.
                </p>
                <div className="relative">
                  <input
                    value={searchQuery}
                    onChange={(e) => setSearchQuery(e.target.value)}
                    onKeyDown={(e) => e.key === 'Enter' && handleSearch()}
                    placeholder="예: Docker, 머신러닝..."
                    className="w-full bg-[#242424] border border-gray-700 rounded-lg px-3 py-2 pr-8 text-sm text-gray-200 placeholder-gray-600 focus:outline-none focus:border-indigo-500"
                  />
                  {searchQuery && (
                    <button
                      onClick={() => setSearchQuery('')}
                      className="absolute right-2.5 top-1/2 -translate-y-1/2 text-gray-500 hover:text-gray-300 text-base leading-none transition-colors"
                    >
                      ×
                    </button>
                  )}
                </div>
                <button
                  onClick={handleSearch}
                  disabled={isSearching}
                  className="w-full py-2.5 rounded-lg bg-indigo-600 hover:bg-indigo-500 disabled:opacity-40 disabled:cursor-not-allowed text-sm font-medium transition-colors"
                >
                  {isSearching ? '불러오는 중...' : searchQuery.trim() ? '필터 검색 (Enter)' : '전체 그래프 로드'}
                </button>
              </>
            )}

            {/* 의미 검색 모드 — pgvector 코사인 유사도 */}
            {searchMode === 'vector' && (
              <>
                <p className="text-xs text-gray-500">
                  저장된 지식에서 의미적으로 유사한 문서를 검색합니다. 결과 클릭 시 관련 노드로 이동합니다.
                </p>
                <div className="relative">
                  <input
                    value={vectorQuery}
                    onChange={(e) => setVectorQuery(e.target.value)}
                    onKeyDown={(e) => e.key === 'Enter' && handleVectorSearch()}
                    placeholder="예: 컨테이너 가상화 기술"
                    className="w-full bg-[#242424] border border-gray-700 rounded-lg px-3 py-2 pr-8 text-sm text-gray-200 placeholder-gray-600 focus:outline-none focus:border-indigo-500"
                  />
                  {vectorQuery && (
                    <button
                      onClick={() => { setVectorQuery(''); setVectorResults([]) }}
                      className="absolute right-2.5 top-1/2 -translate-y-1/2 text-gray-500 hover:text-gray-300 text-base leading-none transition-colors"
                    >
                      ×
                    </button>
                  )}
                </div>
                <button
                  onClick={handleVectorSearch}
                  disabled={isVectorSearching || !vectorQuery.trim()}
                  className="w-full py-2.5 rounded-lg bg-indigo-600 hover:bg-indigo-500 disabled:opacity-40 disabled:cursor-not-allowed text-sm font-medium transition-colors"
                >
                  {isVectorSearching ? '검색 중...' : '의미 검색 (Enter)'}
                </button>

                {/* F-3: 결과 카드 — 클릭 시 그래프 노드 포커스 */}
                {vectorResults.length > 0 && (
                  <div className="space-y-2">
                    <p className="text-xs text-gray-600 font-medium">{vectorResults.length}개 결과 · 클릭하면 노드로 이동</p>
                    {vectorResults.map((result, i) => (
                      <button
                        key={i}
                        onClick={() => handleResultClick(result.content)}
                        className="w-full text-left bg-[#242424] rounded-lg p-3 border border-gray-800 hover:border-indigo-700 hover:bg-[#1f1f2e] transition-colors"
                      >
                        <div className="flex items-center justify-between mb-1.5">
                          <span className="text-xs text-gray-600">#{i + 1}</span>
                          <span className="text-xs text-indigo-400 font-medium">
                            {(result.score * 100).toFixed(1)}% 유사
                          </span>
                        </div>
                        <p className="text-xs text-gray-300 leading-relaxed">{result.content}</p>
                      </button>
                    ))}
                  </div>
                )}
              </>
            )}
          </>
        )}

        {/* ── 질문 탭 ── */}
        {tab === 'ask' && (
          <>
            <p className="text-xs text-gray-500">
              그래프 지식을 바탕으로 LLM이 답변합니다.
            </p>
            <textarea
              value={question}
              onChange={(e) => setQuestion(e.target.value)}
              onKeyDown={(e) => { if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) handleAsk() }}
              placeholder="예: Docker와 Kubernetes의 관계를 설명해줘"
              className="w-full h-24 bg-[#242424] border border-gray-700 rounded-lg px-3 py-2 text-sm text-gray-200 placeholder-gray-600 resize-none focus:outline-none focus:border-indigo-500"
            />
            <button
              onClick={handleAsk}
              disabled={isAsking || !question.trim()}
              className="w-full py-2.5 rounded-lg bg-indigo-600 hover:bg-indigo-500 disabled:opacity-40 disabled:cursor-not-allowed text-sm font-medium transition-colors"
            >
              {isAsking ? '생각 중...' : '질문하기 (Ctrl+Enter)'}
            </button>

            {/* F-1: 질문/답변 히스토리 */}
            {answers.length > 0 && (
              <div className="flex items-center justify-between">
                <p className="text-xs text-gray-600 font-medium">대화 기록 {answers.length}개</p>
                <button
                  onClick={clearAnswers}
                  className="text-xs text-gray-700 hover:text-gray-400 transition-colors"
                >
                  전체 삭제
                </button>
              </div>
            )}

            <div className="space-y-2">
              {answers.map((item) => (
                <div key={item.id} className="bg-[#242424] rounded-lg border border-gray-700 overflow-hidden">
                  {/* 질문 */}
                  <div className="px-3 py-2 border-b border-gray-800 bg-[#1f1f1f]">
                    <p className="text-xs text-gray-500 leading-relaxed">{item.question}</p>
                    <p className="text-[10px] text-gray-700 mt-1">
                      {new Date(item.timestamp).toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit' })}
                    </p>
                  </div>
                  {/* 답변 */}
                  <div className="flex items-start justify-between px-3 py-2.5 gap-2">
                    <p className="text-xs text-gray-300 leading-relaxed whitespace-pre-wrap flex-1">{item.answer}</p>
                    <button
                      onClick={() => handleCopyAnswer(item.id, item.answer)}
                      className="text-xs text-gray-600 hover:text-gray-400 transition-colors shrink-0 mt-0.5"
                    >
                      {copiedId === item.id ? '✓' : '복사'}
                    </button>
                  </div>
                </div>
              ))}
            </div>
          </>
        )}

        {/* ── 인사이트 탭 ── */}
        {tab === 'insights' && (
          <>
            <p className="text-xs text-gray-500">
              연결 수(degree) 기준 핵심 노드 순위입니다. Neo4j 중심성 분석을 사용합니다.
            </p>
            <button
              onClick={handleLoadInsights}
              disabled={isLoadingInsights}
              className="w-full py-2.5 rounded-lg bg-indigo-600 hover:bg-indigo-500 disabled:opacity-40 disabled:cursor-not-allowed text-sm font-medium transition-colors"
            >
              {isLoadingInsights ? '분석 중...' : insights.length > 0 ? '새로고침' : '인사이트 로드'}
            </button>

            {insights.length > 0 && (
              <div className="space-y-1.5">
                <p className="text-xs text-gray-600 font-medium">상위 {insights.length}개 핵심 노드</p>
                {insights.map((insight, i) => (
                  <button
                    key={insight.name}
                    onClick={() => handleInsightNodeClick(insight.name)}
                    className="w-full flex items-center gap-3 bg-[#242424] rounded-lg px-3 py-2.5 hover:bg-[#2a2a2a] transition-colors text-left"
                  >
                    <span className="text-sm font-bold text-gray-700 w-5 shrink-0 text-right">{i + 1}</span>
                    <div className="flex-1 min-w-0">
                      <p className="text-xs font-medium text-gray-200 truncate">{insight.name}</p>
                      <p className="text-[10px] text-gray-600 mt-0.5">{insight.type}</p>
                    </div>
                    {insight.degree > 0 ? (
                      <span className="text-xs text-indigo-400 shrink-0 font-medium">{insight.degree} 연결</span>
                    ) : (
                      <span className="text-xs text-gray-700 shrink-0">—</span>
                    )}
                  </button>
                ))}
              </div>
            )}

            {insights.length === 0 && !isLoadingInsights && (
              <p className="text-xs text-gray-700 text-center py-4">
                버튼을 눌러 분석을 시작하세요.
              </p>
            )}

            {/* F-5: 노드 임베딩 재생성 — 구분선 */}
            <div className="border-t border-gray-800 pt-3 mt-2">
              <p className="text-xs text-gray-600 mb-2">유지 관리</p>
              <button
                onClick={handleRebuildEmbeddings}
                disabled={isRebuildingEmbeddings}
                className="w-full py-2 rounded-lg bg-[#242424] border border-gray-700 hover:border-gray-500 disabled:opacity-40 disabled:cursor-not-allowed text-xs text-gray-400 hover:text-gray-200 transition-colors"
              >
                {isRebuildingEmbeddings ? '재생성 중...' : '노드 임베딩 재생성'}
              </button>
              <p className="text-[10px] text-gray-700 mt-1.5">
                새 노드 추가 후 한/영 교차 검색이 안 될 때 실행하세요.
              </p>
            </div>
          </>
        )}
      </div>
    </div>
  )
}
