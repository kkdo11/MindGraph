import { useGraphStore } from '../store/graphStore'

export default function Toast() {
  const { toasts, removeToast } = useGraphStore()

  if (toasts.length === 0) return null

  const styles = {
    success: 'bg-emerald-950/95 border-emerald-700/60 text-emerald-200',
    error:   'bg-red-950/95 border-red-700/60 text-red-200',
    info:    'bg-indigo-950/95 border-indigo-700/60 text-indigo-200',
  }
  const icons = { success: '✓', error: '✕', info: 'ℹ' }

  return (
    <div className="fixed top-4 right-4 z-50 flex flex-col gap-2 pointer-events-none">
      {toasts.map((toast) => (
        <div
          key={toast.id}
          className={`flex items-center gap-2.5 px-4 py-2.5 rounded-lg border text-sm pointer-events-auto shadow-xl backdrop-blur-sm ${styles[toast.type]}`}
        >
          <span className="font-bold shrink-0 text-base leading-none">{icons[toast.type]}</span>
          <span className="leading-snug">{toast.message}</span>
          <button
            onClick={() => removeToast(toast.id)}
            className="ml-1 opacity-50 hover:opacity-100 shrink-0 text-lg leading-none"
          >
            ×
          </button>
        </div>
      ))}
    </div>
  )
}
