import React, { useEffect, useState } from 'react'
import { Activity, Radio, Target, CheckCircle2, XCircle } from 'lucide-react'

export interface DiscoveryActivityEvent {
  type: string
  action: string
  ipAddress: string
  details: string
  timestamp: string
}

export const LiveDiscoveryFeed: React.FC = () => {
  const [events, setEvents] = useState<DiscoveryActivityEvent[]>([])

  useEffect(() => {
    const wsUrl = `${window.location.protocol === 'https:' ? 'wss:' : 'ws:'}//${window.location.host}/ws/events`
    const ws = new WebSocket(wsUrl)

    ws.onmessage = (event) => {
      try {
        const data = JSON.parse(event.data)
        if (data.type === 'ACTIVITY') {
          setEvents((prev) => [data, ...prev].slice(0, 50)) // Keep last 50 events
        }
      } catch (err) {
        console.error('Failed to parse websocket message', err)
      }
    }

    return () => {
      ws.close()
    }
  }, [])

  const getActionIcon = (action: string, details: string) => {
    if (action === 'TARGETED_SCAN_RESULT' && details.includes('Did not respond')) {
        return <XCircle className="w-4 h-4 text-red-400" />
    }
    switch (action) {
      case 'EBPF_HEARTBEAT': return <Activity className="w-4 h-4 text-cyan-400" />
      case 'TARGETED_SCAN': return <Target className="w-4 h-4 text-amber-400" />
      case 'TARGETED_SCAN_RESULT': return <CheckCircle2 className="w-4 h-4 text-green-400" />
      default: return <Radio className="w-4 h-4 text-slate-400" />
    }
  }

  const getActionColor = (action: string, details: string) => {
    if (action === 'TARGETED_SCAN_RESULT' && details.includes('Did not respond')) {
        return 'bg-red-500/10 border-red-500/20'
    }
    switch (action) {
      case 'EBPF_HEARTBEAT': return 'bg-cyan-500/10 border-cyan-500/20'
      case 'TARGETED_SCAN': return 'bg-amber-500/10 border-amber-500/20'
      case 'TARGETED_SCAN_RESULT': return 'bg-green-500/10 border-green-500/20'
      default: return 'bg-slate-500/10 border-slate-500/20'
    }
  }

  return (
    <div className="flex flex-col h-64 overflow-hidden rounded-xl bg-slate-900/40 border border-white/5 mt-4">
      <div className="p-3 border-b border-white/5 bg-slate-900/60 flex items-center justify-between">
        <h3 className="text-sm font-semibold text-slate-300 flex items-center gap-2">
          <Radio className="w-4 h-4 text-cyan-400 animate-pulse" />
          Live Discovery Stream
        </h3>
        <span className="text-xs text-slate-500">{events.length} events</span>
      </div>
      
      <div className="flex-1 overflow-y-auto p-3 space-y-2 custom-scrollbar">
        {events.length === 0 ? (
          <div className="h-full flex items-center justify-center text-xs text-slate-500 italic">
            Waiting for network activity...
          </div>
        ) : (
          events.map((ev, i) => (
            <div 
              key={i} 
              className={`flex items-start gap-3 p-2 rounded-lg border ${getActionColor(ev.action, ev.details)} transition-all animate-in fade-in slide-in-from-top-2 duration-300`}
            >
              <div className="mt-0.5">
                {getActionIcon(ev.action, ev.details)}
              </div>
              <div className="flex-1 min-w-0">
                <div className="flex items-center justify-between">
                  <span className="text-sm font-mono text-slate-200">{ev.ipAddress}</span>
                  <span className="text-[10px] text-slate-500">
                    {new Date(ev.timestamp).toLocaleTimeString()}
                  </span>
                </div>
                <p className="text-xs text-slate-400 mt-0.5 truncate">{ev.details}</p>
              </div>
            </div>
          ))
        )}
      </div>
    </div>
  )
}
