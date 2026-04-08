import axios from 'axios'

const api = axios.create({
  baseURL: 'http://localhost:8080',
  timeout: 60_000,
})

export interface NodeData {
  id: number
  name: string
  type: string
  description?: string
}

export interface EdgeData {
  id: number
  sourceName: string
  targetName: string
  relation: string
}

export interface GraphResponse {
  nodes: NodeData[]
  edges: EdgeData[]
}

export interface SearchResult {
  content: string
  score: number
}

export interface NodeInsight {
  name: string
  type: string
  degree: number
}

export const extractKnowledge = (text: string) =>
  api.post<{ message: string }>('/api/graph/extract', JSON.stringify(text), {
    headers: { 'Content-Type': 'application/json' },
  })

// 벡터 유사도 검색 (내용 문자열 반환)
export const searchSimilar = (query: string) =>
  api.get<SearchResult[]>('/api/graph/search', { params: { query } })

export const askQuestion = (question: string) =>
  api.post<{ answer: string }>('/api/graph/ask', { question })

export const getAllNodes = () =>
  api.get<GraphResponse>('/api/graph/nodes')

export const updateNode = (id: number, data: { type?: string; description?: string }) =>
  api.patch(`/api/graph/nodes/${id}`, data)

export const deleteNode = (id: number) =>
  api.delete(`/api/graph/nodes/${id}`)

export const getInsights = (limit = 10) =>
  api.get<{ insights: NodeInsight[]; count: number }>('/api/graph/insights', { params: { limit } })

// F-5: 노드 이름 임베딩 일괄 재생성 (D-1 마이그레이션용)
export const rebuildNodeEmbeddings = () =>
  api.post<{ message: string; embedded: number; skipped: number }>('/api/graph/rebuild-node-embeddings')
