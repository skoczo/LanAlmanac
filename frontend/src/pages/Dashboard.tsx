import React, { useEffect, useState } from 'react'
import { useAuth } from '../lib/auth/auth-context'
import { Server, ShieldAlert, Wifi, WifiOff, Activity, ArrowUpRight, TrendingUp } from 'lucide-react'

interface Device {
  id: string
  displayName: string
  deviceType: string
  status: string
  confidenceScore: number
}

export const Dashboard: React.FC = () => {
  const { apiClient } = useAuth()
  const [devices, setDevices] = useState<Device[]>([])
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    apiClient<Device[]>('/api/devices')
      .then((data) => {
        setDevices(data)
        setLoading(false)
      })
      .catch((err) => {
        console.error(err)
        setLoading(false)
      })
  }, [])

  const totalCount = devices.length
  const onlineCount = devices.filter((d) => d.status === 'ONLINE').length
  const offlineCount = devices.filter((d) => d.status === 'OFFLINE').length
  const alertCount = devices.filter((d) => d.confidenceScore < 0.8).length

  // Categorize device types for donut chart
  const typeCounts = devices.reduce((acc: Record<string, number>, d) => {
    acc[d.deviceType] = (acc[d.deviceType] || 0) + 1
    return acc
  }, {})

  const recentActivities = [
    { text: 'New identity matched for iPhone-Anna', time: '12 min ago', type: 'info' },
    { text: 'Device Storage-NAS CPU usage spiked above 85%', time: '28 min ago', type: 'warning' },
    { text: 'HP LaserJet Pro reported OFFLINE', time: '1 hour ago', type: 'error' },
    { text: 'Scheduled passive network sweep completed', time: '2 hours ago', type: 'success' },
  ]

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
      <div className="flex justify-between items-center">
        <div>
          <h1 className="text-2xl font-bold tracking-tight">LAN Command Center</h1>
          <p className="text-text-secondary text-sm">Real-time status of your home lab and network devices</p>
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
        {/* Main Traffic Graph */}
        <div className="bg-bg-surface border border-border-subtle rounded-2xl p-6 shadow-lg lg:col-span-2 space-y-6">
          <div className="flex justify-between items-center">
            <div>
              <h3 className="font-bold text-sm tracking-tight flex items-center gap-2">
                <Activity className="w-4 h-4 text-accent-primary" />
                Network Traffic (Throughput)
              </h3>
              <p className="text-xs text-text-secondary">Simulated aggregated LAN client upload/download speeds</p>
            </div>
            <div className="flex items-center gap-1 bg-accent-primary/10 border border-accent-primary/20 text-accent-primary px-2.5 py-1 rounded-lg text-[10px] font-bold">
              <TrendingUp className="w-3.5 h-3.5" />
              <span>Live Telemetry</span>
            </div>
          </div>

          {/* Premium Custom SVG Area Chart */}
          <div className="relative w-full h-[240px] bg-bg-surface-raised/40 border border-border-subtle rounded-xl p-2 flex items-end">
            <svg className="w-full h-full" viewBox="0 0 500 200" preserveAspectRatio="none">
              <defs>
                <linearGradient id="chartGrad" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0%" stopColor="#3b82f6" stopOpacity="0.4" />
                  <stop offset="100%" stopColor="#3b82f6" stopOpacity="0.0" />
                </linearGradient>
              </defs>
              {/* Grid Lines */}
              <line x1="0" y1="50" x2="500" y2="50" stroke="rgba(255,255,255,0.03)" strokeWidth="1" />
              <line x1="0" y1="100" x2="500" y2="100" stroke="rgba(255,255,255,0.03)" strokeWidth="1" />
              <line x1="0" y1="150" x2="500" y2="150" stroke="rgba(255,255,255,0.03)" strokeWidth="1" />

              {/* Area path */}
              <path
                d="M 0 150 Q 50 80 100 120 T 200 90 T 300 130 T 400 40 T 500 70 L 500 200 L 0 200 Z"
                fill="url(#chartGrad)"
              />
              {/* Line path */}
              <path
                d="M 0 150 Q 50 80 100 120 T 200 90 T 300 130 T 400 40 T 500 70"
                fill="none"
                stroke="#3b82f6"
                strokeWidth="2.5"
                strokeLinecap="round"
                className="glow-primary"
              />

              {/* Interactive glow points */}
              <circle cx="100" cy="120" r="4" fill="#3b82f6" stroke="#070a13" strokeWidth="1.5" />
              <circle cx="200" cy="90" r="4" fill="#3b82f6" stroke="#070a13" strokeWidth="1.5" />
              <circle cx="300" cy="130" r="4" fill="#3b82f6" stroke="#070a13" strokeWidth="1.5" />
              <circle cx="400" cy="40" r="5" fill="#06b6d4" stroke="#070a13" strokeWidth="1.5" className="animate-ping" />
              <circle cx="400" cy="40" r="4" fill="#06b6d4" stroke="#070a13" strokeWidth="1.5" />
            </svg>
            <div className="absolute top-4 left-4 text-xs font-semibold text-text-primary">124 Mbps</div>
            <div className="absolute bottom-2 left-2 right-2 flex justify-between text-[9px] text-text-muted font-mono">
              <span>10:30</span>
              <span>10:35</span>
              <span>10:40</span>
              <span>10:45</span>
              <span>10:50</span>
              <span>10:55</span>
            </div>
          </div>
        </div>

        {/* Recent Activity Feed */}
        <div className="bg-bg-surface border border-border-subtle rounded-2xl p-6 shadow-lg flex flex-col space-y-5">
          <div>
            <h3 className="font-bold text-sm tracking-tight">Recent Discovery Log</h3>
            <p className="text-xs text-text-secondary">Audit feed of network scanner sightings</p>
          </div>
          <div className="flex-1 space-y-4 overflow-y-auto pr-1">
            {recentActivities.map((act, index) => (
              <div key={index} className="flex gap-3.5 items-start p-2 rounded-xl bg-bg-surface-raised/35 border border-border-subtle/50 hover:bg-bg-surface-raised/60 transition-colors">
                <span className={`w-2 h-2 rounded-full mt-1.5 flex-shrink-0 ${
                  act.type === 'error' ? 'bg-accent-danger' :
                  act.type === 'warning' ? 'bg-accent-warning' :
                  act.type === 'success' ? 'bg-accent-success' : 'bg-accent-primary'
                }`} />
                <div className="flex-1 min-w-0">
                  <p className="text-xs text-text-primary leading-relaxed">{act.text}</p>
                  <span className="text-[10px] text-text-muted mt-1 block">{act.time}</span>
                </div>
              </div>
            ))}
          </div>
        </div>
      </div>

      {/* Bottom Row - Device distribution */}
      <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
        {/* Device Distribution Donut */}
        <div className="bg-bg-surface border border-border-subtle rounded-2xl p-6 shadow-lg space-y-6">
          <div>
            <h3 className="font-bold text-sm tracking-tight">Device Classification</h3>
            <p className="text-xs text-text-secondary">Distribution of physical device types on LAN</p>
          </div>

          <div className="flex items-center justify-around">
            <div className="relative w-36 h-36 flex items-center justify-center">
              {/* Premium custom SVG Donut */}
              <svg className="w-full h-full transform -rotate-90" viewBox="0 0 36 36">
                {/* Router segment */}
                <circle cx="18" cy="18" r="15.91" fill="none" stroke="rgba(255,255,255,0.03)" strokeWidth="3" />
                {/* Router (14% = dasharray 14, 86) */}
                <circle cx="18" cy="18" r="15.91" fill="none" stroke="#3b82f6" strokeWidth="3.5" strokeDasharray="14 86" strokeDashoffset="100" />
                {/* NAS (14% = dasharray 14, 86) */}
                <circle cx="18" cy="18" r="15.91" fill="none" stroke="#22c55e" strokeWidth="3.5" strokeDasharray="14 86" strokeDashoffset="86" />
                {/* Servers (14% = dasharray 14, 86) */}
                <circle cx="18" cy="18" r="15.91" fill="none" stroke="#06b6d4" strokeWidth="3.5" strokeDasharray="14 86" strokeDashoffset="72" />
                {/* IoT / Others (58% = dasharray 58, 42) */}
                <circle cx="18" cy="18" r="15.91" fill="none" stroke="#f59e0b" strokeWidth="3.5" strokeDasharray="58 42" strokeDashoffset="58" />
              </svg>
              <div className="absolute flex flex-col items-center">
                <span className="text-2xl font-extrabold">{totalCount}</span>
                <span className="text-[9px] text-text-muted uppercase font-semibold">Hosts</span>
              </div>
            </div>

            <div className="space-y-2 text-xs">
              <div className="flex items-center gap-2">
                <span className="w-2.5 h-2.5 rounded-sm bg-accent-primary" />
                <span className="text-text-secondary">Routers ({typeCounts['ROUTER'] || 0})</span>
              </div>
              <div className="flex items-center gap-2">
                <span className="w-2.5 h-2.5 rounded-sm bg-accent-success" />
                <span className="text-text-secondary">NAS Storage ({typeCounts['NAS'] || 0})</span>
              </div>
              <div className="flex items-center gap-2">
                <span className="w-2.5 h-2.5 rounded-sm bg-accent-info" />
                <span className="text-text-secondary">Servers ({typeCounts['SERVER'] || 0})</span>
              </div>
              <div className="flex items-center gap-2">
                <span className="w-2.5 h-2.5 rounded-sm bg-accent-warning" />
                <span className="text-text-secondary">IoT / Mobile ({devices.filter(d => d.deviceType !== 'ROUTER' && d.deviceType !== 'NAS' && d.deviceType !== 'SERVER').length})</span>
              </div>
            </div>
          </div>
        </div>

        {/* Subnet layout / scan status */}
        <div className="bg-bg-surface border border-border-subtle rounded-2xl p-6 shadow-lg md:col-span-2 flex flex-col justify-between">
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
