import Sidebar from './components/Sidebar'
import GraphCanvas from './components/GraphCanvas'
import NodeDetail from './components/NodeDetail'
import StatusBar from './components/StatusBar'
import Toast from './components/Toast'

export default function App() {
  return (
    <div className="flex h-screen w-screen overflow-hidden bg-[#0f0f0f]">
      {/* 좌측 컨트롤 사이드바 */}
      <div className="w-80 shrink-0 h-full">
        <Sidebar />
      </div>

      {/* 중앙 그래프 캔버스 */}
      <div className="relative flex-1 h-full min-w-0">
        <GraphCanvas />
        <StatusBar />
      </div>

      {/* 우측 노드 상세 패널 */}
      <div className="w-72 shrink-0 h-full border-l border-gray-800 bg-[#1a1a1a]">
        <NodeDetail />
      </div>

      {/* 전역 Toast 알림 */}
      <Toast />
    </div>
  )
}
