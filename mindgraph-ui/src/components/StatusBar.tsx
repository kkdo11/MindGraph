import { useGraphStore } from '../store/graphStore'

export default function StatusBar() {
  const { nodes, edges, statusMessage, isExtracting, isSearching, isAsking, selectedNode } = useGraphStore()
  const isBusy = isExtracting || isSearching || isAsking

  return (
    <div className="absolute bottom-4 left-1/2 -translate-x-1/2 flex items-center gap-3 bg-[#1a1a1a] border border-gray-800 rounded-full px-4 py-2 text-xs text-gray-500 shadow-lg z-10 max-w-[480px]">
      {isBusy && (
        <span className="flex items-center gap-1.5 text-indigo-400 shrink-0">
          <span className="animate-spin inline-block">⟳</span>
          <span className="truncate max-w-[200px]">{statusMessage}</span>
        </span>
      )}
      {!isBusy && statusMessage && (
        <span className="text-gray-400 truncate max-w-[200px]">{statusMessage}</span>
      )}
      {selectedNode && (
        <>
          {(isBusy || statusMessage) && <span className="text-gray-700 shrink-0">·</span>}
          <span className="text-indigo-400 truncate max-w-[120px] shrink-0">
            {selectedNode.name}
          </span>
        </>
      )}
      <span className="shrink-0">노드 {nodes.length}</span>
      <span className="text-gray-700 shrink-0">·</span>
      <span className="shrink-0">엣지 {edges.length}</span>
    </div>
  )
}
