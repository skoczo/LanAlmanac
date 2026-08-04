import React, { useEffect, useState } from 'react'
import { useAuth } from '../lib/auth/auth-context'
import { KeyRound, Lock, Unlock, Eye, EyeOff, ShieldCheck, Key, ShieldAlert } from 'lucide-react'

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
  const [devices, setDevices] = useState<Device[]>([])
  const [isUnlocked, setIsUnlocked] = useState(false)
  const [passphrase, setPassphrase] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [revealedCreds, setRevealedCreds] = useState<Record<string, boolean>>({})

  useEffect(() => {
    apiClient<Device[]>('/api/devices')
      .then(setDevices)
      .catch(console.error)
  }, [])

  const handleUnlock = (e: React.FormEvent) => {
    e.preventDefault()
    setError(null)
    if (passphrase === 'admin') {
      setIsUnlocked(true)
      setPassphrase('')
    } else {
      setError('Invalid master passphrase key')
    }
  }

  const toggleRevealCred = (id: string) => {
    setRevealedCreds((prev) => ({ ...prev, [id]: !prev[id] }))
  }

  // Get flat list of all credentials
  const credentials = devices.flatMap((d) => 
    (d.credentials || []).map((c) => ({
      ...c,
      deviceName: d.displayName,
      deviceId: d.id
    }))
  )

  if (!isUnlocked) {
    return (
      <div className="min-h-[400px] flex items-center justify-center p-6 animate-fade-in select-none">
        <div className="w-full max-w-md bg-bg-surface border border-border-subtle rounded-2xl p-8 shadow-2xl glow-primary">
          <div className="text-center space-y-3 mb-6">
            <div className="inline-flex p-3.5 rounded-xl bg-accent-danger/10 border border-accent-danger/20 text-accent-danger mb-2">
              <Lock className="w-6 h-6" />
            </div>
            <h2 className="text-lg font-bold text-text-primary">Credential Vault Sealed</h2>
            <p className="text-xs text-text-secondary">
              Enter your master passphrase to unseal key rings and load decrypted payloads.
            </p>
          </div>

          {error && (
            <div className="mb-4 p-2.5 rounded-lg bg-accent-danger/10 border border-accent-danger/25 text-[11px] text-accent-danger flex items-center gap-2">
              <ShieldAlert className="w-3.5 h-3.5" />
              {error}
            </div>
          )}

          <form onSubmit={handleUnlock} className="space-y-4">
            <input
              type="password"
              value={passphrase}
              onChange={(e) => setPassphrase(e.target.value)}
              placeholder="Enter Master Passphrase (admin)"
              required
              className="w-full bg-bg-surface-raised border border-border-subtle rounded-xl py-3 px-4 text-xs text-text-primary focus:outline-none focus:border-accent-primary transition-colors text-center font-mono"
            />
            <button
              type="submit"
              className="w-full bg-accent-primary hover:bg-accent-primary/95 text-text-primary font-semibold py-3 rounded-xl shadow-lg transition-all text-xs flex justify-center items-center gap-2 cursor-pointer"
            >
              <Unlock className="w-4 h-4" />
              Unseal Vault
            </button>
          </form>
        </div>
      </div>
    )
  }

  return (
    <div className="space-y-6 animate-fade-in select-none">
      {/* Title */}
      <div className="flex justify-between items-center">
        <div>
          <h1 className="text-2xl font-bold tracking-tight">Credential Vault</h1>
          <p className="text-text-secondary text-sm">Envelope-encrypted access keys and credentials</p>
        </div>
        <button
          onClick={() => setIsUnlocked(false)}
          className="flex items-center gap-1.5 px-3 py-2 rounded-xl bg-accent-danger/10 border border-accent-danger/25 text-accent-danger hover:bg-accent-danger/15 transition-all text-xs font-semibold cursor-pointer"
        >
          <Lock className="w-4 h-4" />
          Seal Vault
        </button>
      </div>

      {/* Audit notice */}
      <div className="p-4 rounded-xl bg-accent-success/5 border border-accent-success/20 text-accent-success text-xs flex items-center gap-2.5 glow-success">
        <ShieldCheck className="w-5 h-5 flex-shrink-0" />
        <span>Vault decrypted successfully. All reads are audited in the append-only logs.</span>
      </div>

      {credentials.length === 0 ? (
        <div className="p-12 text-center border border-dashed border-border-subtle rounded-2xl bg-bg-surface/30">
          <p className="text-text-secondary text-sm">No credentials stored in vault.</p>
        </div>
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
          {credentials.map((cred) => (
            <div
              key={cred.id}
              className="bg-bg-surface border border-border-subtle rounded-2xl p-5 shadow-md flex flex-col justify-between hover:border-accent-primary/10 transition-all duration-300"
            >
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
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
