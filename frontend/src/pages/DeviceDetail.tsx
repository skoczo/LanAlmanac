import React, { useEffect, useState } from 'react'
import { Link, useParams } from '@tanstack/react-router'
import { useAuth } from '../lib/auth/auth-context'
import { useVault } from '../lib/vault/vault-context'
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
  Trash2,
  GitMerge,
  Wifi,
  Terminal as TerminalIcon
} from 'lucide-react'
import { Terminal } from '../components/Terminal'
import { useNavigate } from '@tanstack/react-router'
import { AreaChart, Area, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer } from 'recharts'

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
  httpServerHeader: string
  sshHostKeys: string[]
  tlsJa4: string
  tlsCertSubject: string
  openPorts: number[]
  macOui: string
  capturedAt: string
}

interface CorrelationEvent {
  id: string
  ipAddress: string
  macAddress: string
  hostname: string
  decisionType: 'NEW_DEVICE' | 'DIRECT_MATCH' | 'HOSTNAME_MATCH' | 'SIMILARITY_MATCH' | 'MAC_RESOLVED'
  confidenceScore: number
  details: string
  timestamp: string
}

interface Credential {
  id: string
  label: string
  credentialType: string
  username: string
  port: number
}

interface NetworkService {
  id: string
  label: string
  serviceType: string
  protocol: string
  port: number
  manageable: boolean
  discovered: boolean
  firstSeen: string
  lastSeen: string
  sshHostKey: string | null
  sshHostKeyTrusted: boolean
  credential?: Credential
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
  services: NetworkService[]
}

interface TelemetryPoint {
  id: {
    time: string
    metricName: string
  }
  value: number
}

