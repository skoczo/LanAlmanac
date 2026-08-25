import React, { useEffect, useState } from 'react'
import { useAuth } from '../lib/auth/auth-context'
import { useVault } from '../lib/vault/vault-context'
import { Lock, Unlock, Eye, EyeOff, ShieldCheck, Key, ShieldAlert } from 'lucide-react'

interface Device {
  id: string
  displayName: string
  credentials: Array<{
    id: string
    label: string
    credentialType: string
    username: string
    port: number
  }>
}

export const Vault: React.FC = () => {
  const { apiClient } = useAuth()
  const { initialized, sealed, setShowUnsealModal, refreshStatus } = useVault()
  
  const [devices, setDevices] = useState<Device[]>([])
  const [revealedCreds, setRevealedCreds] = useState<Record<string, string>>({})
  
  const [initError, setInitError] = useState<string | null>(null)
  const [password, setPassword] = useState('')


  useEffect(() => {
    refreshStatus()
    apiClient<Device[]>('/api/devices')
      .then(setDevices)
      .catch(console.error)
  }, [])

  const handleInit = async (e: React.FormEvent) => {
    e.preventDefault()
    try {
      await apiClient('/api/vault/init', {
        method: 'POST',
        body: JSON.stringify({ password })
      })
      await refreshStatus()
      setInitError(null)
      setPassword('')
    } catch (err: any) {
      setInitError(err.message || 'Initialization failed')
    }
  }

  const handleLock = async () => {
    try {
      await apiClient('/api/vault/lock', { method: 'POST' })
      await refreshStatus()
      setRevealedCreds({})
    } catch (err) {
      console.error(err)
    }
  }

  const toggleRevealCred = async (id: string) => {
    if (revealedCreds[id] !== undefined) {
      // hide
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
      console.error('Reveal failed', e)
      if (e.message?.includes('sealed') || e.message?.includes('Unauthorized') || e.message?.includes('FORBIDDEN')) {
        setShowUnsealModal(true)
      }
    }
  }

  // Get flat list of all credentials
  const credentials = devices.flatMap((d) => 
    (d.credentials || []).map((c) => ({
      ...c,
      deviceName: d.displayName,
      deviceId: d.id
    }))
  )

  if (!initialized) {
    return (
      <div className="min-h-[400px] flex items-center justify-center p-6 animate-fade-in select-none">
        <div className="w-full max-w-md bg-bg-surface border border-border-subtle rounded-2xl p-8 shadow-2xl">
          <div className="text-center space-y-3 mb-6">
            <h2 className="text-lg font-bold text-text-primary">Initialize Vault</h2>
            <p className="text-xs text-text-secondary">Enter a secure master password to initialize the Vault.</p>
          </div>
          {initError && (
            <div className="mb-4 p-2.5 rounded-lg bg-accent-danger/10 border border-accent-danger/25 text-[11px] text-accent-danger flex items-center gap-2">
              <ShieldAlert className="w-3.5 h-3.5" />
              {initError}
            </div>
          )}
          <form onSubmit={handleInit} className="space-y-4">
            <div>
              <label className="block text-xs font-semibold text-text-secondary mb-1">Master Password</label>
              <input
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                className="w-full px-3 py-2 bg-bg-surface-raised border border-border-subtle rounded-xl text-sm focus:outline-none focus:border-accent-primary"
                placeholder="Enter new vault password"
                autoFocus
              />
            </div>
            <button type="submit" disabled={!password} className="w-full bg-accent-primary hover:bg-accent-primary/95 text-text-primary font-semibold py-3 rounded-xl shadow-lg transition-all text-xs cursor-pointer disabled:opacity-50 disabled:cursor-not-allowed">
              Initialize Vault
            </button>
          </form>
        </div>
      </div>
    )
  }

  if (sealed) {
    return (
      <div className="min-h-[400px] flex items-center justify-center p-6 animate-fade-in select-none">
        <div className="w-full max-w-md bg-bg-surface border border-border-subtle rounded-2xl p-8 shadow-2xl glow-primary text-center">
          <div className="inline-flex p-3.5 rounded-xl bg-accent-danger/10 border border-accent-danger/20 text-accent-danger mb-4">
            <Lock className="w-6 h-6" />
          </div>
          <h2 className="text-lg font-bold text-text-primary mb-2">Vault Sealed</h2>
          <p className="text-xs text-text-secondary mb-6">Your credentials are cryptographically sealed.</p>
          <button
            onClick={() => setShowUnsealModal(true)}
            className="w-full bg-accent-primary hover:bg-accent-primary/95 text-text-primary font-semibold py-3 rounded-xl shadow-lg transition-all text-xs flex justify-center items-center gap-2 cursor-pointer"
          >
            <Unlock className="w-4 h-4" />
            Unseal Vault
          </button>
        </div>
      </div>
    )
  }

  return (
    <div className="space-y-6 animate-fade-in select-none">
      <div className="flex justify-between items-center">
        <div>
          <h1 className="text-2xl font-bold tracking-tight">Credential Vault</h1>
          <p className="text-text-secondary text-sm">Envelope-encrypted access keys and credentials</p>
        </div>
        <button
          onClick={handleLock}
          className="flex items-center gap-1.5 px-3 py-2 rounded-xl bg-accent-danger/10 border border-accent-danger/25 text-accent-danger hover:bg-accent-danger/15 transition-all text-xs font-semibold cursor-pointer"
        >
          <Lock className="w-4 h-4" />
          Seal Vault
        </button>
      </div>

      <div className="p-4 rounded-xl bg-accent-success/5 border border-accent-success/20 text-accent-success text-xs flex items-center gap-2.5 glow-success">
        <ShieldCheck className="w-5 h-5 flex-shrink-0" />
        <span>Vault decrypted successfully. Secret payloads can now be revealed.</span>
      </div>

      {credentials.length === 0 ? (
        <div className="p-12 text-center border border-dashed border-border-subtle rounded-2xl bg-bg-surface/30">
          <p className="text-text-secondary text-sm">No credentials stored in vault.</p>
        </div>
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
          {credentials.map((cred) => (
            <div key={cred.id} className="bg-bg-surface border border-border-subtle rounded-2xl p-5 shadow-md flex flex-col justify-between hover:border-accent-primary/10 transition-all duration-300">
              <div className="flex justify-between items-start gap-4">
                <div className="flex items-center gap-3">
                  <div className="p-2.5 rounded-xl bg-accent-primary/10 border border-accent-primary/20 text-accent-primary">
                    <Key className="w-4 h-4" />
                  </div>
                  <div>
                    <h3 className="font-bold text-xs text-text-primary">{cred.label}</h3>
                    <p className="text-[10px] text-text-secondary mt-0.5 uppercase tracking-wider font-semibold">
                      Device: <span className="text-accent-info">{cred.deviceName}</span>
                    </p>
                  </div>
                </div>
                <span className="px-2 py-0.5 rounded bg-bg-surface-raised border border-border-subtle text-text-secondary text-[9px] font-bold uppercase tracking-wider">
                  {cred.credentialType}
                </span>
              </div>

              <div className="mt-6 p-3 rounded-xl bg-bg-surface-raised border border-border-subtle flex items-center justify-between">
                <div className="space-y-0.5 text-[10px]">
                  <p className="text-text-muted">USERNAME</p>
                  <p className="font-mono text-text-primary">{cred.username || '-'}</p>
                </div>

                <div className="flex items-center gap-3">
                  <span className="font-mono text-xs text-text-muted">
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
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
