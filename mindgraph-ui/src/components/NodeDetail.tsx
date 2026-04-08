import { useState } from 'react'
import { updateNode, deleteNode } from '../api/mindgraph'
import { useGraphStore } from '../store/graphStore'

export const NODE_TYPES = ['Person', 'Company', 'Project', 'Technology', 'Concept', 'Cloud', 'Database']

const TYPE_COLORS: Record<string, string> = {
  Technology: '#6366f1',
  Person:     '#f59e0b',
  Project:    '#3b82f6',
  Concept:    '#8b5cf6',
  Company:    '#10b981',
  Cloud:      '#06b6d4',
  Database:   '#f97316',
  default:    '#6b7280',
}

export default function NodeDetail() {
  const {
    selectedNode, selectNode, updateNodeInStore, removeNodeFromStore,
    addToast, nodes, edges, insights,
  } = useGraphStore()
  const [editing, setEditing] = useState(false)
  const [editType, setEditType] = useState('')
  const [editDesc, setEditDesc] = useState('')
  const [saving, setSaving] = useState(false)
  const [confirmDelete, setConfirmDelete] = useState(false)

  const handleEditStart = () => {
    setEditType(selectedNode!.type)
    setEditDesc(selectedNode!.description ?? '')
    setEditing(true)
  }

  const handleSave = async () => {
    if (!selectedNode) return
    setSaving(true)
    try {
      await updateNode(selectedNode.id, { type: editType, description: editDesc })
      updateNodeInStore(selectedNode.id, { type: editType, description: editDesc })
      setEditing(false)
      addToast('success', '노드가 업데이트되었습니다.')
    } catch {
      addToast('error', '저장 실패. 서버 연결을 확인하세요.')
    } finally {
      setSaving(false)
    }
  }

  const handleDelete = async () => {
    if (!selectedNode) return
    try {
      await deleteNode(selectedNode.id)
      removeNodeFromStore(selectedNode.id)
      addToast('success', `"${selectedNode.name}" 노드가 삭제되었습니다.`)
      setConfirmDelete(false)
    } catch {
      addToast('error', '삭제 실패. 서버 연결을 확인하세요.')
      setConfirmDelete(false)
    }
  }

  // 빈 상태: 노드 미선택
  if (!selectedNode) {
    const typeStats = NODE_TYPES.map((type) => ({
      type,
      count: nodes.filter((n) => n.type === type).length,
      color: TYPE_COLORS[type],
    })).filter((s) => s.count > 0)

    return (
      <div className="h-full flex flex-col p-4 overflow-y-auto">
        {/* 헤더 */}
        <div className="mb-4">
          <h2 className="text-sm font-semibold text-gray-300">노드 상세</h2>
          <p className="text-xs text-gray-600 mt-0.5">노드를 클릭하면 정보가 표시됩니다.</p>
        </div>

        {/* 그래프 통계 */}
        {nodes.length > 0 && (
          <div className="grid grid-cols-2 gap-2 mb-5">
            <div className="bg-[#242424] rounded-xl p-3 text-center">
              <div className="text-2xl font-bold text-white">{nodes.length}</div>
              <div className="text-xs text-gray-500 mt-0.5">노드</div>
            </div>
            <div className="bg-[#242424] rounded-xl p-3 text-center">
              <div className="text-2xl font-bold text-white">{edges.length}</div>
              <div className="text-xs text-gray-500 mt-0.5">엣지</div>
            </div>
          </div>
        )}

        {/* 타입별 분포 */}
        {typeStats.length > 0 && (
          <div className="mb-5">
            <p className="text-xs text-gray-500 font-medium mb-2.5">노드 타입</p>
            <div className="space-y-2">
              {typeStats.map(({ type, count, color }) => (
                <div key={type} className="flex items-center gap-2.5">
                  <span className="w-2 h-2 rounded-full shrink-0" style={{ backgroundColor: color }} />
                  <span className="text-xs text-gray-400 flex-1">{type}</span>
                  <div className="flex items-center gap-1.5">
                    <div className="h-1 rounded-full bg-gray-700" style={{ width: 40 }}>
                      <div
                        className="h-1 rounded-full"
                        style={{
                          width: `${Math.round((count / nodes.length) * 100)}%`,
                          backgroundColor: color,
                          opacity: 0.7,
                        }}
                      />
                    </div>
                    <span className="text-xs text-gray-600 w-4 text-right">{count}</span>
                  </div>
                </div>
              ))}
            </div>
          </div>
        )}

        {/* 핵심 노드 (인사이트 탭에서 로드된 경우) */}
        {insights.length > 0 && (
          <div className="mb-5">
            <p className="text-xs text-gray-500 font-medium mb-2.5">핵심 노드 (degree 기준)</p>
            <div className="space-y-1.5">
              {insights.slice(0, 5).map((insight, i) => {
                const color = TYPE_COLORS[insight.type] ?? TYPE_COLORS.default
                return (
                  <button
                    key={insight.name}
                    onClick={() => {
                      const node = nodes.find((n) => n.name === insight.name)
                      if (node) selectNode(node)
                    }}
                    className="w-full flex items-center gap-2 bg-[#242424] rounded-lg px-2.5 py-2 hover:bg-[#2a2a2a] transition-colors text-left"
                  >
                    <span className="text-xs text-gray-700 w-4 shrink-0 text-right">{i + 1}</span>
                    <span className="w-1.5 h-1.5 rounded-full shrink-0" style={{ backgroundColor: color }} />
                    <span className="text-xs text-gray-300 flex-1 truncate">{insight.name}</span>
                    {insight.degree > 0 && (
                      <span className="text-[10px] text-indigo-500 shrink-0">{insight.degree}</span>
                    )}
                  </button>
                )
              })}
            </div>
          </div>
        )}

        {/* 사용 가이드 */}
        <div className="mt-auto pt-4 border-t border-gray-800">
          <p className="text-xs text-gray-600 font-medium mb-2">사용 방법</p>
          <div className="space-y-1.5 text-xs text-gray-700">
            <p>· 노드 클릭 → 상세 정보 확인 및 편집</p>
            <p>· 스크롤 → 줌 인 / 아웃</p>
            <p>· 드래그 → 캔버스 이동</p>
            <p>· 인사이트 탭 → 핵심 노드 분석</p>
          </div>
        </div>
      </div>
    )
  }

  const typeColor = TYPE_COLORS[selectedNode.type] ?? TYPE_COLORS.default

  return (
    <div className="h-full flex flex-col overflow-hidden">
      {/* 헤더 */}
      <div className="px-4 py-3.5 border-b border-gray-800 flex items-start justify-between gap-2">
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2 mb-1">
            <span className="w-2 h-2 rounded-full shrink-0" style={{ backgroundColor: typeColor }} />
            <span className="text-xs text-gray-500">{selectedNode.type}</span>
          </div>
          <h3
            className="text-sm font-semibold text-white leading-tight"
            style={{ wordBreak: 'break-word' }}
          >
            {selectedNode.name}
          </h3>
        </div>
        <button
          onClick={() => { selectNode(null); setEditing(false); setConfirmDelete(false) }}
          className="text-gray-600 hover:text-gray-300 text-lg leading-none shrink-0 mt-0.5 transition-colors"
        >
          ×
        </button>
      </div>

      {/* 본문 스크롤 영역 */}
      <div className="flex-1 overflow-y-auto px-4 py-3 space-y-4">
        {editing ? (
          /* 편집 폼 */
          <div className="space-y-3">
            <div>
              <label className="text-xs text-gray-500 mb-1.5 block font-medium">타입</label>
              <select
                value={editType}
                onChange={(e) => setEditType(e.target.value)}
                className="w-full bg-[#242424] border border-gray-600 rounded-lg px-3 py-2 text-sm text-gray-200 focus:outline-none focus:border-indigo-500"
              >
                {NODE_TYPES.map((t) => (
                  <option key={t} value={t}>{t}</option>
                ))}
              </select>
            </div>
            <div>
              <label className="text-xs text-gray-500 mb-1.5 block font-medium">설명</label>
              <textarea
                value={editDesc}
                onChange={(e) => setEditDesc(e.target.value)}
                rows={5}
                className="w-full bg-[#242424] border border-gray-600 rounded-lg px-3 py-2 text-sm text-gray-200 resize-none focus:outline-none focus:border-indigo-500"
              />
            </div>
            <div className="flex gap-2 pt-1">
              <button
                onClick={handleSave}
                disabled={saving}
                className="flex-1 py-2 rounded-lg bg-indigo-600 hover:bg-indigo-500 disabled:opacity-40 text-xs font-medium transition-colors"
              >
                {saving ? '저장 중...' : '저장'}
              </button>
              <button
                onClick={() => setEditing(false)}
                className="flex-1 py-2 rounded-lg bg-gray-700 hover:bg-gray-600 text-xs font-medium text-gray-300 transition-colors"
              >
                취소
              </button>
            </div>
          </div>
        ) : (
          /* 뷰 모드 */
          <>
            {/* 설명 */}
            <div>
              <p className="text-xs text-gray-500 font-medium mb-1.5">설명</p>
              {selectedNode.description ? (
                <p className="text-xs text-gray-300 leading-relaxed">{selectedNode.description}</p>
              ) : (
                <p className="text-xs text-gray-600 italic">설명 없음</p>
              )}
            </div>

            {/* 연결된 관계 */}
            {selectedNode.connectedEdges.length > 0 && (
              <div>
                <p className="text-xs text-gray-500 font-medium mb-2">
                  연결된 관계 <span className="text-gray-600 font-normal">({selectedNode.connectedEdges.length})</span>
                </p>
                <div className="space-y-1.5">
                  {selectedNode.connectedEdges.map((edge) => {
                    const isSource = edge.sourceName === selectedNode.name
                    const other = isSource ? edge.targetName : edge.sourceName
                    return (
                      <div
                        key={edge.id}
                        className="flex items-center gap-1.5 text-xs bg-[#242424] rounded-lg px-2.5 py-2 hover:bg-[#2a2a2a] transition-colors"
                      >
                        {isSource ? (
                          <>
                            <span className="text-gray-600 shrink-0">→</span>
                            <span className="text-indigo-400 shrink-0 font-medium">{edge.relation}</span>
                            <span className="text-gray-300 truncate ml-auto">{other}</span>
                          </>
                        ) : (
                          <>
                            <span className="text-gray-300 truncate">{other}</span>
                            <span className="text-indigo-400 shrink-0 font-medium ml-auto">{edge.relation}</span>
                            <span className="text-gray-600 shrink-0">→</span>
                          </>
                        )}
                      </div>
                    )
                  })}
                </div>
              </div>
            )}
          </>
        )}
      </div>

      {/* 하단 액션 버튼 */}
      {!editing && (
        <div className="px-4 pb-4 pt-3 border-t border-gray-800">
          {confirmDelete ? (
            <div className="space-y-2">
              <p className="text-xs text-red-400 text-center py-1">
                연결된 엣지도 함께 삭제됩니다.
              </p>
              <div className="flex gap-2">
                <button
                  onClick={handleDelete}
                  className="flex-1 py-2 rounded-lg bg-red-700 hover:bg-red-600 text-xs font-medium text-white transition-colors"
                >
                  삭제 확인
                </button>
                <button
                  onClick={() => setConfirmDelete(false)}
                  className="flex-1 py-2 rounded-lg bg-gray-700 hover:bg-gray-600 text-xs font-medium text-gray-300 transition-colors"
                >
                  취소
                </button>
              </div>
            </div>
          ) : (
            <div className="flex gap-2">
              <button
                onClick={handleEditStart}
                className="flex-1 py-2 rounded-lg bg-gray-700 hover:bg-gray-600 text-xs font-medium text-gray-300 transition-colors"
              >
                편집
              </button>
              <button
                onClick={() => setConfirmDelete(true)}
                className="flex-1 py-2 rounded-lg bg-red-950/60 hover:bg-red-900/70 text-xs font-medium text-red-400 transition-colors"
              >
                삭제
              </button>
            </div>
          )}
        </div>
      )}
    </div>
  )
}
