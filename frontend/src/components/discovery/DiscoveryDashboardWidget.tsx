import React, { useEffect, useState } from 'react'
import { Activity, AlertTriangle, Play, Power, ShieldAlert, Cpu, RefreshCw } from 'lucide-react'
import { useAuth } from '../../lib/auth/auth-context'
import { LiveDiscoveryFeed } from './LiveDiscoveryFeed'

export interface DiscoveryModule {
  id: string
  name: string
  status: 'RUNNING' | 'STOPPED' | 'ERROR' | 'DISABLED'
  errorMessage?: string
  currentActivity?: string
  lastScanAt?: string
  lastDiscoveredSummary?: string
  enabled: boolean
}

export const DiscoveryDashboardWidget: React.FC = () => {
  const { apiClient } = useAuth()
  const [modules, setModules] = useState<DiscoveryModule[]>([])
  const [loading, setLoading] = useState(true)
  const [actionLoading, setActionLoading] = useState<Record<string, boolean>>({})

  const fetchModules = async () => {
    try {
      const data = await apiClient<DiscoveryModule[]>('/api/discovery/modules')
      setModules(data)
    } catch (err) {
      console.error('Failed to fetch discovery modules status', err)
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    fetchModules()
    const interval = setInterval(fetchModules, 5000)
    return () => clearInterval(interval)
  }, [])

  const handleToggle = async (id: string, currentEnabled: boolean) => {
    try {
      setActionLoading((prev) => ({ ...prev, [id]: true }))
      await apiClient(`/api/discovery/modules/${id}/toggle`, {
        method: 'POST',
        body: JSON.stringify({ enabled: !currentEnabled }),
      })
      await fetchModules()
    } catch (err) {
      console.error('Failed to toggle module', err)
    } finally {
      setActionLoading((prev) => ({ ...prev, [id]: false }))
    }
  }

  const handleTrigger = async (id: string) => {
    try {
      setActionLoading((prev) => ({ ...prev, [id]: true }))
      await apiClient(`/api/discovery/modules/${id}/trigger`, { method: 'POST' })
      await fetchModules()
    } catch (err) {
      console.error('Failed to trigger scan', err)
    } finally {
      setTimeout(() => setActionLoading((prev) => ({ ...prev, [id]: false })), 1000)
    }
  }

  if (loading) {
    return (
      <div className="bg-slate-900/60 backdrop-blur-md border border-white/10 rounded-2xl p-6 text-slate-400 flex items-center justify-center">
        <RefreshCw className="w-5 h-5 animate-spin mr-2 text-cyan-400" /> Loading discovery modules status...
      </div>
    )
  }

  return (
    <div className="bg-slate-900/60 backdrop-blur-md border border-white/10 rounded-2xl p-6 shadow-xl space-y-4">
      <div className="flex items-center justify-between border-b border-white/10 pb-4">
        <div className="flex items-center gap-3">
          <div className="p-2.5 bg-cyan-500/20 text-cyan-400 rounded-xl border border-cyan-500/30">
            <Cpu className="w-6 h-6" />
          </div>
          <div>
            <h2 className="text-lg font-semibold text-slate-100 flex items-center gap-2">
              Network Discovery Modules
              <span className="text-xs px-2 py-0.5 rounded-full bg-cyan-500/10 text-cyan-400 border border-cyan-500/20 font-mono">
                Passive Sniffer
              </span>
            </h2>
            <p className="text-xs text-slate-400">
              Operational status, passive sniffer & on-demand scanning
            </p>
          </div>
        </div>
        <button
          onClick={fetchModules}
          className="p-2 bg-slate-800 hover:bg-slate-700 text-slate-300 rounded-lg transition-colors border border-white/5"
          title="Refresh status"
        >
          <RefreshCw className="w-4 h-4" />
        </button>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        {modules.map((mod) => {
          const isError = mod.status === 'ERROR'
          const isRunning = mod.status === 'RUNNING'
          const isDisabled = mod.status === 'DISABLED'
          const isLoading = actionLoading[mod.id]

          return (
            <div
              key={mod.id}
              className={`rounded-xl p-4 border transition-all flex flex-col justify-between space-y-3 ${
                isError
                  ? 'bg-red-950/30 border-red-500/40'
                  : isRunning
                  ? 'bg-slate-800/40 border-emerald-500/30 shadow-lg shadow-emerald-950/20'
                  : isDisabled
                  ? 'bg-slate-900/40 border-white/5 opacity-60'
                  : 'bg-slate-800/20 border-white/10'
              }`}
            >
              <div>
                <div className="flex items-start justify-between">
                  <div className="font-medium text-slate-200 text-sm flex items-center gap-2">
                    {mod.name}
                  </div>
                  <span
                    className={`text-xs px-2.5 py-1 rounded-full font-medium border flex items-center gap-1.5 ${
                      isRunning
                        ? 'bg-emerald-500/20 text-emerald-400 border-emerald-500/30'
                        : isError
                        ? 'bg-red-500/20 text-red-400 border-red-500/30 animate-pulse'
                        : isDisabled
                        ? 'bg-slate-800 text-slate-400 border-slate-700'
                        : 'bg-amber-500/20 text-amber-400 border-amber-500/30'
                    }`}
                  >
                    {isRunning && <span className="w-2 h-2 rounded-full bg-emerald-400 animate-ping" />}
                    {isError && <AlertTriangle className="w-3.5 h-3.5" />}
                    {isRunning ? 'Running' : isError ? 'Error' : isDisabled ? 'Disabled' : 'Stopped'}
                  </span>
                </div>

                {isError && mod.errorMessage && (
                  <div className="mt-3 p-3 bg-red-900/40 border border-red-500/30 rounded-lg text-xs text-red-200 flex items-start gap-2">
                    <ShieldAlert className="w-4 h-4 text-red-400 shrink-0 mt-0.5" />
                    <div>
                      <span className="font-semibold block text-red-300">Container capability required:</span>
                      {mod.errorMessage}
                    </div>
                  </div>
                )}

                {!isError && (
                  <div className="mt-3 text-xs space-y-1.5 text-slate-300">
                    <div className="flex items-center gap-1.5 text-slate-400">
                      <Activity className="w-3.5 h-3.5 text-cyan-400" />
                      <span>{mod.currentActivity || 'Idle'}</span>
                    </div>
                    {mod.lastDiscoveredSummary && (
                      <div className="text-slate-400 font-mono text-[11px] bg-slate-950/40 p-1.5 rounded border border-white/5 truncate">
                        {mod.lastDiscoveredSummary}
                      </div>
                    )}
                    {mod.lastScanAt && (
                      <div className="text-[10px] text-slate-500">
                        Last scan/event: {new Date(mod.lastScanAt).toLocaleTimeString()}
                      </div>
                    )}
                  </div>
                )}
              </div>

              <div className="flex items-center justify-between pt-2 border-t border-white/5 text-xs">
                <button
                  onClick={() => handleToggle(mod.id, mod.enabled)}
                  disabled={isLoading}
                  className={`px-3 py-1.5 rounded-lg border flex items-center gap-1.5 font-medium transition-colors ${
                    mod.enabled
                      ? 'bg-slate-800 hover:bg-slate-700 text-slate-300 border-white/10'
                      : 'bg-emerald-950/40 hover:bg-emerald-900/40 text-emerald-300 border-emerald-500/30'
                  }`}
                >
                  <Power className="w-3.5 h-3.5" />
                  {mod.enabled ? 'Disable' : 'Enable'}
                </button>

                <button
                  onClick={() => handleTrigger(mod.id)}
                  disabled={isLoading || !mod.enabled}
                  className="px-3 py-1.5 bg-cyan-600/30 hover:bg-cyan-600/50 text-cyan-300 border border-cyan-500/30 rounded-lg flex items-center gap-1.5 font-medium transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
                >
                  <Play className={`w-3.5 h-3.5 ${isLoading ? 'animate-spin' : ''}`} />
                  Scan Now
                </button>
              </div>
            </div>
          )
        })}
      </div>
      <LiveDiscoveryFeed />
    </div>
  )
}
