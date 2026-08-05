import React, { useEffect, useState } from 'react'
import { Link, useParams } from '@tanstack/react-router'
import { useAuth } from '../lib/auth/auth-context'
import {
  ArrowLeft,
  Calendar,
  Cpu,
  Eye,
  EyeOff,
  KeyRound,
  MapPin,
  ShieldCheck,
  Clock,
  HardDrive,
  Plus,
  Edit2,
  Save,
  X,
  Terminal as TerminalIcon
} from 'lucide-react'
import { useVault } from '../lib/vault/vault-context'
import { Terminal } from '../components/Terminal'

interface Identity {
  id: string
  ipAddress: string
  macAddress: string
  hostname: string
  firstSeen: string
  lastSeen: string
  current: boolean
}

interface Fingerprint {
  id: string
  version: number
  dhcpOption55: string
  dhcpOption60: string
  tcpFingerprint: string
  mdnsServices: string[]
  ssdpUsn: string
  sshBanner: string
  httpServerHeader: string
  tlsJa4: string
  tlsCertSubject: string
  openPorts: number[]
  macOui: string
  capturedAt: string
}

interface Credential {
  id: string
  label: string
  credentialType: string
  username: string
  port: number
}

interface Device {
  id: string
  displayName: string
  deviceType: string
  osFamily: string
  osVersion: string
  manufacturer: string
  model: string
  locationNote: string
  confidenceScore: number
  status: string
  managementState: string
  labels: string[]
  firstSeen: string
  lastSeen: string
  identities: Identity[]
  fingerprints: Fingerprint[]
  credentials: Credential[]
}

interface TelemetryPoint {
  id: {
    time: string
    metricName: string
  }
  value: number
}

