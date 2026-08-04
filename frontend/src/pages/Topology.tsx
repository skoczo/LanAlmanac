import React, { useEffect, useState } from 'react'
import { useNavigate } from '@tanstack/react-router'
import { useAuth } from '../lib/auth/auth-context'
import { Compass, Cpu, Database, Monitor, Network, Info } from 'lucide-react'

interface Device {
  id: string
  displayName: string
  deviceType: string
  status: string
  identities: Array<{ ipAddress: string; macAddress: string }>
}

interface Node {
  id: string
  label: string
  ip: string
  x: number
  y: number
  type: string
  status: string
  deviceId?: string
}

export const Topology: React.FC = () => {
  const { apiClient } = useAuth()
  const [devices, setDevices] = useState<Device[]>([])
  const [hoveredNode, setHoveredNode] = useState<Node | null>(null)
  const navigate = useNavigate()

  useEffect(() => {
    apiClient<Device[]>('/api/devices')
      .then(setDevices)
      .catch(console.error)
  }, [])

  // Setup static hierarchical positions for our mock devices
  const getNodes = (): Node[] => {
    const nodes: Node[] = []
    
    // Gateway
    const gateway = devices.find((d) => d.deviceType === 'ROUTER')
    nodes.push({
      id: 'gateway',
      label: gateway?.displayName || 'UniFi UDM-Pro',
      ip: gateway?.identities?.[0]?.ipAddress || '192.168.1.1',
      x: 250,
      y: 40,
      type: 'ROUTER',
      status: gateway?.status || 'ONLINE',
      deviceId: gateway?.id
    })

    // NAS Storage
    const nas = devices.find((d) => d.deviceType === 'NAS')
    nodes.push({
      id: 'nas',
      label: nas?.displayName || 'Storage-NAS',
      ip: nas?.identities?.[0]?.ipAddress || '192.168.1.10',
      x: 100,
      y: 110,
      type: 'NAS',
      status: nas?.status || 'ONLINE',
      deviceId: nas?.id
    })

    // Proxmox Server
    const server = devices.find((d) => d.displayName.includes('Proxmox'))
    nodes.push({
      id: 'server',
      label: server?.displayName || 'Proxmox-Node-01',
      ip: server?.identities?.[0]?.ipAddress || '192.168.1.20',
      x: 400,
      y: 110,
      type: 'SERVER',
      status: server?.status || 'ONLINE',
      deviceId: server?.id
    })

    // Endpoints
    const tv = devices.find((d) => d.displayName.includes('TV'))
    nodes.push({
      id: 'tv',
      label: tv?.displayName || 'Living Room TV',
      ip: tv?.identities?.[0]?.ipAddress || '192.168.1.150',
      x: 50,
      y: 200,
      type: 'IOT',
      status: tv?.status || 'ONLINE',
      deviceId: tv?.id
    })

    const iphone = devices.find((d) => d.displayName.includes('iPhone'))
    nodes.push({
      id: 'iphone',
      label: iphone?.displayName || 'iPhone-Anna',
      ip: iphone?.identities?.[0]?.ipAddress || '192.168.1.88',
      x: 180,
      y: 200,
      type: 'IOT',
      status: iphone?.status || 'ONLINE',
      deviceId: iphone?.id
    })

    const workstation = devices.find((d) => d.displayName.includes('MacBook'))
    nodes.push({
      id: 'workstation',
      label: workstation?.displayName || 'MacBook-Pro-16',
      ip: workstation?.identities?.[0]?.ipAddress || '192.168.1.75',
      x: 320,
      y: 200,
      type: 'WORKSTATION',
      status: workstation?.status || 'ONLINE',
      deviceId: workstation?.id
    })

    const printer = devices.find((d) => d.displayName.includes('Printer'))
    nodes.push({
      id: 'printer',
      label: printer?.displayName || 'HP LaserJet Pro',
      ip: printer?.identities?.[0]?.ipAddress || '192.168.1.200',
      x: 450,
      y: 200,
      type: 'IOT',
      status: printer?.status || 'OFFLINE',
      deviceId: printer?.id
    })

    return nodes
  }

  const nodes = getNodes()

  const handleNodeDoubleClick = (node: Node) => {
    if (node.deviceId) {
      navigate({ to: `/devices/${node.deviceId}` })
    }
  }

  const renderNodeIcon = (type: string) => {
    switch (type) {
      case 'ROUTER':
        return <Compass className="w-5 h-5 text-accent-primary" />
      case 'NAS':
        return <Database className="w-5 h-5 text-accent-success" />
      case 'SERVER':
        return <Cpu className="w-5 h-5 text-accent-info" />
      default:
        return <Monitor className="w-5 h-5 text-text-secondary" />
    }
  }

  return (
    <div className="space-y-6 animate-fade-in select-none relative h-full">
      {/* Title */}
      <div>
        <h1 className="text-2xl font-bold tracking-tight">Interactive Network Map</h1>
        <p className="text-text-secondary text-sm">Visual topology layout of connected systems on local subnets</p>
      </div>

      <div className="relative w-full h-[450px] bg-bg-surface border border-border-subtle rounded-2xl p-6 overflow-hidden flex items-center justify-center shadow-lg">
        {/* Connection paths */}
        <svg className="absolute inset-0 w-full h-full pointer-events-none" viewBox="0 0 500 240" preserveAspectRatio="xMidYMid meet">
          {/* Gateway to NAS and Proxmox */}
          <line x1="250" y1="40" x2="100" y2="110" stroke="rgba(59,130,246,0.3)" strokeWidth="1.5" strokeDasharray="3 3" />
          <line x1="250" y1="40" x2="400" y2="110" stroke="rgba(59,130,246,0.3)" strokeWidth="1.5" strokeDasharray="3 3" />

          {/* NAS to Endpoints (TV, iPhone) */}
          <line x1="100" y1="110" x2="50" y2="200" stroke="rgba(34,197,94,0.25)" strokeWidth="1" />
          <line x1="100" y1="110" x2="180" y2="200" stroke="rgba(34,197,94,0.25)" strokeWidth="1" />

          {/* Proxmox to Endpoints (workstation, printer) */}
          <line x1="400" y1="110" x2="320" y2="200" stroke="rgba(6,182,212,0.25)" strokeWidth="1" />
          <line x1="400" y1="110" x2="450" y2="200" stroke="rgba(239,68,68,0.2)" strokeWidth="1" strokeDasharray="2 2" />
        </svg>

        {/* Nodes */}
        <div className="absolute inset-0 w-full h-full flex items-center justify-center" style={{ transform: 'scale(1)' }}>
          <svg className="w-full h-full" viewBox="0 0 500 240" preserveAspectRatio="xMidYMid meet">
            {nodes.map((node) => (
              <g
                key={node.id}
                transform={`translate(${node.x}, ${node.y})`}
                className="cursor-pointer"
                onMouseEnter={() => setHoveredNode(node)}
                onMouseLeave={() => setHoveredNode(null)}
                onDoubleClick={() => handleNodeDoubleClick(node)}
              >
                {/* Glow ring */}
                <circle
                  r="18"
                  fill="#0d1222"
                  stroke={node.status === 'ONLINE' ? '#22c55e' : '#ef4444'}
                  strokeWidth="1.5"
                  className={node.status === 'ONLINE' ? 'animate-pulse-slow' : ''}
                  style={{
                    filter: node.status === 'ONLINE' ? 'drop-shadow(0 0 4px rgba(34,197,94,0.5))' : 'none',
                  }}
                />
                
                {/* Node center */}
                <circle r="14" fill="#161d30" />

                {/* Subnet Tag */}
                <text
                  y="30"
                  textAnchor="middle"
                  fill="#f1f5f9"
                  fontSize="7.5"
                  fontWeight="bold"
                  className="font-sans fill-text-primary"
                >
                  {node.label}
                </text>
                <text
                  y="40"
                  textAnchor="middle"
                  fill="#94a3b8"
                  fontSize="6.5"
                  className="font-mono fill-text-secondary"
                >
                  {node.ip}
                </text>
              </g>
            ))}
          </svg>
        </div>

        {/* Hover overlay popup */}
        {hoveredNode && (
          <div className="absolute top-4 left-4 bg-bg-surface-raised border border-border-subtle p-4 rounded-xl shadow-2xl space-y-2 z-30 w-52 animate-fade-in">
            <div className="flex items-center gap-2 border-b border-border-subtle pb-1.5">
              {renderNodeIcon(hoveredNode.type)}
              <div>
                <h4 className="text-xs font-bold text-text-primary truncate max-w-[140px]">{hoveredNode.label}</h4>
                <span className={`text-[8px] font-bold uppercase tracking-wider ${
                  hoveredNode.status === 'ONLINE' ? 'text-accent-success' : 'text-accent-danger'
                }`}>
                  {hoveredNode.status}
                </span>
              </div>
            </div>
            <div className="text-[10px] space-y-1 text-text-secondary">
              <p>IP: <span className="font-mono text-text-primary">{hoveredNode.ip}</span></p>
              <p>Type: <span className="text-text-primary">{hoveredNode.type}</span></p>
            </div>
            <div className="text-[8px] text-text-muted flex items-center gap-1 mt-2">
              <Info className="w-3 h-3 text-accent-info" />
              <span>Double-click to view profile</span>
            </div>
          </div>
        )}
      </div>

      <div className="flex justify-between items-center text-xs text-text-muted border-t border-border-subtle pt-4">
        <span>Topology inferred via Gateway Switch ARP database</span>
        <div className="flex items-center gap-4">
          <div className="flex items-center gap-1.5">
            <span className="w-2.5 h-2.5 rounded-full bg-accent-success" />
            <span>Online Link</span>
          </div>
          <div className="flex items-center gap-1.5">
            <span className="w-2.5 h-2.5 rounded-full bg-accent-danger" />
            <span>Timeout Link</span>
          </div>
        </div>
      </div>
    </div>
  )
}
