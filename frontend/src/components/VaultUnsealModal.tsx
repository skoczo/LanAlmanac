import React, { useState } from 'react'
import { useVault } from '../lib/vault/vault-context'
import { useAuth } from '../lib/auth/auth-context'
import { Lock, Unlock, ShieldAlert } from 'lucide-react'

export const VaultUnsealModal = () => {
  const { sealed, autoUnsealEnabled, showUnsealModal, setShowUnsealModal, refreshStatus } = useVault()
  const { apiClient } = useAuth()
  const [error, setError] = useState<string | null>(null)
  const [password, setPassword] = useState('')

  // In auto-unseal mode the backend manages the vault — never prompt the user manually.
  if (!showUnsealModal || !sealed || autoUnsealEnabled) return null

  const handleUnseal = async (e: React.FormEvent) => {
    e.preventDefault()
    try {
      await apiClient('/api/vault/unseal', {
        method: 'POST',
        body: JSON.stringify({ password })
      })
      await refreshStatus()
      setShowUnsealModal(false)
      setError(null)
      setPassword('')
    } catch (err: any) {
      setError(err.message || 'Invalid passcode')
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm p-4">
      <div className="w-full max-w-sm bg-bg-surface border border-border-subtle rounded-2xl p-6 shadow-2xl animate-fade-in">
        <div className="text-center space-y-3 mb-6">
          <div className="inline-flex p-3 rounded-xl bg-accent-danger/10 text-accent-danger mb-2 border border-accent-danger/20">
            <Lock className="w-6 h-6" />
          </div>
          <h2 className="text-lg font-bold text-text-primary">Vault Sealed</h2>
          <p className="text-xs text-text-secondary">
            Enter the master password to unlock the Vault.
          </p>
        </div>

        {error && (
          <div className="mb-4 p-2.5 rounded-lg bg-accent-danger/10 border border-accent-danger/25 text-[11px] text-accent-danger flex items-center gap-2">
            <ShieldAlert className="w-3.5 h-3.5" />
            {error}
          </div>
        )}

        <form onSubmit={handleUnseal} className="space-y-4">
          <div>
            <label className="block text-xs font-semibold text-text-secondary mb-1">Master Password</label>
            <input
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              className="w-full px-3 py-2 bg-bg-surface-raised border border-border-subtle rounded-xl text-sm focus:outline-none focus:border-accent-primary"
              placeholder="Enter vault password"
              autoFocus
            />
          </div>

          <div className="flex gap-3 mt-6">
            <button
              type="button"
              onClick={() => setShowUnsealModal(false)}
              className="flex-1 px-4 py-2.5 rounded-xl border border-border-subtle hover:bg-bg-surface-raised text-xs font-semibold cursor-pointer transition-colors"
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={!password}
              className="flex-1 bg-accent-primary hover:bg-accent-primary/95 text-text-primary font-semibold py-2.5 rounded-xl text-xs flex justify-center items-center gap-2 cursor-pointer disabled:opacity-50 disabled:cursor-not-allowed"
            >
              <Unlock className="w-4 h-4" />
              Unseal Vault
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}
