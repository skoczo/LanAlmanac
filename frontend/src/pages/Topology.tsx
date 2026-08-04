import React, { useEffect, useState, useCallback } from 'react'
import { useNavigate } from '@tanstack/react-router'
import { useAuth } from '../lib/auth/auth-context'
import { Compass, Cpu, Database, Monitor, ServerCrash } from 'lucide-react'
import ReactFlow, { 
  MiniMap, 
  Controls, 
  Background, 
  useNodesState, 
  useEdgesState, 
  MarkerType,
  Node,
  Edge
} from 'reactflow'
import 'reactflow/dist/style.css'

// Custom Node Component to maintain our dark theme aesthetic
const CustomDeviceNode = ({ data }: { data: any }) => {
  const isOnline = data.status === 'ONLINE'

  const renderIcon = (type: string) => {
    switch (type) {
      case 'ROUTER':
      case 'SWITCH':
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
    <div className={`px-4 py-3 shadow-lg rounded-xl border-2 bg-bg-surface-raised flex items-center gap-3 w-48 ${isOnline ? 'border-accent-success/50 shadow-accent-success/10' : 'border-accent-danger/50 shadow-accent-danger/10'}`}>
      <div className={`p-2 rounded-lg bg-bg-surface flex-shrink-0 ${isOnline ? 'animate-pulse-slow' : ''}`}>
        {isOnline ? renderIcon(data.type) : <ServerCrash className="w-5 h-5 text-accent-danger" />}
      </div>
      <div className="flex flex-col overflow-hidden">
        <span className="font-bold text-sm text-text-primary truncate" title={data.label}>{data.label}</span>
        <span className="text-xs text-text-secondary">{data.type}</span>
      </div>
    </div>
  )
}

const nodeTypes = {
  customDevice: CustomDeviceNode,
}

export const Topology: React.FC = () => {
  const { apiClient } = useAuth()
  const navigate = useNavigate()
  
  const [nodes, setNodes, onNodesChange] = useNodesState([])
  const [edges, setEdges, onEdgesChange] = useEdgesState([])
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    apiClient<{ nodes: any[], edges: any[] }>('/api/topology')
      .then((data) => {
        // We need to apply a basic layout since backend returns x:0, y:0
        const formattedNodes: Node[] = data.nodes.map((n, i) => {
          // Simple Grid Layout for now
          const cols = 5;
          const x = (i % cols) * 250;
          const y = Math.floor(i / cols) * 150;
          
          return {
            id: n.id,
            type: 'customDevice',
            position: { x, y },
            data: { ...n.data, deviceId: n.id }
          }
        })
        
        const formattedEdges: Edge[] = data.edges.map((e) => ({
          ...e,
          animated: true,
          style: { stroke: '#3b82f6', strokeWidth: 2 },
          labelStyle: { fill: '#94a3b8', fontWeight: 700 },
          markerEnd: {
            type: MarkerType.ArrowClosed,
            width: 20,
            height: 20,
            color: '#3b82f6',
          },
        }))

        setNodes(formattedNodes)
        setEdges(formattedEdges)
      })
      .catch(console.error)
      .finally(() => setLoading(false))
  }, [])

  const onNodeDoubleClick = useCallback(
    (_: React.MouseEvent, node: Node) => {
      navigate({ to: '/devices/$id', params: { id: node.data.deviceId } })
    },
    [navigate]
  )

  if (loading) {
    return (
      <div className="flex flex-col items-center justify-center h-full text-center select-none">
        <div className="w-12 h-12 rounded-full border-4 border-accent-primary border-t-transparent animate-spin mb-4" />
        <h3 className="font-bold text-text-primary">Loading Interactive Map...</h3>
      </div>
    )
  }

  return (
    <div className="flex flex-col h-full animate-fade-in select-none">
      <div className="mb-4">
        <h1 className="text-2xl font-bold tracking-tight">Interactive Network Map</h1>
        <p className="text-text-secondary text-sm">Visualizing active SNMP Layer 2/3 connections</p>
      </div>

      <div className="flex-1 w-full bg-[#0a0f1c] rounded-2xl border border-border-subtle shadow-inner overflow-hidden">
        <ReactFlow
          nodes={nodes}
          edges={edges}
          onNodesChange={onNodesChange}
          onEdgesChange={onEdgesChange}
          onNodeDoubleClick={onNodeDoubleClick}
          nodeTypes={nodeTypes}
          fitView
          attributionPosition="bottom-right"
        >
          <Background color="#1e293b" gap={20} size={1} />
          <Controls className="bg-bg-surface border-border-subtle fill-text-primary" />
          <MiniMap 
            nodeColor={(n) => n.data.status === 'ONLINE' ? '#22c55e' : '#ef4444'}
            maskColor="rgba(10, 15, 28, 0.7)"
            className="bg-bg-surface-raised border-border-subtle rounded-lg"
          />
        </ReactFlow>
      </div>
    </div>
  )
}