export const DeviceDetail: React.FC = () => {
  const { id: deviceId } = useParams({ strict: false }) as { id: string }
  const { apiClient } = useAuth()
  
  const [device, setDevice] = useState<Device | null>(null)
  const [telemetry, setTelemetry] = useState<TelemetryPoint[]>([])
  const [correlationHistory, setCorrelationHistory] = useState<CorrelationEvent[]>([])
  const [loading, setLoading] = useState(true)
  const [activeTab, setActiveTab] = useState<'overview' | 'identities' | 'fingerprint' | 'correlation' | 'services' | 'credentials' | 'monitor' | 'settings' | 'web console'>('overview')
  const [newLabel, setNewLabel] = useState('')
  
  const { sealed, setShowUnsealModal } = useVault()
  
  const [showAddCred, setShowAddCred] = useState(false)
  const [editingCredId, setEditingCredId] = useState<string | null>(null)
  const [newCred, setNewCred] = useState({ label: '', type: 'SSH_KEY', username: '', secret: '', port: '' })
  const [revealedCreds, setRevealedCreds] = useState<Record<string, string>>({})
  
  const [showAddService, setShowAddService] = useState(false)
  const [editingServiceId, setEditingServiceId] = useState<string | null>(null)
  const [newService, setNewService] = useState({ label: '', type: 'SSH', protocol: 'TCP', port: '22', credentialId: '' })
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
      apiClient<TelemetryPoint[]>(`/api/devices/${deviceId}/telemetry`),
      apiClient<CorrelationEvent[]>(`/api/devices/${deviceId}/correlation-history`).catch(() => [])
    ])
      .then(([deviceData, telemetryData, correlationData]) => {
        setDevice(deviceData)
        setTelemetry(telemetryData)
        setCorrelationHistory(correlationData)
        setLoading(false)
      })
      .catch((err) => {
        console.error(err)
        setLoading(false)
      })
  }, [deviceId])
  
  const navigate = useNavigate()

  const handleDeleteDevice = async () => {
    if (!window.confirm('Are you sure you want to permanently delete this device? This will remove all associated telemetry, credentials, and fingerprints.')) {
      return
    }
    try {
      await apiClient(`/api/devices/${deviceId}`, { method: 'DELETE' })
      navigate({ to: '/devices' })
    } catch (err: any) {
      alert('Failed to delete device: ' + (err.message || 'Unknown error'))
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

  const handleTrustSshKey = async (serviceId: string) => {
    try {
      const updatedDevice = await apiClient<Device>(`/api/devices/${deviceId}/services/${serviceId}/trust-ssh-key`, {
        method: 'PUT'
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



  const handleSaveCredential = async (e: React.FormEvent) => {
    e.preventDefault()
    try {
      if (editingCredId) {
        await apiClient(`/api/credentials/${editingCredId}`, {
          method: 'PUT',
          body: JSON.stringify(newCred)
        })
      } else {
        await apiClient(`/api/credentials/device/${deviceId}`, {
          method: 'POST',
          body: JSON.stringify(newCred)
        })
      }
      const deviceData = await apiClient<Device>(`/api/devices/${deviceId}`)
      setDevice(deviceData)
      setShowAddCred(false)
      setEditingCredId(null)
      setNewCred({ label: '', type: 'SSH_KEY', username: '', secret: '', port: '' })
    } catch (err) {
      console.error(err)
    }
  }

  const toggleRevealCred = async (credId: string) => {
    if (revealedCreds[credId]) {
      const newRevealed = { ...revealedCreds }
      delete newRevealed[credId]
      setRevealedCreds(newRevealed)
    } else {
      if (sealed) {
        setShowUnsealModal(true)
        return
      }
      try {
        const res = await apiClient<{secret: string}>(`/api/credentials/${credId}/reveal`)
        setRevealedCreds(prev => ({ ...prev, [credId]: res.secret }))
      } catch (err) {
        console.error(err)
      }
    }
  }

  const handleDeleteCred = async (credId: string) => {
    try {
      await apiClient(`/api/credentials/${credId}`, { method: 'DELETE' })
      const deviceData = await apiClient<Device>(`/api/devices/${deviceId}`)
      setDevice(deviceData)
    } catch (err) {
      console.error(err)
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

      {/* Header */}
      <div className="flex items-center justify-between gap-4">
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

        <button
          onClick={handleDeleteDevice}
          className="p-2.5 rounded-xl bg-bg-surface border border-border-subtle hover:bg-accent-danger/10 text-text-secondary hover:text-accent-danger transition-all cursor-pointer"
          title="Delete Device"
        >
          <Trash2 className="w-4 h-4" />
        </button>
      </div>

    {/* Navigation tabs */}
      <div className="flex items-center border-b border-border-subtle gap-2 overflow-x-auto">
        {(['overview', 'identities', 'fingerprint', 'correlation', 'services', 'credentials', 'monitor', 'settings', 'web console'] as const).map((tab) => (
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
                <div className="border-b border-border-subtle/50 pb-2.5">
                  <p className="text-[10px] text-text-muted uppercase font-bold tracking-wider">UPnP SSDP USN</p>
                  <p className="font-mono text-xs text-text-primary mt-1 break-all">{latestFingerprint.ssdpUsn || '-'}</p>
                </div>
                <div>
                  <p className="text-[10px] text-text-muted uppercase font-bold tracking-wider">TLS Client JA4 Hash</p>
                  <p className="font-mono text-xs text-text-primary mt-1">{latestFingerprint.tlsJa4 || '-'}</p>
                </div>
                <div>
                  <p className="text-[10px] text-text-muted uppercase font-bold tracking-wider">SSH Host Keys (SHA256)</p>
                  <div className="flex flex-col gap-1.5 mt-1">
                    {latestFingerprint.sshHostKeys?.map((key) => (
                      <p key={key} className="font-mono text-[9px] text-text-primary break-all bg-bg-base p-1.5 rounded border border-border-subtle">
                        {key}
                      </p>
                    )) || <p className="font-mono text-xs text-text-primary">-</p>}
                  </div>
                </div>
              </div>
            </div>

            <div className="bg-bg-surface border border-border-subtle rounded-2xl p-6 space-y-4 shadow-lg">
              <h3 className="font-bold text-sm tracking-tight">Service Banners & Ports</h3>
              <div className="space-y-3.5 text-xs">
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
        {/* CORRELATION HISTORY TAB */}
        {activeTab === 'correlation' && (
          <div className="bg-bg-surface border border-border-subtle rounded-2xl p-6 space-y-6 shadow-lg animate-fade-in">
            <div className="flex items-center justify-between border-b border-border-subtle pb-4">
              <div>
                <h3 className="font-bold text-lg tracking-tight">Correlation & Fingerprint History</h3>
                <p className="text-xs text-text-muted mt-1">
                  Historical record of when this device was detected on the network and the decisions made to link those detections back to this physical device.
                </p>
              </div>
              <GitMerge className="w-6 h-6 text-accent-primary animate-pulse" />
            </div>

            {correlationHistory.length === 0 ? (
              <div className="text-center py-12 border-2 border-dashed border-border-subtle rounded-xl bg-bg-base/30">
                <Clock className="w-8 h-8 text-text-muted mx-auto mb-2.5" />
                <p className="text-sm text-text-secondary">No correlation events recorded yet.</p>
                <p className="text-xs text-text-muted mt-1">Events will appear here as new sightings are processed by the Fingerprint Engine.</p>
              </div>
            ) : (
              <div className="relative pl-6 border-l-2 border-border-subtle/50 ml-4 space-y-8 mt-6">
                {correlationHistory.map((event) => {
                  let IconComponent = Clock;
                  let colorClass = 'text-accent-info bg-accent-info/10 border-accent-info/20';
                  let typeLabel: string = event.decisionType;

                  if (event.decisionType === 'NEW_DEVICE') {
                    IconComponent = Cpu;
                    colorClass = 'text-accent-success bg-accent-success/10 border-accent-success/20';
                    typeLabel = 'New Device Discovered';
                  } else if (event.decisionType === 'DIRECT_MATCH') {
                    IconComponent = ShieldCheck;
                    colorClass = 'text-accent-primary bg-accent-primary/10 border-accent-primary/20';
                    typeLabel = 'Direct MAC Match';
                  } else if (event.decisionType === 'HOSTNAME_MATCH') {
                    IconComponent = TerminalIcon;
                    colorClass = 'text-accent-warning bg-accent-warning/10 border-accent-warning/20';
                    typeLabel = 'Hostname Early Merge';
                  } else if (event.decisionType === 'SIMILARITY_MATCH') {
                    IconComponent = HardDrive;
                    colorClass = 'text-accent-info bg-accent-info/10 border-accent-info/20';
                    typeLabel = `Fingerprint Similarity Engine`;
                  } else if (event.decisionType === 'MAC_RESOLVED') {
                    IconComponent = Wifi;
                    colorClass = 'text-accent-warning bg-accent-warning/10 border-accent-warning/20';
                    typeLabel = 'MAC Address Resolved';
                  }

                  return (
                    <div key={event.id} className="relative group">
                      {/* Timeline dot & icon */}
                      <span className={`absolute -left-[37px] top-0 flex items-center justify-center w-7.5 h-7.5 rounded-full border ${colorClass} shadow-md`}>
                        <IconComponent className="w-3.5 h-3.5" />
                      </span>

                      <div className="bg-bg-surface-raised border border-border-subtle rounded-xl p-5 space-y-3 transition-all hover:border-border-accent/40 shadow-sm">
                        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-2.5">
                          <div>
                            <span className="text-[10px] text-text-muted font-bold uppercase tracking-wider block">
                              Decision Mode
                            </span>
                            <span className="font-bold text-xs text-text-primary">
                              {typeLabel}
                            </span>
                          </div>
                          <div className="text-right sm:text-right">
                            <span className="text-[10px] text-text-muted font-bold uppercase tracking-wider block">
                              Observed At
                            </span>
                            <span className="text-xs text-text-secondary">
                              {new Date(event.timestamp).toLocaleString()}
                            </span>
                          </div>
                        </div>

                        <div className="grid grid-cols-1 sm:grid-cols-3 gap-4 bg-bg-base/50 p-3.5 rounded-lg border border-border-subtle/50 text-xs">
                          <div>
                            <span className="text-[10px] text-text-muted font-bold uppercase tracking-wider block">
                              IP Address
                            </span>
                            <span className="font-mono font-semibold text-text-primary">{event.ipAddress}</span>
                          </div>
                          <div>
                            <span className="text-[10px] text-text-muted font-bold uppercase tracking-wider block">
                              MAC Address
                            </span>
                            <span className="font-mono font-semibold text-text-primary">{event.macAddress}</span>
                          </div>
                          <div>
                            <span className="text-[10px] text-text-muted font-bold uppercase tracking-wider block">
                              Hostname
                            </span>
                            <span className="font-mono font-semibold text-text-primary">{event.hostname || '-'}</span>
                          </div>
                        </div>

                        <div className="pt-2 flex items-center justify-between border-t border-border-subtle/30 text-xs gap-4">
                          <p className="text-text-secondary italic">"{event.details}"</p>
                          <div className="flex items-center gap-1.5 shrink-0">
                            <span className="text-[10px] text-text-muted font-bold uppercase tracking-wider">Confidence:</span>
                            <span className={`px-2 py-0.5 rounded text-[10px] font-bold ${
                              event.confidenceScore >= 0.8 
                                ? 'bg-accent-success/10 text-accent-success border border-accent-success/20' 
                                : 'bg-accent-warning/10 text-accent-warning border border-accent-warning/20'
                            }`}>
                              {Math.round(event.confidenceScore * 100)}%
                            </span>
                          </div>
                        </div>
                      </div>
                    </div>
                  );
                })}
              </div>
            )}
          </div>
        )}

        {/* CREDENTIALS TAB */}
        {activeTab === 'credentials' && (
          <div className="bg-bg-surface border border-border-subtle rounded-2xl p-6 space-y-6 shadow-lg">
            <div className="flex justify-between items-center pb-4 border-b border-border-subtle">
              <h3 className="font-bold text-lg tracking-tight">Access Credentials</h3>
              <button
                onClick={() => {
                  setEditingCredId(null)
                  setNewCred({ label: '', type: 'SSH_KEY', username: '', secret: '', port: '' })
                  setShowAddCred(true)
                }}
                className="flex items-center gap-1.5 px-3 py-1.5 bg-accent-primary text-white rounded-lg text-sm font-semibold hover:bg-accent-primary-hover transition-colors"
              >
                <Plus className="w-4 h-4" /> Add Credential
              </button>
            </div>

            {device.credentials && device.credentials.length > 0 ? (
              <div className="space-y-4">
                {device.credentials.map((cred) => (
                  <div key={cred.id} className="flex justify-between items-center p-4 rounded-xl bg-bg-surface-raised border border-border-subtle hover:border-accent-primary/50 transition-colors">
                    <div>
                      <div className="flex items-center gap-2">
                        <KeyRound className="w-4 h-4 text-text-secondary" />
                        <span className="font-bold text-text-primary">{cred.label}</span>
                        <span className="text-[10px] uppercase font-bold tracking-wider px-2 py-0.5 rounded-full bg-border-subtle/50 text-text-secondary">
                          {cred.credentialType.replace('_', ' ')}
                        </span>
                      </div>
                      <div className="text-sm text-text-secondary mt-1 ml-6">
                        {cred.username ? <span className="font-mono">{cred.username}</span> : 'No Username'}
                        {cred.port ? <span className="ml-2 font-mono text-xs">Port: {cred.port}</span> : null}
                      </div>
                      {revealedCreds[cred.id] && (
                        <div className="mt-3 ml-6 p-3 bg-bg-base rounded border border-border-subtle font-mono text-xs text-text-primary overflow-x-auto whitespace-pre-wrap">
                          {revealedCreds[cred.id]}
                        </div>
                      )}
                    </div>
                    <div className="flex gap-2">
                      <button
                        onClick={() => toggleRevealCred(cred.id)}
                        className={`p-2 rounded-lg transition-colors border ${
                          revealedCreds[cred.id] 
                            ? 'bg-accent-primary/10 text-accent-primary border-accent-primary/20 hover:bg-accent-primary/20' 
                            : 'bg-bg-surface border-border-subtle text-text-secondary hover:text-text-primary hover:border-text-primary/30'
                        }`}
                        title={sealed ? "Unlock Vault to Reveal" : (revealedCreds[cred.id] ? "Hide Secret" : "Reveal Secret")}
                      >
                        {sealed ? <ShieldCheck className="w-4 h-4 text-accent-warning" /> : (revealedCreds[cred.id] ? <EyeOff className="w-4 h-4" /> : <Eye className="w-4 h-4" />)}
                      </button>
                      <button
                        onClick={() => {
                          setNewCred({
                            label: cred.label,
                            type: cred.credentialType,
                            username: cred.username || '',
                            port: cred.port ? String(cred.port) : '',
                            secret: ''
                          })
                          setEditingCredId(cred.id)
                          setShowAddCred(true)
                        }}
                        className="p-2 bg-bg-surface border border-border-subtle text-text-secondary rounded-lg hover:text-accent-primary hover:border-accent-primary/30 transition-colors"
                        title="Edit Credential"
                      >
                        <Edit2 className="w-4 h-4" />
                      </button>
                      <button
                        onClick={() => handleDeleteCred(cred.id)}
                        className="p-2 bg-bg-surface border border-border-subtle text-accent-danger rounded-lg hover:bg-accent-danger/10 hover:border-accent-danger/30 transition-colors"
                        title="Delete Credential"
                      >
                        <X className="w-4 h-4" />
                      </button>
                    </div>
                  </div>
                ))}
              </div>
            ) : (
              <div className="text-center py-8 text-text-secondary">
                <KeyRound className="w-12 h-12 mx-auto text-border-subtle mb-3" />
                <p>No credentials stored for this device.</p>
              </div>
            )}
            
            {showAddCred && (
              <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm p-4">
                <div className="bg-bg-surface rounded-2xl w-full max-w-md shadow-2xl border border-border-subtle flex flex-col max-h-[90vh]">
                  <div className="p-6 border-b border-border-subtle flex justify-between items-center sticky top-0 bg-bg-surface z-10 rounded-t-2xl">
                    <h3 className="font-bold text-xl">{editingCredId ? 'Edit Credential' : 'Add Credential'}</h3>
                    <button onClick={() => { setShowAddCred(false); setEditingCredId(null); }} className="text-text-muted hover:text-text-primary transition-colors p-1">
                      <X className="w-5 h-5" />
                    </button>
                  </div>
                  
                  <div className="p-6 overflow-y-auto">
                    {sealed && (
                      <div className="mb-6 p-4 rounded-xl bg-accent-warning/10 border border-accent-warning/20 flex gap-3 items-start">
                        <ShieldCheck className="w-5 h-5 text-accent-warning shrink-0 mt-0.5" />
                        <div className="text-sm">
                          <p className="font-bold text-accent-warning mb-1">Vault is Sealed</p>
                          <p className="text-accent-warning/80">You must unlock the secure vault before you can add credentials.</p>
                          <button
                            onClick={(e) => { e.preventDefault(); setShowUnsealModal(true); }}
                            className="mt-3 px-3 py-1.5 bg-accent-warning text-black rounded text-xs font-bold hover:bg-yellow-400 transition-colors"
                          >
                            Unlock Vault
                          </button>
                        </div>
                      </div>
                    )}
                    
                    <form id="add-cred-form" onSubmit={handleSaveCredential} className="space-y-4">
                      <div>
                        <label className="block text-xs font-bold text-text-secondary uppercase tracking-wider mb-1.5">Label</label>
                        <input
                          autoFocus
                          type="text"
                          required
                          value={newCred.label}
                          onChange={e => setNewCred({...newCred, label: e.target.value})}
                          placeholder="e.g. Root SSH Key"
                          className="w-full bg-bg-base border border-border-subtle rounded-lg px-3 py-2 text-sm text-text-primary focus:outline-none focus:border-accent-primary"
                        />
                      </div>
                      
                      <div className="grid grid-cols-2 gap-4">
                        <div>
                          <label className="block text-xs font-bold text-text-secondary uppercase tracking-wider mb-1.5">Type</label>
                          <select
                            value={newCred.type}
                            onChange={e => setNewCred({...newCred, type: e.target.value})}
                            className="w-full bg-bg-base border border-border-subtle rounded-lg px-3 py-2 text-sm text-text-primary focus:outline-none focus:border-accent-primary appearance-none"
                          >
                            <option value="SSH_KEY">SSH Key</option>
                            <option value="PASSWORD">Password</option>
                            <option value="SNMP">SNMP Community</option>
                            <option value="API_TOKEN">API Token</option>
                            <option value="CERTIFICATE">Certificate</option>
                          </select>
                        </div>
                        <div>
                          <label className="block text-xs font-bold text-text-secondary uppercase tracking-wider mb-1.5">Port (Optional)</label>
                          <input
                            type="number"
                            value={newCred.port}
                            onChange={e => setNewCred({...newCred, port: e.target.value})}
                            placeholder="e.g. 22"
                            className="w-full bg-bg-base border border-border-subtle rounded-lg px-3 py-2 text-sm text-text-primary focus:outline-none focus:border-accent-primary"
                          />
                        </div>
                      </div>
                      
                      <div>
                        <label className="block text-xs font-bold text-text-secondary uppercase tracking-wider mb-1.5">Username</label>
                        <input
                          type="text"
                          value={newCred.username}
                          onChange={e => setNewCred({...newCred, username: e.target.value})}
                          placeholder="e.g. root"
                          className="w-full bg-bg-base border border-border-subtle rounded-lg px-3 py-2 text-sm text-text-primary focus:outline-none focus:border-accent-primary"
                        />
                      </div>
                      
                      <div>
                        <label className="block text-xs font-bold text-text-secondary uppercase tracking-wider mb-1.5">Secret / Key</label>
                        <textarea
                          required={!editingCredId}
                          value={newCred.secret}
                          onChange={e => setNewCred({...newCred, secret: e.target.value})}
                          placeholder={editingCredId ? "Leave blank to keep existing secret..." : "Enter password, token, or paste private key here..."}
                          rows={4}
                          className="w-full bg-bg-base border border-border-subtle rounded-lg px-3 py-2 text-sm text-text-primary font-mono focus:outline-none focus:border-accent-primary"
                        />
                      </div>
                    </form>
                  </div>
                  
                  <div className="p-6 border-t border-border-subtle bg-bg-surface-raised rounded-b-2xl flex justify-end gap-3 sticky bottom-0">
                    <button
                      type="button"
                      onClick={() => { setShowAddCred(false); setEditingCredId(null); }}
                      className="px-4 py-2 bg-transparent border border-border-subtle text-text-primary rounded-lg text-sm font-semibold hover:bg-bg-base transition-colors"
                    >
                      Cancel
                    </button>
                    <button
                      type="submit"
                      form="add-cred-form"
                      disabled={sealed}
                      className="px-4 py-2 bg-accent-primary text-white rounded-lg text-sm font-semibold hover:bg-accent-primary-hover transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                    >
                      {editingCredId ? 'Update Credential' : 'Save Credential'}
                    </button>
                  </div>
                </div>
              </div>
            )}
          </div>
        )}

        {/* SERVICES TAB */}
        {activeTab === 'services' && (
          <div className="bg-bg-surface border border-border-subtle rounded-2xl p-6 space-y-6 shadow-lg">
            <div className="flex justify-between items-center">
              <div>
                <h3 className="font-bold text-sm tracking-tight">Network Services & Connections</h3>
                <p className="text-xs text-text-secondary">Manage discovered and manually added services (SSH, Web UI, etc)</p>
              </div>
              <button
                onClick={() => {
                  setNewService({ label: '', type: 'SSH', protocol: 'TCP', port: '22', credentialId: '' })
                  setEditingServiceId(null)
                  setShowAddService(true)
                }}
                className="flex items-center gap-1.5 px-3 py-2 bg-accent-primary hover:bg-accent-primary/90 text-text-primary rounded-xl text-xs font-semibold transition-colors cursor-pointer"
              >
                <Plus className="w-4 h-4" />
                Add Service
              </button>
            </div>
            
            {showAddService && (
              <form onSubmit={async (e) => {
                e.preventDefault();
                const payload = {
                    label: newService.label,
                    serviceType: newService.type,
                    protocol: newService.protocol,
                    port: parseInt(newService.port),
                    credential: newService.credentialId ? { id: newService.credentialId } : null
                };
                
                if (editingServiceId) {
                  await apiClient(`/api/devices/${deviceId}/services/${editingServiceId}`, {
                    method: 'PUT',
                    body: JSON.stringify(payload)
                  });
                } else {
                  await apiClient(`/api/devices/${deviceId}/services`, {
                    method: 'POST',
                    body: JSON.stringify(payload)
                  });
                }
                
                setShowAddService(false);
                setEditingServiceId(null);
                // Refresh device
                const updatedDevice = await apiClient<Device>(`/api/devices/${deviceId}`);
                setDevice(updatedDevice);
              }} className="p-5 rounded-xl border border-border-subtle bg-bg-surface-raised space-y-4">
                <h4 className="text-xs font-bold uppercase tracking-wider text-text-secondary">{editingServiceId ? 'Edit Service' : 'New Service'}</h4>
                <div className="grid grid-cols-2 gap-4">
                  <div>
                    <label className="block text-[10px] font-bold text-text-secondary uppercase tracking-wider mb-1">Label</label>
                    <input type="text" placeholder="e.g. Ubuntu SSH" required className="bg-bg-surface border border-border-subtle rounded-lg py-2 px-3 text-xs w-full focus:outline-none focus:border-accent-primary" value={newService.label} onChange={e => setNewService({...newService, label: e.target.value})} />
                  </div>
                  <div>
                    <label className="block text-[10px] font-bold text-text-secondary uppercase tracking-wider mb-1">Type</label>
                    <select className="bg-bg-surface border border-border-subtle rounded-lg py-2 px-3 text-xs w-full focus:outline-none focus:border-accent-primary" value={newService.type} onChange={e => setNewService({...newService, type: e.target.value})}>
                      <option value="SSH">SSH</option>
                      <option value="HTTP">HTTP</option>
                      <option value="HTTPS">HTTPS</option>
                      <option value="SNMP">SNMP</option>
                    </select>
                  </div>
                  <div>
                    <label className="block text-[10px] font-bold text-text-secondary uppercase tracking-wider mb-1">Port</label>
                    <input type="number" placeholder="Port" required className="bg-bg-surface border border-border-subtle rounded-lg py-2 px-3 text-xs w-full focus:outline-none focus:border-accent-primary" value={newService.port} onChange={e => setNewService({...newService, port: e.target.value})} />
                  </div>
                  <div>
                    <label className="block text-[10px] font-bold text-text-secondary uppercase tracking-wider mb-1">Credential (Optional)</label>
                    <select className="bg-bg-surface border border-border-subtle rounded-lg py-2 px-3 text-xs w-full focus:outline-none focus:border-accent-primary" value={newService.credentialId} onChange={e => setNewService({...newService, credentialId: e.target.value})}>
                      <option value="">-- None --</option>
                      {device.credentials?.map(c => (
                        <option key={c.id} value={c.id}>{c.label} ({c.username})</option>
                      ))}
                    </select>
                  </div>
                </div>
                <div className="flex justify-end gap-2 mt-4">
                  <button type="button" onClick={() => { setShowAddService(false); setEditingServiceId(null); }} className="px-4 py-2 rounded-lg border border-border-subtle text-xs font-semibold hover:bg-bg-surface cursor-pointer">Cancel</button>
                  <button type="submit" className="px-4 py-2 rounded-lg bg-accent-primary text-text-primary text-xs font-semibold hover:bg-accent-primary/90 cursor-pointer">Save Service</button>
                </div>
              </form>
            )}

            {(!device.services || device.services.length === 0) ? (
              <div className="p-8 text-center border border-dashed border-border-subtle rounded-xl text-text-secondary text-xs">
                No network services discovered or added for this system.
              </div>
            ) : (
              <div className="space-y-4">
                {device.services.map((svc) => (
                  <div key={svc.id} className="p-4 rounded-xl bg-bg-surface-raised border border-border-subtle flex items-center justify-between gap-4">
                    <div className="flex items-center gap-3.5">
                      <div className="p-2.5 rounded-xl bg-accent-primary/10 border border-accent-primary/20 text-accent-primary">
                        <TerminalIcon className="w-4 h-4" />
                      </div>
                      <div>
                        <h4 className="font-bold text-xs text-text-primary">{svc.label}</h4>
                        <p className="text-[10px] text-text-secondary uppercase tracking-wider mt-0.5">
                          {svc.serviceType} · Port: <span className="font-mono text-text-primary">{svc.port}</span>
                        </p>
                      </div>
                    </div>

                    <div className="flex items-center gap-3">
                      <button
                        onClick={() => {
                          setNewService({
                            label: svc.label || '',
                            type: svc.serviceType || 'SSH',
                            protocol: svc.protocol || 'TCP',
                            port: svc.port?.toString() || '22',
                            credentialId: svc.credential?.id || ''
                          });
                          setEditingServiceId(svc.id);
                          setShowAddService(true);
                        }}
                        className="p-1.5 rounded-lg border border-border-subtle text-text-secondary hover:text-text-primary hover:bg-bg-surface transition-colors cursor-pointer"
                        title="Edit Service"
                      >
                        <Edit2 className="w-3.5 h-3.5" />
                      </button>

                      {svc.serviceType === 'SSH' && (
                        <div className="flex flex-col gap-1.5 mr-4 border-r border-border-subtle pr-4 w-64">
                            <span className="text-[10px] font-bold text-text-secondary uppercase tracking-wider">Host Key (SHA256)</span>
                            {svc.sshHostKey ? (
                                <div className="space-y-1.5">
                                    <p className="font-mono text-[10px] text-text-primary break-all bg-bg-base p-1.5 rounded border border-border-subtle">
                                        {svc.sshHostKey}
                                    </p>
                                    {svc.sshHostKeyTrusted ? (
                                        <span className="px-2 py-0.5 rounded bg-accent-success/15 text-accent-success text-[9px] font-bold border border-accent-success/20 flex items-center gap-1 w-fit">
                                            <ShieldCheck className="w-2.5 h-2.5" /> Trusted
                                        </span>
                                    ) : (
                                        <div className="flex items-center gap-2 justify-between">
                                            <span className="px-2 py-0.5 rounded bg-accent-danger/15 text-accent-danger text-[9px] font-bold border border-accent-danger/20">
                                                Untrusted
                                            </span>
                                            <button
                                                onClick={() => handleTrustSshKey(svc.id)}
                                                className="px-2 py-0.5 bg-accent-primary hover:bg-accent-primary/90 text-text-primary font-bold rounded text-[9px] cursor-pointer transition-colors"
                                            >
                                                Trust
                                            </button>
                                        </div>
                                    )}
                                </div>
                            ) : (
                                <span className="text-[9px] text-text-muted italic mt-1">Connect to capture</span>
                            )}
                        </div>
                      )}

                      {svc.serviceType === 'SSH' && (
                        <button
                          onClick={() => {
                            if (svc.credential) {
                                setActiveTerminalCredId(svc.credential.id);
                                return;
                            }
                            // Find matching credential (exact port match, or one without a specific port)
                            const matchingCred = device.credentials?.find(c => c.port === svc.port) 
                                              || device.credentials?.find(c => !c.port || c.port === 0);
                            
                            if (matchingCred) {
                                setActiveTerminalCredId(matchingCred.id);
                            } else {
                                alert("No credential found for this SSH service port. Add a credential first.");
                            }
                          }}
                          className="px-3 py-1.5 rounded-lg bg-accent-primary hover:bg-accent-primary/90 text-text-primary text-[10px] font-bold uppercase tracking-wider cursor-pointer transition-colors flex items-center gap-1.5"
                        >
                          <TerminalIcon className="w-3 h-3" /> Connect SSH
                        </button>
                      )}
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>
        )}

        {/* MONITOR TAB */}
        {activeTab === 'monitor' && (
          <div className="bg-bg-surface border border-border-subtle rounded-2xl p-6 space-y-6 shadow-lg">
            <div>
              <h3 className="font-bold text-sm tracking-tight">System Telemetry</h3>
              <p className="text-xs text-text-secondary">Real-time metrics polled via SNMP & SSH</p>
            </div>
            
            {telemetry.length === 0 ? (
              <div className="p-8 text-center border border-dashed border-border-subtle rounded-xl text-text-secondary text-xs">
                No telemetry data available yet. Waiting for next polling cycle.
              </div>
            ) : (
              <div className="space-y-8">
                <div className="h-64">
                  <h4 className="text-xs font-bold text-text-primary mb-4">CPU Load (1m Average)</h4>
                  <ResponsiveContainer width="100%" height="100%">
                    <AreaChart data={telemetry.filter(t => t.id.metricName === 'cpu_load_1m')}>
                      <defs>
                        <linearGradient id="colorCpu" x1="0" y1="0" x2="0" y2="1">
                          <stop offset="5%" stopColor="#3b82f6" stopOpacity={0.3}/>
                          <stop offset="95%" stopColor="#3b82f6" stopOpacity={0}/>
                        </linearGradient>
                      </defs>
                      <CartesianGrid strokeDasharray="3 3" stroke="#334155" vertical={false} />
                      <XAxis dataKey="id.time" tickFormatter={(timeStr: string) => new Date(timeStr).toLocaleTimeString()} stroke="#64748b" fontSize={10} />
                      <YAxis stroke="#64748b" fontSize={10} />
                      <Tooltip contentStyle={{ backgroundColor: '#1e293b', border: '1px solid #334155', borderRadius: '0.5rem' }} />
                      <Area type="monotone" dataKey="value" stroke="#3b82f6" fillOpacity={1} fill="url(#colorCpu)" />
                    </AreaChart>
                  </ResponsiveContainer>
                </div>

                <div className="h-64">
                  <h4 className="text-xs font-bold text-text-primary mb-4">Memory Usage (%)</h4>
                  <ResponsiveContainer width="100%" height="100%">
                    <AreaChart data={telemetry.filter(t => t.id.metricName === 'ram_usage_percent')}>
                      <defs>
                        <linearGradient id="colorRam" x1="0" y1="0" x2="0" y2="1">
                          <stop offset="5%" stopColor="#10b981" stopOpacity={0.3}/>
                          <stop offset="95%" stopColor="#10b981" stopOpacity={0}/>
                        </linearGradient>
                      </defs>
                      <CartesianGrid strokeDasharray="3 3" stroke="#334155" vertical={false} />
                      <XAxis dataKey="id.time" tickFormatter={(timeStr: string) => new Date(timeStr).toLocaleTimeString()} stroke="#64748b" fontSize={10} />
                      <YAxis stroke="#64748b" fontSize={10} domain={[0, 100]} />
                      <Tooltip contentStyle={{ backgroundColor: '#1e293b', border: '1px solid #334155', borderRadius: '0.5rem' }} />
                      <Area type="monotone" dataKey="value" stroke="#10b981" fillOpacity={1} fill="url(#colorRam)" />
                    </AreaChart>
                  </ResponsiveContainer>
                </div>
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
                <h3 className="font-bold text-sm tracking-tight text-accent-danger">System Operations</h3>
                <p className="text-xs text-text-secondary mt-1">Execute high-privilege remote commands.</p>
              </div>
              <div className="p-4 rounded-xl border border-accent-danger/20 bg-accent-danger/5">
                <h4 className="text-xs font-bold text-text-primary">Remote Package Update</h4>
                <p className="text-[10px] text-text-secondary mt-1 mb-3">Execute apt-get update & upgrade over SSH using the default saved credential.</p>
                <button 
                  onClick={async () => {
                    const evtSource = new EventSource(`/api/devices/${device.id}/update`);
                    evtSource.onmessage = function(event) {
                      console.log("Update output: ", event.data);
                      if (event.data.includes("Update Complete") || event.data.includes("Update Failed") || event.data.includes("Error:")) {
                        evtSource.close();
                        alert(event.data);
                      }
                    };
                  }}
                  className="px-4 py-2 bg-accent-danger hover:bg-accent-danger/90 text-white rounded-lg text-xs font-bold transition-colors cursor-pointer"
                >
                  Apply System Updates
                </button>
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
        {/* WEB CONSOLE TAB */}
        {activeTab === 'web console' && (
          <div className="bg-bg-surface border border-border-subtle rounded-2xl overflow-hidden shadow-lg h-[600px] flex flex-col">
            <div className="bg-bg-surface-raised border-b border-border-subtle p-3 flex justify-between items-center">
              <span className="text-xs font-semibold text-text-secondary uppercase tracking-wider">Web Administration Console</span>
              <a href={`/api/proxy/${deviceId}/`} target="_blank" rel="noreferrer" className="text-xs text-accent-primary hover:underline">
                Open in new tab
              </a>
            </div>
            <iframe src={`/api/proxy/${deviceId}/`} className="w-full flex-1 border-none bg-white" title="Web Console" />
          </div>
        )}
      </div>
    </div>
  )
}
