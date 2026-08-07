import React, { useEffect, useState } from 'react'
import { Link } from '@tanstack/react-router'
import { useAuth } from '../lib/auth/auth-context'
import { Server, ShieldAlert, Wifi, WifiOff, LockOpen, PieChart as PieChartIcon } from 'lucide-react'
import { PieChart, Pie, Cell, Tooltip, ResponsiveContainer, BarChart, Bar, XAxis, YAxis, CartesianGrid } from 'recharts'



interface Device {
  id: string
  displayName: string
  deviceType: string
  status: string
  confidenceScore: number
  services: { port: number, protocol: string, serviceType: string }[]
}


export const Dashboard: React.FC = () => {
  const { apiClient } = useAuth()
  const [devices, setDevices] = useState<Device[]>([])
  const [loading, setLoading] = useState(true)

  const [appMode, setAppMode] = useState<string>('DISCOVERY')
  const [threats, setThreats] = useState<any[]>([])

  useEffect(() => {
    Promise.all([
      apiClient<Device[]>('/api/devices'),
      apiClient<any[]>('/api/settings').catch(() => []),
      apiClient<any[]>('/api/threats').catch(() => [])
    ])
      .then(([devRes, setRes, threatRes]) => {
        setDevices(devRes)
        const modeSetting = setRes.find((s: any) => s.key === 'APP_MODE')
        if (modeSetting) setAppMode(modeSetting.value)
        setThreats(threatRes)
        setLoading(false)
      })
      .catch((err) => {
        console.error(err)
        setLoading(false)
      })
  }, [])

  const toggleAppMode = async () => {
    const newMode = appMode === 'DISCOVERY' ? 'DETECTION' : 'DISCOVERY'
    try {
      await apiClient(`/api/settings/APP_MODE`, {
        method: 'PUT',
        body: JSON.stringify({ key: 'APP_MODE', value: newMode })
      })
      setAppMode(newMode)
    } catch (err) {
      console.error('Failed to change mode', err)
    }
  }

  const resolveThreat = async (id: string) => {
    try {
      await apiClient(`/api/threats/${id}/resolve`, { method: 'PUT' })
      setThreats(threats.map(t => t.id === id ? { ...t, resolved: true } : t))
    } catch (err) {
      console.error('Failed to resolve threat', err)
    }
  }

  const totalCount = devices.length
  const onlineCount = devices.filter((d) => d.status === 'ONLINE').length
  const offlineCount = devices.filter((d) => d.status === 'OFFLINE').length
  const alertCount = devices.filter((d) => d.confidenceScore < 0.8).length

  // Categorize device types for donut chart
  const typeCounts = devices.reduce((acc: Record<string, number>, d) => {
    acc[d.deviceType] = (acc[d.deviceType] || 0) + 1
    return acc
  }, {})

  const COLORS = ['#3b82f6', '#22c55e', '#06b6d4', '#f59e0b', '#8b5cf6', '#ec4899']
  const deviceTypeData = Object.entries(typeCounts).map(([type, count]) => ({
    name: type,
    value: count
  })).sort((a, b) => b.value - a.value)

  const portCounts: Record<number, number> = {}
  devices.forEach(d => {
    d.services?.forEach(s => {
      if (s.port) {
        portCounts[s.port] = (portCounts[s.port] || 0) + 1
      }
    })
  })
  const topPortsData = Object.entries(portCounts)
    .map(([port, count]) => ({ name: `Port ${port}`, count }))
    .sort((a, b) => b.count - a.count)
    .slice(0, 5)

  if (loading) {
    return (
      <div className="flex items-center justify-center h-full">
        <div className="w-8 h-8 border-4 border-accent-primary border-t-transparent rounded-full animate-spin" />
      </div>
    )
  }

  return (
    <div className="space-y-8 animate-fade-in select-none">
      {/* Page Title */}
      <div className="flex justify-between items-center bg-bg-surface border border-border-subtle rounded-2xl p-5 shadow-lg">
        <div>
          <h1 className="text-2xl font-bold tracking-tight">LAN Command Center</h1>
          <p className="text-text-secondary text-sm">Real-time status of your home lab and network devices</p>
        </div>
        <div className="flex items-center gap-4">
          <div className="text-right">
            <h3 className="text-xs font-bold text-text-primary uppercase tracking-wider">Engine Mode</h3>
            <p className="text-[10px] text-text-secondary">{appMode === 'DISCOVERY' ? 'Learning Network Baseline' : 'Locked Baseline (IDS Active)'}</p>
          </div>
          <button 
            onClick={toggleAppMode}
            className={`relative inline-flex h-8 w-14 items-center rounded-full transition-colors focus:outline-none focus:ring-2 focus:ring-accent-primary focus:ring-offset-2 focus:ring-offset-bg-base ${
              appMode === 'DETECTION' ? 'bg-accent-danger' : 'bg-accent-primary'
            }`}
          >
            <span
              className={`inline-block h-6 w-6 transform rounded-full bg-white transition-transform ${
                appMode === 'DETECTION' ? 'translate-x-7' : 'translate-x-1'
              }`}
            />
          </button>
        </div>
      </div>

      {/* Bento KPIs Grid */}
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6">
        {/* KPI 1: Total Devices */}
        <div className="bg-bg-surface border border-border-subtle rounded-2xl p-6 shadow-lg hover:border-accent-primary/20 transition-all duration-300 relative group overflow-hidden">
          <div className="absolute top-0 right-0 w-24 h-24 bg-accent-primary/5 rounded-bl-full pointer-events-none group-hover:scale-110 transition-transform" />
          <div className="flex justify-between items-start">
            <span className="text-xs font-semibold text-text-secondary uppercase tracking-wider">Total Devices</span>
            <div className="p-2 rounded-xl bg-accent-primary/10 border border-accent-primary/20 text-accent-primary">
              <Server className="w-4 h-4" />
            </div>
          </div>
          <div className="mt-4">
            <h3 className="text-4xl font-extrabold text-text-primary tracking-tight">{totalCount}</h3>
            <p className="text-[10px] text-text-muted mt-1 uppercase tracking-wider">Discovered hosts on subnet</p>
          </div>
        </div>

        {/* KPI 2: Online */}
        <div className="bg-bg-surface border border-border-subtle rounded-2xl p-6 shadow-lg hover:border-accent-success/20 transition-all duration-300 relative group overflow-hidden">
          <div className="absolute top-0 right-0 w-24 h-24 bg-accent-success/5 rounded-bl-full pointer-events-none group-hover:scale-110 transition-transform" />
          <div className="flex justify-between items-start">
            <span className="text-xs font-semibold text-text-secondary uppercase tracking-wider">Online Hosts</span>
            <div className="p-2 rounded-xl bg-accent-success/10 border border-accent-success/20 text-accent-success glow-success">
              <Wifi className="w-4 h-4 animate-pulse-slow" />
            </div>
          </div>
          <div className="mt-4">
            <h3 className="text-4xl font-extrabold text-text-primary tracking-tight">{onlineCount}</h3>
            <p className="text-[10px] text-text-muted mt-1 uppercase tracking-wider">Active network sockets</p>
          </div>
        </div>

        {/* KPI 3: Offline */}
        <div className="bg-bg-surface border border-border-subtle rounded-2xl p-6 shadow-lg hover:border-accent-danger/20 transition-all duration-300 relative group overflow-hidden">
          <div className="absolute top-0 right-0 w-24 h-24 bg-accent-danger/5 rounded-bl-full pointer-events-none group-hover:scale-110 transition-transform" />
          <div className="flex justify-between items-start">
            <span className="text-xs font-semibold text-text-secondary uppercase tracking-wider">Offline Hosts</span>
            <div className="p-2 rounded-xl bg-accent-danger/10 border border-accent-danger/20 text-accent-danger">
              <WifiOff className="w-4 h-4" />
            </div>
          </div>
          <div className="mt-4">
            <h3 className="text-4xl font-extrabold text-text-primary tracking-tight">{offlineCount}</h3>
            <p className="text-[10px] text-text-muted mt-1 uppercase tracking-wider">Ping timeouts detected</p>
          </div>
        </div>

        {/* KPI 4: Alerts */}
        <div className="bg-bg-surface border border-border-subtle rounded-2xl p-6 shadow-lg hover:border-accent-warning/20 transition-all duration-300 relative group overflow-hidden">
          <div className="absolute top-0 right-0 w-24 h-24 bg-accent-warning/5 rounded-bl-full pointer-events-none group-hover:scale-110 transition-transform" />
          <div className="flex justify-between items-start">
            <span className="text-xs font-semibold text-text-secondary uppercase tracking-wider">Security Alerts</span>
            <div className="p-2 rounded-xl bg-accent-warning/10 border border-accent-warning/20 text-accent-warning">
              <ShieldAlert className="w-4 h-4" />
            </div>
          </div>
          <div className="mt-4">
            <h3 className="text-4xl font-extrabold text-text-primary tracking-tight">{alertCount}</h3>
            <p className="text-[10px] text-text-muted mt-1 uppercase tracking-wider">Weak fingerprints detected</p>
          </div>
        </div>
      </div>

      {/* Graphs & Activity Row */}
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        {/* Main Charts Area */}
        <div className="lg:col-span-2 grid grid-cols-1 md:grid-cols-2 gap-6">
          {/* Device Classification Chart */}
          <div className="bg-bg-surface border border-border-subtle rounded-2xl p-6 shadow-lg space-y-6">
            <div className="flex justify-between items-start">
              <div>
                <h3 className="font-bold text-sm tracking-tight flex items-center gap-2">
                  <PieChartIcon className="w-4 h-4 text-accent-primary" />
                  Network Composition
                </h3>
                <p className="text-xs text-text-secondary">Distribution of physical device types on LAN</p>
              </div>
            </div>
            <div className="h-[240px] w-full">
              {deviceTypeData.length > 0 ? (
                <ResponsiveContainer width="100%" height="100%">
                  <PieChart>
                    <Pie
                      data={deviceTypeData}
                      cx="50%"
                      cy="50%"
                      innerRadius={60}
                      outerRadius={90}
                      paddingAngle={5}
                      dataKey="value"
                      stroke="none"
                    >
                      {deviceTypeData.map((_, index) => (
                        <Cell key={`cell-${index}`} fill={COLORS[index % COLORS.length]} />
                      ))}
                    </Pie>
                    <Tooltip 
                      contentStyle={{ backgroundColor: '#070a13', borderColor: '#1f2937', borderRadius: '8px' }}
                      itemStyle={{ color: '#fff', fontSize: '12px' }}
                    />
                  </PieChart>
                </ResponsiveContainer>
              ) : (
                <div className="flex items-center justify-center h-full text-text-muted text-xs">No device data</div>
              )}
            </div>
          </div>

          {/* Exposed Services Chart */}
          <div className="bg-bg-surface border border-border-subtle rounded-2xl p-6 shadow-lg space-y-6">
            <div className="flex justify-between items-start">
              <div>
                <h3 className="font-bold text-sm tracking-tight flex items-center gap-2">
                  <LockOpen className="w-4 h-4 text-accent-danger" />
                  Most Exposed Services
                </h3>
                <p className="text-xs text-text-secondary">Top 5 open ports discovered across all hosts</p>
              </div>
            </div>
            <div className="h-[240px] w-full">
              {topPortsData.length > 0 ? (
                <ResponsiveContainer width="100%" height="100%">
                  <BarChart data={topPortsData} layout="vertical" margin={{ top: 0, right: 10, left: -20, bottom: 0 }}>
                    <CartesianGrid strokeDasharray="3 3" horizontal={false} stroke="rgba(255,255,255,0.05)" />
                    <XAxis type="number" stroke="#9ca3af" fontSize={10} tickLine={false} axisLine={false} />
                    <YAxis dataKey="name" type="category" stroke="#9ca3af" fontSize={10} tickLine={false} axisLine={false} />
                    <Tooltip 
                      contentStyle={{ backgroundColor: '#070a13', borderColor: '#1f2937', borderRadius: '8px' }}
                      itemStyle={{ color: '#06b6d4', fontSize: '12px' }}
                      cursor={{ fill: 'rgba(255,255,255,0.05)' }}
                    />
                    <Bar dataKey="count" fill="#06b6d4" radius={[0, 4, 4, 0]} barSize={24} />
                  </BarChart>
                </ResponsiveContainer>
              ) : (
                <div className="flex items-center justify-center h-full text-text-muted text-xs">No services discovered</div>
              )}
            </div>
          </div>
        </div>


        {/* Recent Activity Feed */}
        <div className="bg-bg-surface border border-border-subtle rounded-2xl p-6 shadow-lg flex flex-col space-y-5">
          <div className="flex justify-between items-start">
            <div>
              <h3 className="font-bold text-sm tracking-tight text-accent-danger flex items-center gap-2">
                <ShieldAlert className="w-4 h-4" /> Threat Log (IDS)
              </h3>
              <p className="text-xs text-text-secondary">Security anomalies and unverified mutations</p>
            </div>
            <Link 
              to="/alerts" 
              className="px-3 py-1.5 rounded-lg bg-bg-surface-raised border border-border-subtle hover:border-accent-primary text-[10px] font-bold uppercase tracking-wider text-text-secondary hover:text-accent-primary transition-colors cursor-pointer"
            >
              View All Alerts
            </Link>
          </div>
          <div className="flex-1 space-y-4 overflow-y-auto pr-1">
            {threats.length === 0 && (
              <div className="text-xs text-text-muted italic text-center p-4">No threats detected.</div>
            )}
            {threats.map((threat, index) => (
              <div key={index} className={`flex gap-3.5 items-start p-3 rounded-xl border transition-colors ${
                threat.resolved ? 'bg-bg-surface border-border-subtle/50 opacity-50' : 'bg-accent-danger/5 border-accent-danger/20'
              }`}>
                <span className={`w-2 h-2 rounded-full mt-1.5 flex-shrink-0 ${threat.resolved ? 'bg-text-muted' : 'bg-accent-danger animate-pulse'}`} />
                <div className="flex-1 min-w-0">
                  <p className={`text-xs font-bold leading-relaxed ${threat.resolved ? 'text-text-secondary' : 'text-text-primary'}`}>{threat.description}</p>
                  <div className="flex justify-between items-center mt-2">
                    <span className="text-[10px] text-text-muted font-mono">{threat.ipAddress} | {threat.macAddress}</span>
                    {!threat.resolved && (
                      <button onClick={() => resolveThreat(threat.id)} className="text-[9px] font-bold uppercase tracking-wider text-accent-primary hover:text-accent-primary/80 transition-colors">
                        Mark Resolved
                      </button>
                    )}
                  </div>
                </div>
              </div>
            ))}
          </div>
        </div>
      </div>

      {/* Bottom Row - Device distribution */}
      <div className="grid grid-cols-1 gap-6">
        {/* Subnet layout / scan status */}
        <div className="bg-bg-surface border border-border-subtle rounded-2xl p-6 shadow-lg flex flex-col justify-between">
          <div className="space-y-2">
            <h3 className="font-bold text-sm tracking-tight">Active Scan Configurations</h3>
            <p className="text-xs text-text-secondary">Configured discovery interfaces and timing sweeps</p>
          </div>
          
          <div className="grid grid-cols-1 sm:grid-cols-3 gap-4 my-6">
            <div className="p-4 rounded-xl bg-bg-surface-raised border border-border-subtle">
              <p className="text-[10px] text-text-muted uppercase font-semibold">Discovery Subnet</p>
              <h4 className="text-base font-bold font-mono mt-1 text-accent-info">192.168.1.0/24</h4>
            </div>
            <div className="p-4 rounded-xl bg-bg-surface-raised border border-border-subtle">
              <p className="text-[10px] text-text-muted uppercase font-semibold">Active Interface</p>
              <h4 className="text-base font-bold font-mono mt-1 text-accent-primary">eth0 (Sniffing)</h4>
            </div>
            <div className="p-4 rounded-xl bg-bg-surface-raised border border-border-subtle">
              <p className="text-[10px] text-text-muted uppercase font-semibold">ARP Scan Rate</p>
              <h4 className="text-base font-bold font-mono mt-1 text-accent-success">Every 30s</h4>
            </div>
          </div>

          <div className="flex justify-between items-center text-xs text-text-muted border-t border-border-subtle pt-4">
            <span>Passive DHCP Option Sniffer: <b className="text-accent-success">RUNNING</b></span>
            <span>Passive mDNS Listener: <b className="text-accent-success">RUNNING</b></span>
          </div>
        </div>
      </div>

    </div>
  )
}
