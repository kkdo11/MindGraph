import { create } from 'zustand'
import { persist } from 'zustand/middleware'
import type { NodeData, EdgeData, NodeInsight } from '../api/mindgraph'

export interface SelectedNode extends NodeData {
  connectedEdges: EdgeData[]
}

export interface ToastItem {
  id: string
  type: 'success' | 'error' | 'info'
  message: string
}

// F-1: 질문/답변 히스토리 아이템
export interface QAItem {
  id: string
  question: string
  answer: string
  timestamp: string // ISO string (localStorage 직렬화 호환)
}

interface GraphStore {
  nodes: NodeData[]
  edges: EdgeData[]
  selectedNode: SelectedNode | null
  answers: QAItem[]       // F-1: 히스토리 (최신 순)
  newNodeIds: number[]    // F-4: 신규 추가 노드 ID
  focusNodeId: number | null // F-3: 캔버스 포커스 요청
  isExtracting: boolean
  isSearching: boolean
  isAsking: boolean
  isLoadingInsights: boolean
  statusMessage: string
  toasts: ToastItem[]
  insights: NodeInsight[]

  setGraph: (nodes: NodeData[], edges: EdgeData[]) => void
  mergeGraph: (nodes: NodeData[], edges: EdgeData[]) => void
  selectNode: (node: NodeData | null) => void
  updateNodeInStore: (id: number, data: { type?: string; description?: string }) => void
  removeNodeFromStore: (id: number) => void
  addAnswer: (question: string, answer: string) => void  // F-1
  clearAnswers: () => void                               // F-1
  setFocusNodeId: (id: number | null) => void           // F-3
  clearNewNodeIds: () => void                            // F-4
  setExtracting: (v: boolean) => void
  setSearching: (v: boolean) => void
  setAsking: (v: boolean) => void
  setLoadingInsights: (v: boolean) => void
  setInsights: (insights: NodeInsight[]) => void
  setStatus: (msg: string) => void
  addToast: (type: ToastItem['type'], message: string) => void
  removeToast: (id: string) => void
}

export const useGraphStore = create<GraphStore>()(
  persist(
    (set, get) => ({
      nodes: [],
      edges: [],
      selectedNode: null,
      answers: [],
      newNodeIds: [],
      focusNodeId: null,
      isExtracting: false,
      isSearching: false,
      isAsking: false,
      isLoadingInsights: false,
      statusMessage: '',
      toasts: [],
      insights: [],

      setGraph: (nodes, edges) => set({ nodes, edges, selectedNode: null, newNodeIds: [] }),

      mergeGraph: (newNodes, newEdges) => {
        const { nodes, edges } = get()
        const existingIds = new Set(nodes.map((n) => n.id))
        const addedNodes = newNodes.filter((n) => !existingIds.has(n.id))
        const merged = [...nodes, ...addedNodes]
        const existingEdgeIds = new Set(edges.map((e) => e.id))
        const mergedEdges = [
          ...edges,
          ...newEdges.filter((e) => !existingEdgeIds.has(e.id)),
        ]
        set({
          nodes: merged,
          edges: mergedEdges,
          newNodeIds: addedNodes.map((n) => n.id), // F-4: 신규 노드 추적
        })
      },

      selectNode: (node) => {
        if (!node) { set({ selectedNode: null }); return }
        const { edges } = get()
        const connectedEdges = edges.filter(
          (e) => e.sourceName === node.name || e.targetName === node.name,
        )
        set({ selectedNode: { ...node, connectedEdges } })
      },

      updateNodeInStore: (id, data) => set((state) => ({
        nodes: state.nodes.map((n) => n.id === id ? { ...n, ...data } : n),
        selectedNode: state.selectedNode?.id === id
          ? { ...state.selectedNode, ...data }
          : state.selectedNode,
      })),

      removeNodeFromStore: (id) => set((state) => ({
        nodes: state.nodes.filter((n) => n.id !== id),
        edges: state.edges.filter((e) => {
          const node = state.nodes.find((n) => n.id === id)
          return node ? e.sourceName !== node.name && e.targetName !== node.name : true
        }),
        selectedNode: state.selectedNode?.id === id ? null : state.selectedNode,
      })),

      // F-1: 답변 히스토리 추가 (최신 순 prepend)
      addAnswer: (question, answer) => {
        const item: QAItem = {
          id: Math.random().toString(36).slice(2),
          question,
          answer,
          timestamp: new Date().toISOString(),
        }
        set((state) => ({ answers: [item, ...state.answers] }))
      },

      clearAnswers: () => set({ answers: [] }),

      // F-3: 캔버스 포커스 요청 (GraphCanvas가 소비 후 null로 초기화)
      setFocusNodeId: (id) => set({ focusNodeId: id }),

      // F-4: 신규 노드 강조 해제
      clearNewNodeIds: () => set({ newNodeIds: [] }),

      setExtracting: (v) => set({ isExtracting: v }),
      setSearching: (v) => set({ isSearching: v }),
      setAsking: (v) => set({ isAsking: v }),
      setLoadingInsights: (v) => set({ isLoadingInsights: v }),
      setInsights: (insights) => set({ insights }),
      setStatus: (msg) => set({ statusMessage: msg }),

      addToast: (type, message) => {
        const id = Math.random().toString(36).slice(2)
        set((state) => ({ toasts: [...state.toasts, { id, type, message }] }))
        setTimeout(() => get().removeToast(id), 3500)
      },

      removeToast: (id) => set((state) => ({
        toasts: state.toasts.filter((t) => t.id !== id),
      })),
    }),
    {
      name: 'mindgraph-store',
      // F-2: nodes, edges만 localStorage에 영속화 (UI 상태 제외)
      partialize: (state) => ({ nodes: state.nodes, edges: state.edges }),
    }
  )
)
