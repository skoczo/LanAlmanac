import React, { useEffect, useState } from 'react'
import { Link, useParams } from '@tanstack/react-router'
import { useAuth } from '../lib/auth/auth-context'
import {
  ArrowLeft,
  Calendar,
  Cpu,
  Database,
  Eye,
  EyeOff,
  KeyRound,
  MapPin,
  Monitor,
  RefreshCw,
  ShieldCheck,
  Terminal,
  Clock,
  Wifi,
  HardDrive
} from 'lucide-react'

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
  const [activeTab, setActiveTab] = useState<'overview' | 'identities' | 'fingerprint' | 'credentials' | 'monitor'>('overview')
  const [revealedCreds, setRevealedCreds] = useState<Record<string, boolean>>({})

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

  const toggleRevealCred = (id: string) => {
    setRevealedCreds((prev) => ({ ...prev, [id]: !prev[id] }))
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

  const renderSvgAreaChart = (data: number[], title: string, color: string, suffix: string) => {
    if (data.length === 0) {
      return (
        <div className="h-44 bg-bg-surface-raised border border-border-subtle rounded-xl flex items-center justify-center text-text-muted text-xs">
          No historical monitoring telemetry recorded
        </div>
      )
    }

    const max = Math.max(...data, 1)
    const min = Math.min(...data, 0)
    const range = max - min
    const width = 500
    const height = 150

    const points = data
      .map((val, index) => {
        const x = (index / (data.length - 1)) * width
        const y = height - ((val - min) / range) * (height - 20) - 10
        return `${x},${y}`
      })
      .join(' ')

    const areaPoints = `0,${height} ${points} ${width},${height}`

    return (
      <div className="bg-bg-surface border border-border-subtle rounded-2xl p-5 space-y-4 shadow-md">
        <div className="flex justify-between items-start">
          <h4 className="text-xs font-semibold text-text-secondary uppercase tracking-wider">{title}</h4>
          <span className="text-sm font-extrabold text-text-primary font-mono">
            {Math.round(data[data.length - 1])}{suffix}
          </span>
        </div>

        <div className="relative w-full h-[150px]">
          <svg className="w-full h-full" viewBox={`0 0 ${width} ${height}`} preserveAspectRatio="none">
            <defs>
              <linearGradient id={`grad-${title}`} x1="0" y1="0" x2="0" y2="1">
                <stop offset="0%" stopColor={color} stopOpacity="0.25" />
                <stop offset="100%" stopColor={color} stopOpacity="0.0" />
              </linearGradient>
            </defs>
            <polygon fill={`url(#grad-${title})`} points={areaPoints} />
            <polyline fill="none" stroke={color} strokeWidth="2" points={points} />
            {/* Draw current value point */}
            <circle
              cx={width}
              cy={height - ((data[data.length - 1] - min) / range) * (height - 20) - 10}
              r="4"
              fill={color}
              stroke="#0d1222"
              strokeWidth="1.5"
            />
          </svg>
        </div>
      </div>
    )
  }

  const currentIdentity = device.identities?.find((id) => id.current)
  const latestFingerprint = device.fingerprints?.[0]

  return (
    <div className="space-y-6 animate-fade-in select-none">
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
      <div className="flex items-center border-b border-border-subtle gap-2">
        {(['overview', 'identities', 'fingerprint', 'credentials', 'monitor'] as const).map((tab) => (
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
            <div className="bg-bg-surface border border-border-subtle rounded-2xl p-6 space-y-5 shadow-lg">
              <h3 className="font-bold text-sm tracking-tight">System Specifications</h3>
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
            </div>

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
                          {cred.credentialType} · Username: <span className="font-mono text-text-primary">{cred.username}</span>
                        </p>
                      </div>
                    </div>

                    <div className="flex items-center gap-3">
                      <span className="font-mono text-xs text-text-muted">
                        {revealedCreds[cred.id] ? 'secret_password_payload_123' : '••••••••••••••••'}
                      </span>
                      <button
                        onClick={() => toggleRevealCred(cred.id)}
                        className="p-1.5 rounded-lg border border-border-subtle hover:bg-bg-surface text-text-secondary hover:text-text-primary cursor-pointer transition-colors"
                      >
                        {revealedCreds[cred.id] ? <EyeOff className="w-4 h-4" /> : <Eye className="w-4 h-4" />}
                      </button>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>
        )}

        {/* MONITOR TAB */}
        {activeTab === 'monitor' && (
          <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
            {renderSvgAreaChart(cpuData, 'CPU Usage History', '#3b82f6', '%')}
            {renderSvgAreaChart(memData, 'RAM Usage History', '#22c55e', '%')}
            <div className="md:col-span-2">
              {renderSvgAreaChart(pingData, 'Ping Latency (Rountrip)', '#06b6d4', 'ms')}
            </div>
          </div>
        )}
      </div>
    </div>
  )
}
