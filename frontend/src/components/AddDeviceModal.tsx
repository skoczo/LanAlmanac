import React, { useState } from 'react'
import { X, Search, PlusCircle, Server, Target } from 'lucide-react'
import { useAuth } from '../lib/auth/auth-context'

interface AddDeviceModalProps {
  onClose: () => void
  onDeviceAdded: () => void
}

export const AddDeviceModal: React.FC<AddDeviceModalProps> = ({ onClose, onDeviceAdded }) => {
  const { apiClient } = useAuth()
  const [activeTab, setActiveTab] = useState<'discover' | 'manual'>('discover')
  
  // Discover State
  const [discoverIp, setDiscoverIp] = useState('')
  const [discoverLoading, setDiscoverLoading] = useState(false)
  const [discoverMessage, setDiscoverMessage] = useState<string | null>(null)
  const [discoverError, setDiscoverError] = useState<string | null>(null)

  // Manual State
  const [manualForm, setManualForm] = useState({
    displayName: '',
    deviceType: 'UNKNOWN',
    ipAddress: '',
    macAddress: '',
    locationNote: ''
  })
  const [manualLoading, setManualLoading] = useState(false)
  const [manualError, setManualError] = useState<string | null>(null)

  const handleDiscover = async (e: React.FormEvent) => {
    e.preventDefault()
    setDiscoverLoading(true)
    setDiscoverMessage(null)
    setDiscoverError(null)

    try {
      const res = await apiClient<{message: string}>('/api/devices/discover', {
        method: 'POST',
        body: JSON.stringify({ ipAddress: discoverIp })
      })
      setDiscoverMessage(res.message || 'Discovery initiated')
      setDiscoverIp('')
    } catch (err: any) {
      setDiscoverError(err.message || 'Failed to trigger discovery')
    } finally {
      setDiscoverLoading(false)
    }
  }

  const handleManualAdd = async (e: React.FormEvent) => {
    e.preventDefault()
    setManualLoading(true)
    setManualError(null)

    try {
      await apiClient('/api/devices/manual', {
        method: 'POST',
        body: JSON.stringify(manualForm)
      })
      onDeviceAdded()
      onClose()
    } catch (err: any) {
      setManualError(err.message || 'Failed to add device manually')
    } finally {
      setManualLoading(false)
    }
  }

  return (
    <div className="fixed inset-0 z-50 bg-bg-base/80 backdrop-blur-sm flex items-center justify-center p-4 sm:p-6 animate-fade-in select-none">
      <div className="w-full max-w-lg bg-bg-surface border border-border-subtle rounded-2xl shadow-2xl flex flex-col overflow-hidden">
        {/* Header */}
        <div className="flex items-center justify-between p-5 border-b border-border-subtle bg-bg-surface-raised">
          <div className="flex items-center gap-3">
            <div className="p-2 rounded-lg bg-accent-primary/10 text-accent-primary">
              <PlusCircle className="w-5 h-5" />
            </div>
            <h2 className="text-lg font-bold text-text-primary">Add Network Element</h2>
          </div>
          <button onClick={onClose} className="p-2 rounded-lg hover:bg-bg-surface text-text-secondary hover:text-text-primary transition-colors cursor-pointer">
            <X className="w-5 h-5" />
          </button>
        </div>

        {/* Tabs */}
        <div className="flex border-b border-border-subtle bg-bg-surface-raised/50">
          <button 
            onClick={() => setActiveTab('discover')}
            className={`flex-1 py-3.5 text-xs font-bold uppercase tracking-wider transition-colors cursor-pointer flex items-center justify-center gap-2 ${activeTab === 'discover' ? 'text-accent-primary border-b-2 border-accent-primary bg-bg-surface' : 'text-text-secondary hover:text-text-primary'}`}
          >
            <Search className="w-4 h-4" />
            Discover by IP
          </button>
          <button 
            onClick={() => setActiveTab('manual')}
            className={`flex-1 py-3.5 text-xs font-bold uppercase tracking-wider transition-colors cursor-pointer flex items-center justify-center gap-2 ${activeTab === 'manual' ? 'text-accent-primary border-b-2 border-accent-primary bg-bg-surface' : 'text-text-secondary hover:text-text-primary'}`}
          >
            <Server className="w-4 h-4" />
            Add Manually
          </button>
        </div>

        {/* Content */}
        <div className="p-6 overflow-y-auto">
          {activeTab === 'discover' ? (
            <div className="space-y-6">
              <div>
                <p className="text-xs text-text-secondary leading-relaxed">
                  Enter an IP address to trigger an immediate active scan (ICMP/ARP). If the device is reachable, it will automatically populate in the devices list within a few seconds.
                </p>
              </div>

              {discoverMessage && (
                <div className="p-3 rounded-xl bg-accent-success/10 border border-accent-success/25 text-accent-success text-xs font-semibold">
                  {discoverMessage}
                </div>
              )}
              {discoverError && (
                <div className="p-3 rounded-xl bg-accent-danger/10 border border-accent-danger/25 text-accent-danger text-xs font-semibold">
                  {discoverError}
                </div>
              )}

              <form onSubmit={handleDiscover} className="space-y-4">
                <div>
                  <label className="block text-xs font-bold text-text-secondary uppercase tracking-wider mb-2">Target IP Address</label>
                  <div className="relative">
                    <Target className="absolute left-3.5 top-1/2 -translate-y-1/2 w-4 h-4 text-text-muted" />
                    <input 
                      type="text" 
                      required
                      placeholder="192.168.1.100"
                      className="w-full bg-bg-surface-raised border border-border-subtle rounded-xl py-3 pl-10 pr-4 text-sm font-mono text-text-primary focus:outline-none focus:border-accent-primary transition-colors"
                      value={discoverIp}
                      onChange={e => setDiscoverIp(e.target.value)}
                    />
                  </div>
                </div>
                
                <button 
                  type="submit" 
                  disabled={discoverLoading || !discoverIp}
                  className="w-full py-3.5 rounded-xl bg-accent-primary hover:bg-accent-primary/90 disabled:opacity-50 disabled:cursor-not-allowed text-text-primary text-xs font-bold uppercase tracking-wider flex justify-center items-center gap-2 cursor-pointer transition-all shadow-lg"
                >
                  {discoverLoading ? <div className="w-4 h-4 border-2 border-white/30 border-t-white rounded-full animate-spin" /> : <Search className="w-4 h-4" />}
                  Trigger Discovery
                </button>
              </form>
            </div>
          ) : (
            <form onSubmit={handleManualAdd} className="space-y-4">
              {manualError && (
                <div className="p-3 rounded-xl bg-accent-danger/10 border border-accent-danger/25 text-accent-danger text-xs font-semibold mb-4">
                  {manualError}
                </div>
              )}

              <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                <div>
                  <label className="block text-[10px] font-bold text-text-secondary uppercase tracking-wider mb-1.5">Display Name *</label>
                  <input type="text" required value={manualForm.displayName} onChange={e => setManualForm({...manualForm, displayName: e.target.value})} className="w-full bg-bg-surface-raised border border-border-subtle rounded-lg py-2.5 px-3 text-xs text-text-primary focus:outline-none focus:border-accent-primary" placeholder="Core Switch" />
                </div>
                <div>
                  <label className="block text-[10px] font-bold text-text-secondary uppercase tracking-wider mb-1.5">Device Type</label>
                  <select value={manualForm.deviceType} onChange={e => setManualForm({...manualForm, deviceType: e.target.value})} className="w-full bg-bg-surface-raised border border-border-subtle rounded-lg py-2.5 px-3 text-xs text-text-primary focus:outline-none focus:border-accent-primary">
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
                  <label className="block text-[10px] font-bold text-text-secondary uppercase tracking-wider mb-1.5">IP Address *</label>
                  <input type="text" required value={manualForm.ipAddress} onChange={e => setManualForm({...manualForm, ipAddress: e.target.value})} className="w-full bg-bg-surface-raised border border-border-subtle rounded-lg py-2.5 px-3 text-xs font-mono text-text-primary focus:outline-none focus:border-accent-primary" placeholder="10.0.0.1" />
                </div>
                <div>
                  <label className="block text-[10px] font-bold text-text-secondary uppercase tracking-wider mb-1.5">MAC Address</label>
                  <input type="text" value={manualForm.macAddress} onChange={e => setManualForm({...manualForm, macAddress: e.target.value})} className="w-full bg-bg-surface-raised border border-border-subtle rounded-lg py-2.5 px-3 text-xs font-mono text-text-primary focus:outline-none focus:border-accent-primary" placeholder="00:00:00:00:00:00 (Optional)" />
                </div>
                <div className="md:col-span-2">
                  <label className="block text-[10px] font-bold text-text-secondary uppercase tracking-wider mb-1.5">Location Note</label>
                  <input type="text" value={manualForm.locationNote} onChange={e => setManualForm({...manualForm, locationNote: e.target.value})} className="w-full bg-bg-surface-raised border border-border-subtle rounded-lg py-2.5 px-3 text-xs text-text-primary focus:outline-none focus:border-accent-primary" placeholder="Server Room Rack 2" />
                </div>
              </div>

              <div className="pt-4 border-t border-border-subtle flex justify-end gap-3 mt-6">
                <button type="button" onClick={onClose} className="px-5 py-2.5 rounded-xl border border-border-subtle text-text-primary text-xs font-bold hover:bg-bg-surface-raised cursor-pointer">
                  Cancel
                </button>
                <button type="submit" disabled={manualLoading || !manualForm.displayName || !manualForm.ipAddress} className="px-5 py-2.5 rounded-xl bg-accent-primary hover:bg-accent-primary/90 disabled:opacity-50 text-text-primary text-xs font-bold cursor-pointer flex items-center gap-2 shadow-lg">
                  {manualLoading ? 'Saving...' : 'Add Device'}
                </button>
              </div>
            </form>
          )}
        </div>
      </div>
    </div>
  )
}