export const DeviceDetail: React.FC = () => {
  const params = useParams({ strict: false }) as { id?: string }
  const deviceId = params.id
  const { apiClient } = useAuth()
  
  const [device, setDevice] = useState<Device | null>(null)
  const [telemetry, setTelemetry] = useState<TelemetryPoint[]>([])
  const [loading, setLoading] = useState(true)
  const [activeTab, setActiveTab] = useState<'overview' | 'identities' | 'fingerprint' | 'credentials' | 'monitor' | 'settings'>('overview')
  const [newLabel, setNewLabel] = useState('')
  const [revealedCreds, setRevealedCreds] = useState<Record<string, string>>({})
  const { sealed, setShowUnsealModal } = useVault()
  const [showAddCred, setShowAddCred] = useState(false)
  const [newCred, setNewCred] = useState({ label: '', type: 'SSH_KEY', username: '', port: '', secret: '' })
  const [activeTerminalCredId, setActiveTerminalCredId] = useState<string | null>(null)
  
  const [isEditing, setIsEditing] = useState(false)
  const [editForm, setEditForm] = useState({
    displayName: '',
    deviceType: '',
    manufacturer: '',
    model: '',
    osFamily: '',
    osVersion: '',
    locationNote: ''
  })

  useEffect(() => {
    if (!deviceId) return

    Promise.all([
      apiClient<Device>(`/api/devices/${deviceId}`),
      apiClient<TelemetryPoint[]>(`/api/devices/${deviceId}/telemetry`)
    ])
      .then(([deviceData, telemetryData]) => {
        setDevice(deviceData)
        setTelemetry(telemetryData)
        setLoading(false)
      })
      .catch((err) => {
        console.error(err)
        setLoading(false)
      })
  }, [deviceId])

  const toggleRevealCred = async (id: string) => {
    if (revealedCreds[id] !== undefined) {
      const newCreds = { ...revealedCreds }
      delete newCreds[id]
      setRevealedCreds(newCreds)
      return
    }

    if (sealed) {
      setShowUnsealModal(true)
      return
    }

    try {
      const res = await apiClient<{secret: string}>(`/api/credentials/${id}/reveal`)
      setRevealedCreds(prev => ({ ...prev, [id]: res.secret }))
    } catch (e: any) {
      if (e.message?.includes('sealed') || e.message?.includes('FORBIDDEN') || e.message?.includes('Unauthorized')) {
        setShowUnsealModal(true)
      }
    }
  }

  const handleAddCredential = async (e: React.FormEvent) => {
    e.preventDefault()
    if (sealed) {
      setShowUnsealModal(true)
      return
    }
    try {
      await apiClient(`/api/credentials/device/${deviceId}`, {
        method: 'POST',
        body: JSON.stringify({ ...newCred, port: newCred.port ? parseInt(newCred.port) : null })
      })
      setShowAddCred(false)
      setNewCred({ label: '', type: 'SSH_KEY', username: '', port: '', secret: '' })
      // reload device
      const deviceData = await apiClient<Device>(`/api/devices/${deviceId}`)
      setDevice(deviceData)
    } catch (e: any) {
      if (e.message?.includes('sealed') || e.message?.includes('FORBIDDEN') || e.message?.includes('Unauthorized')) {
        setShowUnsealModal(true)
      }
    }
  }

  const handleSaveEdits = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!device) return
    try {
      await apiClient(`/api/devices/${deviceId}`, {
        method: 'PUT',
        body: JSON.stringify(editForm)
      })
      const deviceData = await apiClient<Device>(`/api/devices/${deviceId}`)
      setDevice(deviceData)
      setIsEditing(false)
    } catch (err) {
      console.error(err)
    }
  }

  const handleUpdateManagementState = async (state: string) => {
    try {
      const updatedDevice = await apiClient<Device>(`/api/devices/${deviceId}/state`, {
        method: 'PUT',
        body: JSON.stringify({ managementState: state })
      })
      setDevice(updatedDevice)
    } catch (e) {
      console.error(e)
    }
  }

  const handleAddLabel = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!newLabel || !device) return
    const updatedLabels = [...(device.labels || []), newLabel]
    try {
      const updatedDevice = await apiClient<Device>(`/api/devices/${deviceId}/labels`, {
        method: 'PUT',
        body: JSON.stringify(updatedLabels)
      })
      setDevice(updatedDevice)
      setNewLabel('')
    } catch (e) {
      console.error(e)
    }
  }

  const handleRemoveLabel = async (labelToRemove: string) => {
    if (!device) return
    const updatedLabels = (device.labels || []).filter(l => l !== labelToRemove)
    try {
      const updatedDevice = await apiClient<Device>(`/api/devices/${deviceId}/labels`, {
        method: 'PUT',
        body: JSON.stringify(updatedLabels)
      })
      setDevice(updatedDevice)
    } catch (e) {
      console.error(e)
    }
  }

  if (loading) {
    return (
      <div className="flex items-center justify-center h-full">
        <div className="w-8 h-8 border-4 border-accent-primary border-t-transparent rounded-full animate-spin" />
      </div>
    )
  }

  if (!device) {
    return (
      <div className="text-center p-8 bg-bg-surface border border-border-subtle rounded-2xl">
        <p className="text-text-secondary">Device not found or error loading.</p>
        <Link to="/devices" className="text-accent-primary mt-4 inline-block hover:underline">
          Back to Devices List
        </Link>
      </div>
    )
  }

  // Filter telemetry by type
  const filterMetrics = (name: string) => {
    return telemetry
      .filter((t) => t.id.metricName === name)
      .map((t) => t.value)
  }

  const cpuData = filterMetrics('cpu_percent')
  const memData = filterMetrics('mem_percent')
  const pingData = filterMetrics('ping_ms')

  const latestCpu = cpuData[cpuData.length - 1] || 0
  const latestMem = memData[memData.length - 1] || 0
  const latestPing = pingData[pingData.length - 1] || 0

  const getSparklineSvg = (data: number[], color: string) => {
    if (data.length === 0) return null
    const max = Math.max(...data, 1)
    const min = Math.min(...data, 0)
    const range = max - min
    const width = 120
    const height = 30
    const points = data
      .map((val, index) => {
        const x = (index / (data.length - 1)) * width
        const y = height - ((val - min) / range) * height
        return `${x},${y}`
      })
      .join(' ')

    return (
      <svg className="w-24 h-6 overflow-visible" viewBox={`0 0 ${width} ${height}`}>
        <polyline fill="none" stroke={color} strokeWidth="1.5" points={points} />
      </svg>
    )
  }


  const currentIdentity = device.identities?.find((id) => id.current)
  const latestFingerprint = device.fingerprints?.[0]

  return (
    <div className="space-y-6 animate-fade-in select-none">
      {activeTerminalCredId && (
        <div className="fixed inset-0 z-50 bg-bg-base/90 backdrop-blur-sm flex items-center justify-center p-8 animate-fade-in">
          <div className="w-full h-full max-w-6xl max-h-[800px]">
            <Terminal 
              deviceId={device.id} 
              credentialId={activeTerminalCredId} 
              onClose={() => setActiveTerminalCredId(null)} 
            />
          </div>
        </div>
      )}

      {/* Header back button */}
      <div className="flex items-center gap-4">
        <Link
          to="/devices"
          className="p-2.5 rounded-xl bg-bg-surface border border-border-subtle hover:bg-bg-surface-raised text-text-secondary hover:text-text-primary transition-all cursor-pointer"
        >
          <ArrowLeft className="w-4 h-4" />
        </Link>
        <div>
          <div className="flex items-center gap-3">
            <h1 className="text-xl font-bold tracking-tight">{device.displayName}</h1>
            <span className={`w-2 h-2 rounded-full ${
              device.status === 'ONLINE' ? 'bg-accent-success animate-pulse' : 'bg-accent-danger'
            }`} />
            <span className="text-[10px] font-bold uppercase tracking-wider text-text-secondary">
              {device.status}
            </span>
          </div>
          <p className="text-xs text-text-secondary font-mono mt-0.5">
            {currentIdentity?.ipAddress || 'Unknown IP'} · {device.manufacturer} {device.model}
          </p>
        </div>
      </div>

      {/* Navigation tabs */}
      <div className="flex items-center border-b border-border-subtle gap-2 overflow-x-auto">
        {(['overview', 'identities', 'fingerprint', 'credentials', 'monitor', 'settings'] as const).map((tab) => (
          <button
            key={tab}
            onClick={() => setActiveTab(tab)}
            className={`py-3.5 px-5 text-xs font-semibold uppercase tracking-wider border-b-2 cursor-pointer transition-all ${
              activeTab === tab
                ? 'border-accent-primary text-accent-primary'
                : 'border-transparent text-text-secondary hover:text-text-primary'
            }`}
          >
            {tab}
          </button>
        ))}
      </div>

      {/* Tab Contents */}
      <div className="mt-6">
        {/* OVERVIEW TAB */}
        {activeTab === 'overview' && (
          <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
            {/* Specs card */}
            <div className="bg-bg-surface border border-border-subtle rounded-2xl p-6 shadow-lg relative">
              <div className="flex justify-between items-center mb-5">
                <h3 className="font-bold text-sm tracking-tight">System Specifications</h3>
                {!isEditing && (
                  <button onClick={() => {
                    setEditForm({
                      displayName: device.displayName || '',
                      deviceType: device.deviceType || 'UNKNOWN',
                      manufacturer: device.manufacturer || '',
                      model: device.model || '',
                      osFamily: device.osFamily || '',
                      osVersion: device.osVersion || '',
                      locationNote: device.locationNote || ''
                    })
                    setIsEditing(true)
                  }} className="text-text-secondary hover:text-accent-primary transition-colors cursor-pointer p-1 rounded-lg hover:bg-bg-surface-raised">
                    <Edit2 className="w-4 h-4" />
                  </button>
                )}
              </div>
              
              {isEditing ? (
                <form onSubmit={handleSaveEdits} className="space-y-4 text-xs">
                  <div>
                    <label className="text-text-secondary block mb-1">Display Name</label>
                    <input type="text" value={editForm.displayName} onChange={e => setEditForm({...editForm, displayName: e.target.value})} className="w-full bg-bg-surface-raised border border-border-subtle rounded-lg px-3 py-2 text-text-primary focus:border-accent-primary focus:outline-none" required />
                  </div>
                  <div className="grid grid-cols-2 gap-3">
                    <div>
                      <label className="text-text-secondary block mb-1">Device Type</label>
                      <select value={editForm.deviceType} onChange={e => setEditForm({...editForm, deviceType: e.target.value})} className="w-full bg-bg-surface-raised border border-border-subtle rounded-lg px-3 py-2 text-text-primary focus:border-accent-primary focus:outline-none">
                        <option value="ROUTER">Router</option>
                        <option value="SWITCH">Switch</option>
                        <option value="FIREWALL">Firewall</option>
                        <option value="SERVER">Server</option>
                        <option value="NAS">NAS</option>
                        <option value="PRINTER">Printer</option>
                        <option value="IOT">IoT</option>
                        <option value="WORKSTATION">Workstation</option>
                        <option value="LAPTOP">Laptop</option>
                        <option value="PHONE">Phone</option>
                        <option value="TABLET">Tablet</option>
                        <option value="UNKNOWN">Unknown</option>
                      </select>
                    </div>
                    <div>
                      <label className="text-text-secondary block mb-1">Location</label>
                      <input type="text" value={editForm.locationNote} onChange={e => setEditForm({...editForm, locationNote: e.target.value})} className="w-full bg-bg-surface-raised border border-border-subtle rounded-lg px-3 py-2 text-text-primary focus:border-accent-primary focus:outline-none" />
                    </div>
                  </div>
                  <div className="grid grid-cols-2 gap-3">
                    <div>
                      <label className="text-text-secondary block mb-1">Manufacturer</label>
                      <input type="text" value={editForm.manufacturer} onChange={e => setEditForm({...editForm, manufacturer: e.target.value})} className="w-full bg-bg-surface-raised border border-border-subtle rounded-lg px-3 py-2 text-text-primary focus:border-accent-primary focus:outline-none" />
                    </div>
                    <div>
                      <label className="text-text-secondary block mb-1">Model</label>
                      <input type="text" value={editForm.model} onChange={e => setEditForm({...editForm, model: e.target.value})} className="w-full bg-bg-surface-raised border border-border-subtle rounded-lg px-3 py-2 text-text-primary focus:border-accent-primary focus:outline-none" />
                    </div>
                  </div>
                  <div className="grid grid-cols-2 gap-3">
                    <div>
                      <label className="text-text-secondary block mb-1">OS Family</label>
                      <input type="text" value={editForm.osFamily} onChange={e => setEditForm({...editForm, osFamily: e.target.value})} className="w-full bg-bg-surface-raised border border-border-subtle rounded-lg px-3 py-2 text-text-primary focus:border-accent-primary focus:outline-none" />
                    </div>
                    <div>
                      <label className="text-text-secondary block mb-1">OS Version</label>
                      <input type="text" value={editForm.osVersion} onChange={e => setEditForm({...editForm, osVersion: e.target.value})} className="w-full bg-bg-surface-raised border border-border-subtle rounded-lg px-3 py-2 text-text-primary focus:border-accent-primary focus:outline-none" />
                    </div>
                  </div>
                  <div className="flex justify-end gap-2 pt-2 border-t border-border-subtle/50">
                    <button type="button" onClick={() => setIsEditing(false)} className="px-3 py-1.5 rounded-lg border border-border-subtle hover:bg-bg-surface-raised cursor-pointer flex items-center gap-1 font-semibold">
                      <X className="w-3 h-3" /> Cancel
                    </button>
                    <button type="submit" className="px-3 py-1.5 rounded-lg bg-accent-primary text-text-primary hover:bg-accent-primary/90 cursor-pointer flex items-center gap-1 font-semibold">
                      <Save className="w-3 h-3" /> Save
                    </button>
                  </div>
                </form>
              ) : (
                <div className="space-y-3.5 text-xs">
                  <div className="flex justify-between border-b border-border-subtle/50 pb-2">
                    <span className="text-text-secondary">Device Category</span>
                    <span className="font-semibold text-text-primary uppercase">{device.deviceType}</span>
                  </div>
                  <div className="flex justify-between border-b border-border-subtle/50 pb-2">
                    <span className="text-text-secondary">Manufacturer</span>
                    <span className="font-semibold text-text-primary">{device.manufacturer || '-'}</span>
                  </div>
                  <div className="flex justify-between border-b border-border-subtle/50 pb-2">
                    <span className="text-text-secondary">Model Number</span>
                    <span className="font-semibold text-text-primary">{device.model || '-'}</span>
                  </div>
                  <div className="flex justify-between border-b border-border-subtle/50 pb-2">
                    <span className="text-text-secondary">OS / Kernel</span>
                    <span className="font-semibold text-text-primary">
                      {device.osFamily} {device.osVersion}
                    </span>
                  </div>
                  <div className="flex justify-between items-center border-b border-border-subtle/50 pb-2">
                    <span className="text-text-secondary">Physical Location</span>
                    <span className="font-semibold text-text-primary flex items-center gap-1.5">
                      <MapPin className="w-3.5 h-3.5 text-accent-danger" />
                      {device.locationNote || 'Not configured'}
                    </span>
                  </div>
                </div>
              )}
            </div>

            {/* Timestamps & Fingerprint Match */}
            <div className="bg-bg-surface border border-border-subtle rounded-2xl p-6 space-y-5 shadow-lg flex flex-col justify-between">
              <div>
                <h3 className="font-bold text-sm tracking-tight">Fingerprint Integrity</h3>
                <div className="flex items-center gap-3.5 mt-4 p-4 rounded-xl bg-bg-surface-raised border border-border-subtle">
                  <ShieldCheck className="w-8 h-8 text-accent-info" />
                  <div>
                    <h4 className="font-extrabold text-base text-text-primary">
                      {Math.round(device.confidenceScore * 100)}% Match
                    </h4>
                    <p className="text-[10px] text-text-muted mt-0.5 uppercase tracking-wider">
                      Fingerprint vector confidence
                    </p>
                  </div>
                </div>
              </div>

              <div className="space-y-3.5 text-xs">
                <div className="flex justify-between border-b border-border-subtle/50 pb-2">
                  <span className="text-text-secondary flex items-center gap-1.5">
                    <Calendar className="w-3.5 h-3.5 text-accent-primary" />
                    First Sighted
                  </span>
                  <span className="font-mono text-text-primary">
                    {new Date(device.firstSeen).toLocaleDateString()}
                  </span>
                </div>
                <div className="flex justify-between border-b border-border-subtle/50 pb-2">
                  <span className="text-text-secondary flex items-center gap-1.5">
                    <Clock className="w-3.5 h-3.5 text-accent-success" />
                    Last Sighted
                  </span>
                  <span className="font-mono text-text-primary">
                    {new Date(device.lastSeen).toLocaleTimeString()}
                  </span>
                </div>
              </div>
            </div>

            {/* Quick Metrics Sparklines */}
            <div className="bg-bg-surface border border-border-subtle rounded-2xl p-6 space-y-5 shadow-lg flex flex-col justify-between">
              <h3 className="font-bold text-sm tracking-tight">Quick Telemetry</h3>
              <div className="space-y-4 text-xs">
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-2">
                    <Cpu className="w-4 h-4 text-accent-primary" />
                    <span className="text-text-secondary">CPU Util</span>
                  </div>
                  <div className="flex items-center gap-3">
                    {getSparklineSvg(cpuData, '#3b82f6')}
                    <span className="font-mono font-bold text-text-primary w-8 text-right">
                      {Math.round(latestCpu)}%
                    </span>
                  </div>
                </div>

                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-2">
                    <HardDrive className="w-4 h-4 text-accent-success" />
                    <span className="text-text-secondary">RAM Util</span>
                  </div>
                  <div className="flex items-center gap-3">
                    {getSparklineSvg(memData, '#22c55e')}
                    <span className="font-mono font-bold text-text-primary w-8 text-right">
                      {Math.round(latestMem)}%
                    </span>
                  </div>
                </div>

                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-2">
                    <Clock className="w-4 h-4 text-accent-info" />
                    <span className="text-text-secondary">Ping Delay</span>
                  </div>
                  <div className="flex items-center gap-3">
                    {getSparklineSvg(pingData, '#06b6d4')}
                    <span className="font-mono font-bold text-text-primary w-8 text-right">
                      {Math.round(latestPing)}ms
                    </span>
                  </div>
                </div>
              </div>
            </div>
          </div>
        )}

        {/* IDENTITIES TAB */}
        {activeTab === 'identities' && (
          <div className="bg-bg-surface border border-border-subtle rounded-2xl overflow-hidden shadow-lg">
            <table className="w-full text-left border-collapse">
              <thead>
                <tr className="border-b border-border-subtle bg-bg-surface-raised/40 text-[10px] uppercase tracking-wider font-semibold text-text-secondary">
                  <th className="py-4 px-6">IP Address</th>
                  <th className="py-4 px-6">MAC Address</th>
                  <th className="py-4 px-6">Hostname</th>
                  <th className="py-4 px-6">First Seen</th>
                  <th className="py-4 px-6">Last Seen</th>
                  <th className="py-4 px-6 text-right">State</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border-subtle text-xs">
                {device.identities?.map((id) => (
                  <tr key={id.id} className="hover:bg-bg-surface-raised/30 transition-colors">
                    <td className="py-4 px-6 font-mono text-text-primary">{id.ipAddress}</td>
                    <td className="py-4 px-6 font-mono text-text-secondary">{id.macAddress}</td>
                    <td className="py-4 px-6 text-text-primary">{id.hostname || '-'}</td>
                    <td className="py-4 px-6 text-text-muted">{new Date(id.firstSeen).toLocaleString()}</td>
                    <td className="py-4 px-6 text-text-muted">{new Date(id.lastSeen).toLocaleString()}</td>
                    <td className="py-4 px-6 text-right">
                      {id.current ? (
                        <span className="px-2 py-0.5 rounded bg-accent-success/15 text-accent-success text-[10px] font-bold uppercase tracking-wider border border-accent-success/20">
                          Active
                        </span>
                      ) : (
                        <span className="px-2 py-0.5 rounded bg-bg-surface-raised border border-border-subtle text-text-secondary text-[10px] font-bold uppercase tracking-wider">
                          Historical
                        </span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        {/* FINGERPRINT TAB */}
        {activeTab === 'fingerprint' && latestFingerprint && (
          <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
            <div className="bg-bg-surface border border-border-subtle rounded-2xl p-6 space-y-4 shadow-lg">
              <h3 className="font-bold text-sm tracking-tight">Active Networking Probes</h3>
              <div className="space-y-3.5 text-xs">
                <div className="border-b border-border-subtle/50 pb-2.5">
                  <p className="text-[10px] text-text-muted uppercase font-bold tracking-wider">DHCP Option 55 Sequence</p>
                  <p className="font-mono text-xs text-text-primary mt-1 select-all">{latestFingerprint.dhcpOption55 || 'No DHCP option sniffing logged'}</p>
                </div>
                <div className="border-b border-border-subtle/50 pb-2.5">
                  <p className="text-[10px] text-text-muted uppercase font-bold tracking-wider">TCP p0f Stack Signature</p>
                  <p className="font-mono text-xs text-text-primary mt-1">{latestFingerprint.tcpFingerprint || '-'}</p>
                </div>
                <div className="border-b border-border-subtle/50 pb-2.5">
                  <p className="text-[10px] text-text-muted uppercase font-bold tracking-wider">DHCP Vendor Code (Option 60)</p>
                  <p className="font-mono text-xs text-text-primary mt-1">{latestFingerprint.dhcpOption60 || '-'}</p>
                </div>
                <div>
                  <p className="text-[10px] text-text-muted uppercase font-bold tracking-wider">TLS Client JA4 Hash</p>
                  <p className="font-mono text-xs text-text-primary mt-1">{latestFingerprint.tlsJa4 || '-'}</p>
                </div>
              </div>
            </div>

            <div className="bg-bg-surface border border-border-subtle rounded-2xl p-6 space-y-4 shadow-lg">
              <h3 className="font-bold text-sm tracking-tight">Service Banners & Ports</h3>
              <div className="space-y-3.5 text-xs">
                <div className="border-b border-border-subtle/50 pb-2.5">
                  <p className="text-[10px] text-text-muted uppercase font-bold tracking-wider">SSH Banner</p>
                  <p className="font-mono text-xs text-text-primary mt-1">{latestFingerprint.sshBanner || '-'}</p>
                </div>
                <div className="border-b border-border-subtle/50 pb-2.5">
                  <p className="text-[10px] text-text-muted uppercase font-bold tracking-wider">HTTP Server Header</p>
                  <p className="font-mono text-xs text-text-primary mt-1">{latestFingerprint.httpServerHeader || '-'}</p>
                </div>
                <div className="border-b border-border-subtle/50 pb-2.5">
                  <p className="text-[10px] text-text-muted uppercase font-bold tracking-wider">SSL Subject Name (CN)</p>
                  <p className="font-mono text-xs text-text-primary mt-1">{latestFingerprint.tlsCertSubject || '-'}</p>
                </div>
                <div>
                  <p className="text-[10px] text-text-muted uppercase font-bold tracking-wider">Open Ports Scan</p>
                  <div className="flex flex-wrap gap-1.5 mt-1.5">
                    {latestFingerprint.openPorts?.map((port) => (
                      <span key={port} className="px-2 py-0.5 rounded font-mono bg-bg-surface-raised border border-border-subtle text-accent-info text-[10px] font-bold">
                        {port}
                      </span>
                    )) || '-'}
                  </div>
                </div>
              </div>
            </div>
          </div>
        )}

        {/* CREDENTIALS TAB */}
        {activeTab === 'credentials' && (
          <div className="bg-bg-surface border border-border-subtle rounded-2xl p-6 space-y-6 shadow-lg">
            <div className="flex justify-between items-center">
              <div>
                <h3 className="font-bold text-sm tracking-tight">Access Credentials</h3>
                <p className="text-xs text-text-secondary">Sealed keys and credentials managed via envelope decryption</p>
              </div>
              <button
                onClick={() => setShowAddCred(!showAddCred)}
                className="flex items-center gap-1.5 px-3 py-2 bg-accent-primary hover:bg-accent-primary/90 text-text-primary rounded-xl text-xs font-semibold transition-colors cursor-pointer"
              >
                <Plus className="w-4 h-4" />
                Add Credential
              </button>
            </div>
            
            {showAddCred && (
              <form onSubmit={handleAddCredential} className="p-5 rounded-xl border border-border-subtle bg-bg-surface-raised space-y-4">
                <h4 className="text-xs font-bold uppercase tracking-wider text-text-secondary">New Credential</h4>
                <div className="grid grid-cols-2 gap-4">
                  <input type="text" placeholder="Label (e.g. Root SSH)" required className="bg-bg-surface border border-border-subtle rounded-lg py-2 px-3 text-xs w-full focus:outline-none focus:border-accent-primary" value={newCred.label} onChange={e => setNewCred({...newCred, label: e.target.value})} />
                  <select className="bg-bg-surface border border-border-subtle rounded-lg py-2 px-3 text-xs w-full focus:outline-none focus:border-accent-primary" value={newCred.type} onChange={e => setNewCred({...newCred, type: e.target.value})}>
                    <option value="SSH_KEY">SSH Key</option>
                    <option value="BASIC_AUTH">Basic Auth</option>
                    <option value="SNMP_V2">SNMP v2c</option>
                    <option value="SNMP_V3">SNMP v3</option>
                  </select>
                  <input type="text" placeholder="Username (Optional)" className="bg-bg-surface border border-border-subtle rounded-lg py-2 px-3 text-xs w-full focus:outline-none focus:border-accent-primary" value={newCred.username} onChange={e => setNewCred({...newCred, username: e.target.value})} />
                  <input type="number" placeholder="Port (Optional)" className="bg-bg-surface border border-border-subtle rounded-lg py-2 px-3 text-xs w-full focus:outline-none focus:border-accent-primary" value={newCred.port} onChange={e => setNewCred({...newCred, port: e.target.value})} />
                </div>
                <textarea placeholder="Secret Payload (Key, Password, etc)" required className="bg-bg-surface border border-border-subtle rounded-lg py-2 px-3 text-xs w-full min-h-[80px] font-mono focus:outline-none focus:border-accent-primary" value={newCred.secret} onChange={e => setNewCred({...newCred, secret: e.target.value})} />
                <div className="flex justify-end gap-2">
                  <button type="button" onClick={() => setShowAddCred(false)} className="px-4 py-2 rounded-lg border border-border-subtle text-xs font-semibold hover:bg-bg-surface cursor-pointer">Cancel</button>
                  <button type="submit" className="px-4 py-2 rounded-lg bg-accent-primary text-text-primary text-xs font-semibold hover:bg-accent-primary/90 cursor-pointer">Save Encrypted</button>
                </div>
              </form>
            )}

            {(!device.credentials || device.credentials.length === 0) ? (
              <div className="p-8 text-center border border-dashed border-border-subtle rounded-xl text-text-secondary text-xs">
                No credentials stored for this system.
              </div>
            ) : (
              <div className="space-y-4">
                {device.credentials.map((cred) => (
                  <div key={cred.id} className="p-4 rounded-xl bg-bg-surface-raised border border-border-subtle flex items-center justify-between gap-4">
                    <div className="flex items-center gap-3.5">
                      <div className="p-2.5 rounded-xl bg-accent-primary/10 border border-accent-primary/20 text-accent-primary">
                        <KeyRound className="w-4 h-4" />
                      </div>
                      <div>
                        <h4 className="font-bold text-xs text-text-primary">{cred.label}</h4>
                        <p className="text-[10px] text-text-secondary uppercase tracking-wider mt-0.5">
                          {cred.credentialType} · Username: <span className="font-mono text-text-primary">{cred.username || '-'}</span>
                        </p>
                      </div>
                    </div>

                    <div className="flex items-center gap-3">
                      {(cred.credentialType === 'SSH_KEY' || cred.credentialType === 'PASSWORD') && (
                        <button
                          onClick={() => setActiveTerminalCredId(cred.id)}
                          className="px-3 py-1.5 rounded-lg bg-accent-primary hover:bg-accent-primary/90 text-text-primary text-[10px] font-bold uppercase tracking-wider cursor-pointer transition-colors flex items-center gap-1.5"
                        >
                          <TerminalIcon className="w-3 h-3" /> Connect
                        </button>
                      )}
                      <span className="font-mono text-xs text-text-muted border-l border-border-subtle pl-3">
                        {revealedCreds[cred.id] !== undefined ? revealedCreds[cred.id] : '••••••••••••••••'}
                      </span>
                      <button
                        onClick={() => toggleRevealCred(cred.id)}
                        className="p-1.5 rounded-lg border border-border-subtle hover:bg-bg-surface text-text-secondary hover:text-text-primary cursor-pointer transition-colors"
                      >
                        {revealedCreds[cred.id] !== undefined ? <EyeOff className="w-4 h-4" /> : <Eye className="w-4 h-4" />}
                      </button>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>
        )}

        {/* SETTINGS TAB */}
        {activeTab === 'settings' && (
          <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
            <div className="bg-bg-surface border border-border-subtle rounded-2xl p-6 space-y-6 shadow-lg">
              <div>
                <h3 className="font-bold text-sm tracking-tight">Lifecycle Management</h3>
                <p className="text-xs text-text-secondary mt-1">Control how the Discovery Engine interacts with this device.</p>
              </div>
              
              <div className="space-y-3">
                <label className="flex items-start gap-3 p-3 rounded-xl border border-border-subtle bg-bg-surface-raised cursor-pointer hover:border-accent-primary/50 transition-colors">
                  <input type="radio" name="mgmtState" value="DISCOVERED" checked={device.managementState === 'DISCOVERED'} onChange={() => handleUpdateManagementState('DISCOVERED')} className="mt-1" />
                  <div>
                    <span className="block text-xs font-bold text-text-primary">Discovered (Auto-Merge)</span>
                    <span className="text-[10px] text-text-secondary">The discovery engine will automatically update the hostname and device type as it learns more.</span>
                  </div>
                </label>
                
                <label className="flex items-start gap-3 p-3 rounded-xl border border-border-subtle bg-bg-surface-raised cursor-pointer hover:border-accent-primary/50 transition-colors">
                  <input type="radio" name="mgmtState" value="MANAGED" checked={device.managementState === 'MANAGED'} onChange={() => handleUpdateManagementState('MANAGED')} className="mt-1" />
                  <div>
                    <span className="block text-xs font-bold text-text-primary">Managed (Locked)</span>
                    <span className="text-[10px] text-text-secondary">Your custom edits are preserved. The discovery engine will NOT overwrite the display name or device type.</span>
                  </div>
                </label>
                
                <label className="flex items-start gap-3 p-3 rounded-xl border border-border-subtle bg-bg-surface-raised cursor-pointer hover:border-accent-primary/50 transition-colors">
                  <input type="radio" name="mgmtState" value="IGNORED" checked={device.managementState === 'IGNORED'} onChange={() => handleUpdateManagementState('IGNORED')} className="mt-1" />
                  <div>
                    <span className="block text-xs font-bold text-text-primary">Ignored</span>
                    <span className="text-[10px] text-text-secondary">Hide this device from topology maps and active scanning.</span>
                  </div>
                </label>
              </div>
            </div>

            <div className="bg-bg-surface border border-border-subtle rounded-2xl p-6 space-y-6 shadow-lg">
              <div>
                <h3 className="font-bold text-sm tracking-tight">Tags & Labels</h3>
                <p className="text-xs text-text-secondary mt-1">Group devices with custom tags (e.g., 'esphome', 'office').</p>
              </div>

              <div className="flex flex-wrap gap-2">
                {(!device.labels || device.labels.length === 0) && (
                  <span className="text-xs text-text-muted italic">No labels added yet.</span>
                )}
                {device.labels?.map(label => (
                  <span key={label} className="px-3 py-1 rounded-lg bg-accent-primary/10 border border-accent-primary/20 text-accent-primary text-xs font-bold flex items-center gap-2">
                    {label}
                    <button onClick={() => handleRemoveLabel(label)} className="hover:text-text-primary">&times;</button>
                  </span>
                ))}
              </div>

              <form onSubmit={handleAddLabel} className="flex gap-2 pt-2">
                <input 
                  type="text" 
                  value={newLabel}
                  onChange={e => setNewLabel(e.target.value)}
                  placeholder="New label..." 
                  className="bg-bg-surface-raised border border-border-subtle rounded-lg py-2 px-3 text-xs w-full focus:outline-none focus:border-accent-primary" 
                />
                <button type="submit" className="px-4 py-2 rounded-lg bg-bg-surface border border-border-subtle text-text-primary text-xs font-semibold hover:bg-bg-surface-raised cursor-pointer whitespace-nowrap">
                  Add Label
                </button>
              </form>
            </div>
          </div>
        )}
      </div>
    </div>
  )
}
