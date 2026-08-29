import React from 'react'
import { Activity, Radio, ListTree, RefreshCcw } from 'lucide-react'
import { useScanProgress } from '../hooks/useScanProgress'
import { useAuth } from '../lib/auth/auth-context'

export const ScannerProgressWidget: React.FC = () => {
  const { progress, error } = useScanProgress()
  const { apiClient } = useAuth()
  const [rescanLoading, setRescanLoading] = React.useState(false)

  if (error) {
    return null // Fail silently or log
  }

  if (!progress) {
    return null
  }

  const isScanning = progress.activeScans > 0 || progress.queuedDevices > 0

  const handleScanAll = async () => {
    try {
      setRescanLoading(true)
      await apiClient('/api/scanner/scan-all', { method: 'POST' })
    } catch (e) {
      console.error(e)
    } finally {
      setTimeout(() => setRescanLoading(false), 1000)
    }
  }

  return (
    <div className="bg-slate-900/50 backdrop-blur-md border border-white/10 rounded-2xl p-4 flex items-center justify-between shadow-lg shadow-black/20">
      <div className="flex items-center gap-6">
        <div className="flex items-center gap-3">
          <div className={`p-2 rounded-xl ${isScanning ? 'bg-blue-500/20 text-blue-400' : 'bg-slate-800 text-slate-400'}`}>
            <Activity className={`w-5 h-5 ${isScanning ? 'animate-pulse' : ''}`} />
          </div>
          <div>
            <div className="text-sm font-medium text-slate-200">Port Scanner</div>
            <div className="text-xs text-slate-400">
              {isScanning ? 'Scan in progress' : 'Idle'}
            </div>
          </div>
        </div>

        <div className="h-8 w-px bg-white/10" />

        <div className="flex items-center gap-8 text-sm">
          <div className="flex flex-col">
            <span className="text-slate-400 text-xs flex items-center gap-1"><Radio className="w-3 h-3"/> Active</span>
            <span className="font-semibold text-slate-200">{progress.activeScans}</span>
          </div>
          <div className="flex flex-col">
            <span className="text-slate-400 text-xs flex items-center gap-1"><ListTree className="w-3 h-3"/> Queued</span>
            <span className="font-semibold text-slate-200">{progress.queuedDevices}</span>
          </div>
          <div className="flex flex-col">
            <span className="text-slate-400 text-xs flex items-center gap-1"><Activity className="w-3 h-3"/> Completed</span>
            <span className="font-semibold text-slate-200">{progress.totalScanned}</span>
          </div>
        </div>
      </div>

      <button
        onClick={handleScanAll}
        disabled={rescanLoading || isScanning}
        className="px-4 py-2 bg-slate-800 hover:bg-slate-700 disabled:opacity-50 disabled:cursor-not-allowed border border-white/5 rounded-lg text-sm font-medium text-slate-200 transition-colors flex items-center gap-2"
      >
        <RefreshCcw className={`w-4 h-4 ${(rescanLoading || isScanning) ? 'animate-spin' : ''}`} />
        Scan All Pending
      </button>
    </div>
  )
}
