import React, { useEffect, useState } from 'react'
import { Link } from '@tanstack/react-router'
import { useAuth } from '../lib/auth/auth-context'
import {
  Grid,
  List,
  Search,
  SlidersHorizontal,
  ChevronRight,
  ShieldCheck,
  Compass,
  Cpu,
  Monitor,
  Database,
  Plus
} from 'lucide-react'
import { AddDeviceModal } from '../components/AddDeviceModal'

interface Identity {
  ipAddress: string
  macAddress: string
  hostname: string
  current: boolean
}

interface Fingerprint {
  openPorts: number[]
  dhcpOption55: string
  sshBanner: string
}

interface Device {
  id: string
  displayName: string
  deviceType: string
  osFamily: string
  osVersion: string
  manufacturer: string
  model: string
  confidenceScore: number
  status: string
  identities: Identity[]
  fingerprints: Fingerprint[]
}

export const Devices: React.FC = () => {
  const { apiClient } = useAuth()
  const [devices, setDevices] = useState<Device[]>([])
  const [loading, setLoading] = useState(true)
  const [viewMode, setViewMode] = useState<'grid' | 'table'>('grid')
  const [search, setSearch] = useState('')
  const [statusFilter, setStatusFilter] = useState('ALL')
  const [typeFilter, setTypeFilter] = useState('ALL')
  const [showAddModal, setShowAddModal] = useState(false)

  const fetchDevices = () => {
    apiClient<Device[]>('/api/devices')
      .then((data) => {
        setDevices(data)
        setLoading(false)
      })
      .catch((err) => {
        console.error(err)
        setLoading(false)
      })
  }

  useEffect(() => {
    // Initial fetch
    fetchDevices()

    // Establish WebSocket listener to refresh devices list in real-time
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
    const host = window.location.host
    const ws = new WebSocket(`${protocol}//${host}/ws/events`)

    ws.onmessage = () => {
      console.log('Discovered Devices: Refreshing list due to real-time event...')
      fetchDevices()
    }

    return () => {
      ws.close()
    }
  }, [])

  // Filter logic
  const filteredDevices = devices.filter((d) => {
    const currentIdentity = d.identities?.find((id) => id.current)
    const matchesSearch =
      d.displayName.toLowerCase().includes(search.toLowerCase()) ||
      (d.manufacturer ? d.manufacturer.toLowerCase().includes(search.toLowerCase()) : false) ||
      (d.model ? d.model.toLowerCase().includes(search.toLowerCase()) : false) ||
      (currentIdentity?.ipAddress ? currentIdentity.ipAddress.includes(search) : false) ||
      (currentIdentity?.macAddress ? currentIdentity.macAddress.toLowerCase().includes(search.toLowerCase()) : false)

    const matchesStatus = statusFilter === 'ALL' || d.status === statusFilter
    const matchesType = typeFilter === 'ALL' || d.deviceType === typeFilter

    return matchesSearch && matchesStatus && matchesType
  })

  const getDeviceIcon = (type: string) => {
    switch (type) {
      case 'ROUTER':
        return <Compass className="w-5 h-5 text-accent-primary" />
      case 'SERVER':
        return <Cpu className="w-5 h-5 text-accent-info" />
      case 'NAS':
        return <Database className="w-5 h-5 text-accent-success" />
      default:
        return <Monitor className="w-5 h-5 text-text-secondary" />
    }
  }

  if (loading) {
    return (
      <div className="flex items-center justify-center h-full">
        <div className="w-8 h-8 border-4 border-accent-primary border-t-transparent rounded-full animate-spin" />
      </div>
    )
  }

  return (
    <div className="space-y-6 animate-fade-in select-none">
      {/* Title */}
      <div className="flex justify-between items-center">
        <div>
          <h1 className="text-2xl font-bold tracking-tight">Discovered Devices</h1>
          <p className="text-text-secondary text-sm">Review identified systems on your local subnets</p>
        </div>
        <button
          onClick={() => setShowAddModal(true)}
          className="flex items-center gap-2 px-4 py-2.5 rounded-xl bg-accent-primary hover:bg-accent-primary/90 text-text-primary text-xs font-bold uppercase tracking-wider cursor-pointer transition-colors shadow-lg"
        >
          <Plus className="w-4 h-4" />
          Add Device
        </button>
      </div>

      {/* Filter and Search Bar */}
      <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 p-4 rounded-2xl bg-bg-surface border border-border-subtle">
        <div className="flex flex-wrap items-center gap-3 flex-1">
          {/* Search */}
          <div className="relative w-full sm:w-64">
            <div className="absolute inset-y-0 left-0 pl-3.5 flex items-center pointer-events-none text-text-muted">
              <Search className="w-4 h-4" />
            </div>
            <input
              type="text"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              className="w-full bg-bg-surface-raised border border-border-subtle rounded-xl py-2 pl-10 pr-4 text-xs text-text-primary placeholder:text-text-muted focus:outline-none focus:border-accent-primary transition-colors"
              placeholder="Search by name, IP, MAC..."
            />
          </div>

          {/* Type Filter */}
          <div className="relative">
            <select
              value={typeFilter}
              onChange={(e) => setTypeFilter(e.target.value)}
              className="bg-bg-surface-raised border border-border-subtle rounded-xl py-2 px-4 pr-8 text-xs text-text-primary focus:outline-none focus:border-accent-primary appearance-none cursor-pointer"
            >
              <option value="ALL">All Categories</option>
              <option value="ROUTER">Routers</option>
              <option value="NAS">NAS Storage</option>
              <option value="SERVER">Servers</option>
              <option value="PHONE">Mobile Phones</option>
              <option value="IOT">IoT Devices</option>
              <option value="WORKSTATION">Workstations</option>
            </select>
            <SlidersHorizontal className="w-3.5 h-3.5 absolute right-3.5 top-1/2 -translate-y-1/2 text-text-secondary pointer-events-none" />
          </div>

          {/* Status Filter */}
          <div className="relative">
            <select
              value={statusFilter}
              onChange={(e) => setStatusFilter(e.target.value)}
              className="bg-bg-surface-raised border border-border-subtle rounded-xl py-2 px-4 pr-8 text-xs text-text-primary focus:outline-none focus:border-accent-primary appearance-none cursor-pointer"
            >
              <option value="ALL">All Statuses</option>
              <option value="ONLINE">Online Only</option>
              <option value="OFFLINE">Offline Only</option>
            </select>
            <SlidersHorizontal className="w-3.5 h-3.5 absolute right-3.5 top-1/2 -translate-y-1/2 text-text-secondary pointer-events-none" />
          </div>
        </div>

        {/* View Toggle */}
        <div className="flex items-center gap-1.5 p-1 rounded-xl bg-bg-surface-raised border border-border-subtle">
          <button
            onClick={() => setViewMode('grid')}
            className={`p-2 rounded-lg cursor-pointer transition-colors ${
              viewMode === 'grid'
                ? 'bg-bg-surface border border-border-subtle text-accent-primary shadow-sm glow-primary'
                : 'text-text-secondary hover:text-text-primary'
            }`}
          >
            <Grid className="w-4.5 h-4.5" />
          </button>
          <button
            onClick={() => setViewMode('table')}
            className={`p-2 rounded-lg cursor-pointer transition-colors ${
              viewMode === 'table'
                ? 'bg-bg-surface border border-border-subtle text-accent-primary shadow-sm glow-primary'
                : 'text-text-secondary hover:text-text-primary'
            }`}
          >
            <List className="w-4.5 h-4.5" />
          </button>
        </div>
      </div>

      {/* Empty State */}
      {filteredDevices.length === 0 && (
        <div className="p-12 text-center border border-dashed border-border-subtle rounded-2xl bg-bg-surface/30">
          <p className="text-text-secondary text-sm">No devices found matching the selected search criteria.</p>
        </div>
      )}

      {/* Grid Mode */}
      {viewMode === 'grid' && (
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-6">
          {filteredDevices.map((device) => {
            const currentIdentity = device.identities?.find((id) => id.current)
            return (
              <Link
                key={device.id}
                to="/devices/$id"
                params={{ id: device.id }}
                className="bg-bg-surface border border-border-subtle hover:border-accent-primary/20 rounded-2xl p-5 shadow-md flex flex-col justify-between h-[210px] group transition-all duration-300 hover:-translate-y-1 hover:shadow-accent-primary/5 glow-primary"
              >
                <div>
                  <div className="flex justify-between items-start">
                    <div className="p-2.5 rounded-xl bg-bg-surface-raised border border-border-subtle">
                      {getDeviceIcon(device.deviceType)}
                    </div>
                    {/* Status badge */}
                    <div className="flex items-center gap-1.5">
                      <span className={`w-2 h-2 rounded-full ${
                        device.status === 'ONLINE' ? 'bg-accent-success animate-pulse' : 'bg-accent-danger'
                      }`} />
                      <span className="text-[10px] uppercase font-bold tracking-wider text-text-secondary">
                        {device.status}
                      </span>
                    </div>
                  </div>

                  <div className="mt-4 space-y-1">
                    <h3 className="font-bold text-sm text-text-primary group-hover:text-accent-primary transition-colors truncate">
                      {device.displayName}
                    </h3>
                    <p className="font-mono text-xs text-text-secondary truncate flex items-center gap-1.5">
                      <span>{currentIdentity?.ipAddress || 'No IP'}</span>
                      {currentIdentity?.hostname && (
                        <span className="text-[10px] text-text-muted font-sans truncate">
                          ({currentIdentity.hostname})
                        </span>
                      )}
                    </p>
                  </div>
                </div>

                <div className="border-t border-border-subtle pt-3.5 mt-4 flex items-center justify-between text-[11px] text-text-muted">
                  <div className="flex flex-col">
                    <span className="uppercase text-[9px] font-bold text-text-muted tracking-wider">Fingerprint</span>
                    <div className="flex items-center gap-1.5 mt-0.5">
                      <ShieldCheck className="w-3.5 h-3.5 text-accent-info" />
                      <span className="font-semibold text-text-secondary">
                        {Math.round(device.confidenceScore * 100)}% Match
                      </span>
                    </div>
                  </div>
                  <ChevronRight className="w-4 h-4 text-text-muted group-hover:text-text-primary group-hover:translate-x-0.5 transition-all" />
                </div>
              </Link>
            )
          })}
        </div>
      )}

      {/* Table Mode */}
      {viewMode === 'table' && filteredDevices.length > 0 && (
        <div className="bg-bg-surface border border-border-subtle rounded-2xl overflow-hidden shadow-lg">
          <div className="overflow-x-auto">
            <table className="w-full text-left border-collapse">
              <thead>
                <tr className="border-b border-border-subtle bg-bg-surface-raised/40 text-[10px] uppercase tracking-wider font-semibold text-text-secondary">
                  <th className="py-4 px-6">Status</th>
                  <th className="py-4 px-6">Name</th>
                  <th className="py-4 px-6">IP / MAC</th>
                  <th className="py-4 px-6">Category</th>
                  <th className="py-4 px-6">Operating System</th>
                  <th className="py-4 px-6">Confidence</th>
                  <th className="py-4 px-6 text-right">Actions</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border-subtle text-xs">
                {filteredDevices.map((device) => {
                  const currentIdentity = device.identities?.find((id) => id.current)
                  return (
                    <tr key={device.id} className="hover:bg-bg-surface-raised/30 transition-colors">
                      <td className="py-4 px-6">
                        <div className="flex items-center gap-2">
                          <span className={`w-2.5 h-2.5 rounded-full ${
                            device.status === 'ONLINE' ? 'bg-accent-success animate-pulse' : 'bg-accent-danger'
                          }`} />
                          <span className="uppercase text-[10px] font-bold text-text-secondary">
                            {device.status}
                          </span>
                        </div>
                      </td>
                      <td className="py-4 px-6">
                        <div className="font-semibold text-text-primary">{device.displayName}</div>
                        <div className="text-[10px] text-text-secondary mt-0.5">{device.manufacturer} {device.model}</div>
                      </td>
                      <td className="py-4 px-6">
                        <div className="font-mono text-text-primary flex items-center gap-1.5">
                          <span>{currentIdentity?.ipAddress || '-'}</span>
                          {currentIdentity?.hostname && (
                            <span className="text-[10px] text-text-secondary font-sans">
                              ({currentIdentity.hostname})
                            </span>
                          )}
                        </div>
                        <div className="font-mono text-[10px] text-text-secondary mt-0.5">{currentIdentity?.macAddress || '-'}</div>
                      </td>
                      <td className="py-4 px-6">
                        <div className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-lg bg-bg-surface-raised border border-border-subtle font-semibold">
                          {getDeviceIcon(device.deviceType)}
                          <span className="text-[10px] text-text-secondary">{device.deviceType}</span>
                        </div>
                      </td>
                      <td className="py-4 px-6 text-text-primary">
                        {device.osFamily} {device.osVersion}
                      </td>
                      <td className="py-4 px-6">
                        <div className="flex items-center gap-3">
                          <div className="w-16 h-1.5 rounded-full bg-bg-surface-raised border border-border-subtle overflow-hidden">
                            <div
                              className={`h-full rounded-full ${
                                device.confidenceScore >= 0.8 ? 'bg-accent-success' :
                                device.confidenceScore >= 0.5 ? 'bg-accent-warning' : 'bg-accent-danger'
                              }`}
                              style={{ width: `${device.confidenceScore * 100}%` }}
                            />
                          </div>
                          <span className="font-semibold text-[10px]">
                            {Math.round(device.confidenceScore * 100)}%
                          </span>
                        </div>
                      </td>
                      <td className="py-4 px-6 text-right">
                        <Link
                          to="/devices/$id"
                          params={{ id: device.id }}
                          className="inline-flex items-center justify-center p-2 rounded-xl bg-bg-surface-raised border border-border-subtle hover:bg-accent-primary/10 hover:border-accent-primary/30 text-text-secondary hover:text-accent-primary cursor-pointer transition-colors"
                        >
                          <ChevronRight className="w-4 h-4" />
                        </Link>
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {showAddModal && (
        <AddDeviceModal 
          onClose={() => setShowAddModal(false)} 
          onDeviceAdded={() => fetchDevices()} 
        />
      )}
    </div>
  )
}
