import React, { useEffect, useState } from 'react'
import { useAuth } from '../lib/auth/auth-context'
import { ShieldAlert, CheckCircle, ShieldCheck, MessageSquare, Key, Network, AlertOctagon } from 'lucide-react'

export interface ThreatEvent {
  id: string
  severity: string
  description: string
  physicalDeviceId: string
  ipAddress: string
  macAddress: string
  detectedAt: string
  resolved: boolean
  notes?: string
}

interface Device {
  id: string
  displayName: string
}

export const Alerts: React.FC = () => {
  const { apiClient } = useAuth()
  const [threats, setThreats] = useState<ThreatEvent[]>([])
  const [devices, setDevices] = useState<{ [key: string]: string }>({})
  const [loading, setLoading] = useState(true)
  
  const [hostnameEdits, setHostnameEdits] = useState<{ [key: string]: string }>({})
  const [noteEdits, setNoteEdits] = useState<{ [key: string]: string }>({})
  const [expandedNotes, setExpandedNotes] = useState<{ [key: string]: boolean }>({})

  const fetchData = async () => {
    try {
      const [threatsData, devicesData] = await Promise.all([
        apiClient<ThreatEvent[]>('/api/threats'),
        apiClient<Device[]>('/api/devices')
      ])
      setThreats(threatsData)
      
      const devMap: { [key: string]: string } = {}
      devicesData.forEach(d => { devMap[d.id] = d.displayName })
      setDevices(devMap)
    } catch (err) {
      console.error('Failed to fetch data', err)
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    fetchData()
  }, [])

  const handleResolve = async (id: string) => {
    try {
      await apiClient(`/api/threats/${id}/resolve`, { method: 'PUT' })
      fetchData()
    } catch (err) {
      console.error('Failed to resolve threat', err)
    }
  }

  const handleAcceptSshKey = async (id: string) => {
    try {
      await apiClient(`/api/threats/${id}/accept-ssh-key`, { method: 'PUT' })
      fetchData()
    } catch (err) {
      console.error('Failed to accept SSH key', err)
    }
  }

  const handleSaveNote = async (id: string) => {
    const text = noteEdits[id]
    if (text === undefined) return
    
    try {
      await apiClient(`/api/threats/${id}/note`, { 
        method: 'PUT',
        body: JSON.stringify({ notes: text })
      })
      fetchData()
      setExpandedNotes(prev => ({ ...prev, [id]: false }))
    } catch (err) {
      console.error('Failed to save note', err)
    }
  }

  const handleSetHostnameAndResolve = async (threat: ThreatEvent) => {
    const newName = hostnameEdits[threat.id]
    if (!newName || newName.trim() === '') return

    try {
      if (threat.physicalDeviceId) {
        await apiClient(`/api/devices/${threat.physicalDeviceId}`, {
          method: 'PUT',
          body: JSON.stringify({ displayName: newName })
        })
      }
      await apiClient(`/api/threats/${threat.id}/resolve`, { method: 'PUT' })
      
      setHostnameEdits(prev => {
        const next = { ...prev }
        delete next[threat.id]
        return next
      })
      fetchData()
    } catch (err) {
      console.error('Failed to update hostname and resolve threat', err)
    }
  }

  if (loading) {
    return (
      <div className="flex items-center justify-center h-full">
        <div className="w-8 h-8 border-4 border-accent-danger border-t-transparent rounded-full animate-spin" />
      </div>
    )
  }

  return (
    <div className="space-y-6 animate-fade-in select-none">
      <div className="flex justify-between items-center">
        <div>
          <h1 className="text-2xl font-bold tracking-tight">Security Alerts</h1>
          <p className="text-text-secondary text-sm">Manage network threats and device mutations</p>
        </div>
      </div>

      <div className="bg-bg-surface border border-border-subtle rounded-2xl p-6 shadow-lg flex flex-col space-y-5">
        <div className="flex items-center gap-3 border-b border-border-subtle pb-4">
          <ShieldAlert className="w-6 h-6 text-accent-danger" />
          <h2 className="text-lg font-bold tracking-tight">All Alerts Log</h2>
        </div>

        <div className="flex-1 space-y-4">
          {threats.length === 0 && (
            <div className="flex flex-col items-center justify-center py-12 text-text-muted">
              <ShieldCheck className="w-12 h-12 mb-4 opacity-50" />
              <p className="text-sm font-semibold">No alerts detected</p>
              <p className="text-xs">Your network is currently secure.</p>
            </div>
          )}

          {threats.map((threat) => {
            const isUnknownHostname = threat.description.includes('Hostname: Unknown') || threat.description.includes('from Unknown')
            const isSshMutation = threat.description.includes('SSH Host Key mutation detected')
            const hasSshKey = isSshMutation && threat.description.includes('Key: ')
            const isCritical = threat.severity === 'CRITICAL'
            const deviceName = threat.physicalDeviceId ? devices[threat.physicalDeviceId] || 'Unknown Device' : 'Unassociated'

            return (
              <div 
                key={threat.id} 
                className={`flex flex-col gap-3 p-4 rounded-xl border transition-colors ${
                  threat.resolved 
                    ? 'bg-bg-surface border-border-subtle opacity-60' 
                    : isCritical
                      ? 'bg-red-950/20 border-red-500/60 shadow-md shadow-red-900/10'
                      : 'bg-accent-danger/5 border-accent-danger/30 shadow-sm shadow-accent-danger/5'
                }`}
              >
                <div className="flex items-start gap-4">
                  <div className={`mt-1 flex-shrink-0 ${threat.resolved ? 'text-text-muted' : isCritical ? 'text-red-500 animate-pulse' : 'text-accent-danger animate-pulse-slow'}`}>
                    {threat.resolved ? <CheckCircle className="w-5 h-5" /> : isCritical ? <AlertOctagon className="w-6 h-6" /> : <ShieldAlert className="w-5 h-5" />}
                  </div>
                  
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center justify-between mb-2">
                      <div className="flex items-center gap-3">
                        <span className={`text-[10px] font-bold uppercase tracking-wider px-2 py-0.5 rounded-full ${
                          threat.resolved 
                            ? 'bg-bg-surface-raised text-text-secondary' 
                            : isCritical
                              ? 'bg-red-500/20 text-red-500 border border-red-500/50'
                              : threat.severity === 'HIGH' ? 'bg-accent-danger/20 text-accent-danger' : 'bg-accent-warning/20 text-accent-warning'
                        }`}>
                          {threat.severity} SEVERITY
                        </span>
                        
                        <div className="flex items-center gap-1.5 px-2 py-0.5 rounded bg-bg-surface-raised border border-border-subtle text-[10px] text-text-secondary font-semibold max-w-[200px] truncate" title={deviceName}>
                          <Network className="w-3 h-3" />
                          {deviceName}
                        </div>
                      </div>
                      
                      <span className="text-[10px] text-text-muted font-mono">
                        {new Date(threat.detectedAt).toLocaleString()}
                      </span>
                    </div>

                    <p className={`text-sm font-bold leading-relaxed mb-3 ${threat.resolved ? 'text-text-secondary' : isCritical ? 'text-red-400 text-base' : 'text-text-primary'}`}>
                      {threat.description}
                    </p>
                    
                    <div className="flex flex-wrap items-center gap-3">
                      <div className="flex items-center gap-4 text-[11px] text-text-muted font-mono bg-bg-surface-raised/50 p-2 rounded-lg">
                        <span>IP: {threat.ipAddress}</span>
                        <span>MAC: {threat.macAddress}</span>
                      </div>
                      
                      {threat.notes && !expandedNotes[threat.id] && (
                        <div className="text-[11px] text-text-secondary bg-accent-info/5 border border-accent-info/20 px-3 py-1.5 rounded-lg flex items-center gap-2 max-w-md truncate">
                          <MessageSquare className="w-3 h-3 text-accent-info" />
                          {threat.notes}
                        </div>
                      )}
                    </div>
                  </div>
                </div>
                
                {expandedNotes[threat.id] && (
                  <div className="mt-2 pl-9">
                    <textarea 
                      className="w-full bg-bg-surface-raised border border-border-subtle rounded-lg p-3 text-xs text-text-primary focus:outline-none focus:border-accent-primary min-h-[80px]"
                      placeholder="Add an investigation note, remediation steps, etc..."
                      value={noteEdits[threat.id] !== undefined ? noteEdits[threat.id] : (threat.notes || '')}
                      onChange={(e) => setNoteEdits({ ...noteEdits, [threat.id]: e.target.value })}
                    />
                    <div className="flex justify-end gap-2 mt-2">
                      <button 
                        onClick={() => setExpandedNotes({ ...expandedNotes, [threat.id]: false })}
                        className="px-3 py-1.5 text-[10px] font-bold uppercase tracking-wider text-text-muted hover:text-text-primary"
                      >
                        Cancel
                      </button>
                      <button 
                        onClick={() => handleSaveNote(threat.id)}
                        className="px-3 py-1.5 bg-accent-primary hover:bg-accent-primary/90 text-text-primary text-[10px] font-bold uppercase tracking-wider rounded-lg transition-colors"
                      >
                        Save Note
                      </button>
                    </div>
                  </div>
                )}

                {!threat.resolved && !expandedNotes[threat.id] && (
                  <div className="mt-3 pt-3 border-t border-border-subtle flex items-center justify-between pl-9 flex-wrap gap-3">
                    
                    <div className="flex items-center gap-3">
                      {isUnknownHostname && threat.physicalDeviceId && (
                        <div className="flex items-center gap-3 mr-4">
                          <input
                            type="text"
                            placeholder="Assign a hostname..."
                            className="bg-bg-surface-raised border border-border-subtle rounded-lg py-1.5 px-3 text-xs text-text-primary focus:outline-none focus:border-accent-primary w-40"
                            value={hostnameEdits[threat.id] || ''}
                            onChange={(e) => setHostnameEdits({...hostnameEdits, [threat.id]: e.target.value})}
                            onKeyDown={(e) => {
                              if (e.key === 'Enter') handleSetHostnameAndResolve(threat)
                            }}
                          />
                          <button 
                            onClick={() => handleSetHostnameAndResolve(threat)}
                            disabled={!hostnameEdits[threat.id]}
                            className="px-3 py-1.5 bg-accent-primary hover:bg-accent-primary/90 disabled:opacity-50 disabled:cursor-not-allowed text-text-primary text-[10px] font-bold uppercase tracking-wider rounded-lg transition-colors"
                          >
                            Save & Resolve
                          </button>
                        </div>
                      )}

                      {isSshMutation && hasSshKey && (
                        <button 
                          onClick={() => handleAcceptSshKey(threat.id)}
                          className="flex items-center gap-1.5 px-3 py-1.5 bg-accent-info hover:bg-accent-info/90 text-text-primary text-[10px] font-bold uppercase tracking-wider rounded-lg transition-colors mr-4"
                        >
                          <Key className="w-3.5 h-3.5" />
                          Accept New Key
                        </button>
                      )}
                      
                      <button 
                        onClick={() => {
                          setExpandedNotes({ ...expandedNotes, [threat.id]: true })
                          setNoteEdits({ ...noteEdits, [threat.id]: threat.notes || '' })
                        }}
                        className="flex items-center gap-1.5 px-3 py-1.5 border border-border-subtle hover:border-accent-info hover:text-accent-info text-text-secondary text-[10px] font-bold uppercase tracking-wider rounded-lg transition-colors"
                      >
                        <MessageSquare className="w-3.5 h-3.5" />
                        {threat.notes ? 'Edit Note' : 'Add Note'}
                      </button>
                    </div>

                    <button 
                      onClick={() => handleResolve(threat.id)} 
                      className="px-3 py-1.5 border border-border-subtle hover:border-text-secondary text-text-secondary hover:text-text-primary text-[10px] font-bold uppercase tracking-wider rounded-lg transition-colors ml-auto"
                    >
                      Mark as Resolved
                    </button>
                  </div>
                )}
                
                {threat.resolved && !expandedNotes[threat.id] && (
                  <div className="mt-2 pt-2 border-t border-border-subtle flex justify-end pl-9">
                    <button 
                        onClick={() => {
                          setExpandedNotes({ ...expandedNotes, [threat.id]: true })
                          setNoteEdits({ ...noteEdits, [threat.id]: threat.notes || '' })
                        }}
                        className="flex items-center gap-1.5 px-3 py-1.5 text-text-muted hover:text-text-primary text-[10px] font-bold uppercase tracking-wider transition-colors"
                      >
                        <MessageSquare className="w-3.5 h-3.5" />
                        {threat.notes ? 'Edit Note' : 'Add Note'}
                    </button>
                  </div>
                )}
              </div>
            )
          })}
        </div>
      </div>
    </div>
  )
}
