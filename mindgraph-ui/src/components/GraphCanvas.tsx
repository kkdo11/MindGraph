import { useEffect, useRef } from 'react'
import cytoscape from 'cytoscape'
import { useGraphStore } from '../store/graphStore'

const NODE_COLORS: Record<string, string> = {
  Technology: '#6366f1',
  Person: '#f59e0b',
  Project: '#3b82f6',
  Concept: '#8b5cf6',
  Company: '#10b981',
  Cloud: '#06b6d4',
  Database: '#f97316',
  CloudProvider: '#06b6d4',  // legacy
  default: '#6b7280',
}

const NODE_SIZE: Record<string, number> = {
  Technology: 40,
  Person: 34,
  Project: 44,
  Concept: 30,
  Company: 38,
  Cloud: 34,
  Database: 38,
  CloudProvider: 34,
  default: 34,
}

const LAYOUT_OPTIONS: cytoscape.LayoutOptions = {
  name: 'cose',
  animate: true,
  animationDuration: 600,
  randomize: false,
  nodeRepulsion: 15000,
  idealEdgeLength: 160,
  nodeOverlap: 20,
  gravity: 0.25,
  numIter: 1000,
} as cytoscape.LayoutOptions

export default function GraphCanvas() {
  const containerRef = useRef<HTMLDivElement>(null)
  const cyRef = useRef<cytoscape.Core | null>(null)
  const elementKeyRef = useRef('')  // 마지막 렌더링 시 그래프 구조 식별자
  const { nodes, edges, selectNode, focusNodeId, setFocusNodeId, newNodeIds, clearNewNodeIds } = useGraphStore()

  // 초기화
  useEffect(() => {
    if (!containerRef.current) return
    cyRef.current = cytoscape({
      container: containerRef.current,
      style: [
        {
          selector: 'node',
          style: {
            'background-color': 'data(color)',
            'border-width': 2,
            'border-color': 'data(borderColor)',
            label: 'data(label)',
            'font-size': 11,
            color: '#e5e7eb',
            'text-valign': 'bottom',
            'text-margin-y': 5,
            'text-outline-width': 2,
            'text-outline-color': '#0f0f0f',
            width: 'data(size)',
            height: 'data(size)',
          },
        },
        {
          selector: 'node:selected',
          style: {
            'border-color': '#a5b4fc',
            'border-width': 3,
            width: 44,
            height: 44,
          },
        },
        {
          selector: 'edge',
          style: {
            width: 2,
            'line-color': '#6b7280',
            'target-arrow-color': '#9ca3af',
            'target-arrow-shape': 'triangle',
            'curve-style': 'bezier',
            label: 'data(label)',
            'font-size': 9,
            color: '#9ca3af',
            'text-background-color': '#111827',
            'text-background-opacity': 0.85,
            'text-background-padding': '2px',
            'text-rotation': 'autorotate',
          },
        },
        {
          selector: 'edge:hover',
          style: {
            'line-color': '#6366f1',
            'target-arrow-color': '#6366f1',
            width: 2.5,
          },
        },
        {
          selector: 'edge:selected',
          style: { 'line-color': '#6366f1', 'target-arrow-color': '#6366f1' },
        },
      ],
      layout: { name: 'cose', animate: false } as cytoscape.LayoutOptions,
      wheelSensitivity: 0.3,
    })

    cyRef.current.on('tap', 'node', (evt) => {
      const data = evt.target.data()
      selectNode({ id: data.id, name: data.name, type: data.type, description: data.description })
    })
    cyRef.current.on('tap', (evt) => {
      if (evt.target === cyRef.current) selectNode(null)
    })

    return () => cyRef.current?.destroy()
  }, [])

  // 노드/엣지 변경 시 그래프 업데이트
  useEffect(() => {
    const cy = cyRef.current
    if (!cy) return

    const filteredEdges = edges.filter((e) => {
      const srcNode = nodes.find((n) => n.name === e.sourceName)
      const tgtNode = nodes.find((n) => n.name === e.targetName)
      return srcNode !== undefined && tgtNode !== undefined
    })

    // 구조가 바뀐 경우에만 업데이트 — 같은 데이터 재로드 시 위치 유지
    const elementKey = [
      ...nodes.map((n) => `n${n.id}`),
      ...filteredEdges.map((e) => `e${e.id}`),
    ].sort().join(',')

    if (elementKey === elementKeyRef.current) return
    elementKeyRef.current = elementKey

    const elements: cytoscape.ElementDefinition[] = [
      ...nodes.map((n) => {
        const color = NODE_COLORS[n.type] ?? NODE_COLORS.default
        return {
          data: {
            id: String(n.id),
            label: n.name,
            name: n.name,
            type: n.type,
            description: n.description,
            color,
            borderColor: color,
            size: NODE_SIZE[n.type] ?? NODE_SIZE.default,
          },
        }
      }),
      ...filteredEdges.map((e) => ({
        data: {
          id: 'e-' + String(e.id),
          source: String(nodes.find((n) => n.name === e.sourceName)!.id),
          target: String(nodes.find((n) => n.name === e.targetName)!.id),
          label: e.relation,
        },
      })),
    ]

    if (elements.length === 0) return

    cy.elements().remove()
    cy.add(elements)
    cy.layout(LAYOUT_OPTIONS).run()
  }, [nodes, edges])

  // F-4: 신규 노드 강조 (노란 테두리 3초)
  // nodes 업데이트 effect 이후 실행되므로 cy.add() 완료 후 스타일 적용 가능
  useEffect(() => {
    const cy = cyRef.current
    if (!cy || newNodeIds.length === 0) return

    newNodeIds.forEach((id) => {
      const el = cy.getElementById(String(id))
      if (el.length > 0) {
        el.style({ 'border-color': '#fbbf24', 'border-width': 4 })
      }
    })

    const timer = setTimeout(() => {
      newNodeIds.forEach((id) => {
        const el = cy.getElementById(String(id))
        if (el.length > 0) {
          const color = el.data('color') as string
          el.style({ 'border-color': color, 'border-width': 2 })
        }
      })
      clearNewNodeIds()
    }, 3000)

    return () => clearTimeout(timer)
  }, [newNodeIds])

  // F-3: 의미 검색 결과 → 캔버스 포커스 (pan + zoom + 선택)
  useEffect(() => {
    const cy = cyRef.current
    if (!cy || focusNodeId === null) return

    const el = cy.getElementById(String(focusNodeId))
    if (el.length === 0) { setFocusNodeId(null); return }

    cy.animate({ center: { eles: el }, zoom: 1.8 }, { duration: 400 })
    el.select()
    setFocusNodeId(null)
  }, [focusNodeId])

  const zoomIn  = () => cyRef.current?.zoom({ level: (cyRef.current.zoom() * 1.3), renderedPosition: { x: (containerRef.current?.clientWidth ?? 800) / 2, y: (containerRef.current?.clientHeight ?? 600) / 2 } })
  const zoomOut = () => cyRef.current?.zoom({ level: (cyRef.current.zoom() * 0.77), renderedPosition: { x: (containerRef.current?.clientWidth ?? 800) / 2, y: (containerRef.current?.clientHeight ?? 600) / 2 } })
  const fitAll  = () => cyRef.current?.fit(undefined, 40)
  const reLayout = () => { if (cyRef.current && cyRef.current.elements().length > 0) cyRef.current.layout(LAYOUT_OPTIONS).run() }

  return (
    <div className="relative w-full h-full">
      <div ref={containerRef} className="w-full h-full" />

      {nodes.length === 0 && (
        <div className="absolute inset-0 flex items-center justify-center pointer-events-none">
          <div className="text-center text-gray-600">
            <div className="text-5xl mb-4">🕸️</div>
            <p className="text-sm">텍스트를 입력하면 지식 그래프가 생성됩니다</p>
          </div>
        </div>
      )}

      {/* 줌 컨트롤 — 우하단 */}
      <div className="absolute bottom-14 right-4 flex flex-col gap-1 z-10">
        {[
          { label: '+', title: '줌 인', fn: zoomIn },
          { label: '−', title: '줌 아웃', fn: zoomOut },
          { label: '⊡', title: '전체 보기', fn: fitAll },
          { label: '↺', title: '레이아웃 재배치', fn: reLayout },
        ].map(({ label, title, fn }) => (
          <button
            key={label}
            onClick={fn}
            title={title}
            className="w-8 h-8 rounded-lg bg-[#1a1a1a] border border-gray-700 text-gray-400 hover:text-white hover:border-gray-500 text-sm font-medium transition-colors flex items-center justify-center shadow-md"
          >
            {label}
          </button>
        ))}
      </div>
    </div>
  )
}
