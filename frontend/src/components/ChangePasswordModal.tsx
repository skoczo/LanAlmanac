import React, { useState } from 'react'
import { useAuth } from '../lib/auth/auth-context'
import { ShieldAlert, KeyRound, Eye, EyeOff, Check, X } from 'lucide-react'

interface PasswordRequirement {
  label: string
  test: (password: string) => boolean
}

const requirements: PasswordRequirement[] = [
  { label: 'At least 8 characters', test: (p) => p.length >= 8 },
  { label: 'Contains uppercase letter', test: (p) => /[A-Z]/.test(p) },
  { label: 'Contains lowercase letter', test: (p) => /[a-z]/.test(p) },
  { label: 'Contains a number', test: (p) => /\d/.test(p) },
]

export const ChangePasswordModal: React.FC = () => {
  const { token, login, clearMustChangePassword } = useAuth()
  const [currentPassword, setCurrentPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [showCurrent, setShowCurrent] = useState(false)
  const [showNew, setShowNew] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [isSubmitting, setIsSubmitting] = useState(false)

  const allRequirementsMet = requirements.every(r => r.test(newPassword))
  const passwordsMatch = newPassword === confirmPassword && newPassword.length > 0

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setError(null)

    if (!allRequirementsMet) {
      setError('Password does not meet all requirements')
      return
    }

    if (!passwordsMatch) {
      setError('Passwords do not match')
      return
    }

    setIsSubmitting(true)

    try {
      const response = await fetch('/api/auth/change-password', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${token}`,
        },
        body: JSON.stringify({ currentPassword, newPassword }),
      })

      if (!response.ok) {
        const errorData = await response.json().catch(() => null)
        throw new Error(
          errorData?.error || 'Failed to change password. Please try again.'
        )
      }

      const data = await response.json()
      // Update the session with the new token (password change clears mustChangePassword)
      login(data.token, data.username, data.roles, false)
      clearMustChangePassword()
    } catch (err: any) {
      setError(err.message || 'Something went wrong')
    } finally {
      setIsSubmitting(false)
    }
  }

  return (
    <div className="fixed inset-0 z-[100] flex items-center justify-center bg-black/70 backdrop-blur-sm">
      <div className="w-full max-w-lg bg-bg-surface border border-border-subtle rounded-2xl p-8 shadow-2xl relative mx-4">
        {/* Header */}
        <div className="text-center space-y-3 mb-8">
          <div className="inline-flex p-3 rounded-xl bg-accent-warning/10 border border-accent-warning/20 text-accent-warning mb-1">
            <ShieldAlert className="w-8 h-8" />
          </div>
          <h2 className="text-xl font-bold text-text-primary">
            Password Change Required
          </h2>
          <p className="text-sm text-text-secondary max-w-sm mx-auto">
            Your account is using a temporary password. Please set a new secure password to continue.
          </p>
        </div>

        {error && (
          <div className="mb-6 p-3 rounded-lg bg-accent-danger/10 border border-accent-danger/25 text-sm text-accent-danger flex items-center gap-2">
            <span className="w-1.5 h-1.5 rounded-full bg-accent-danger flex-shrink-0" />
            {error}
          </div>
        )}

        <form onSubmit={handleSubmit} className="space-y-5">
          {/* Current Password */}
          <div className="space-y-1.5">
            <label className="text-xs font-semibold text-text-secondary uppercase tracking-wider">
              Current Password
            </label>
            <div className="relative">
              <div className="absolute inset-y-0 left-0 pl-3.5 flex items-center pointer-events-none text-text-muted">
                <KeyRound className="w-4 h-4" />
              </div>
              <input
                type={showCurrent ? 'text' : 'password'}
                value={currentPassword}
                onChange={(e) => setCurrentPassword(e.target.value)}
                required
                autoFocus
                className="w-full bg-bg-surface-raised border border-border-subtle rounded-xl py-3 pl-10 pr-12 text-text-primary text-sm placeholder:text-text-muted focus:outline-none focus:border-accent-primary transition-colors"
                placeholder="Enter current password"
              />
              <button
                type="button"
                onClick={() => setShowCurrent(!showCurrent)}
                className="absolute inset-y-0 right-0 pr-3.5 flex items-center text-text-muted hover:text-text-secondary transition-colors"
              >
                {showCurrent ? <EyeOff className="w-4 h-4" /> : <Eye className="w-4 h-4" />}
              </button>
            </div>
          </div>

          {/* New Password */}
          <div className="space-y-1.5">
            <label className="text-xs font-semibold text-text-secondary uppercase tracking-wider">
              New Password
            </label>
            <div className="relative">
              <div className="absolute inset-y-0 left-0 pl-3.5 flex items-center pointer-events-none text-text-muted">
                <KeyRound className="w-4 h-4" />
              </div>
              <input
                type={showNew ? 'text' : 'password'}
                value={newPassword}
                onChange={(e) => setNewPassword(e.target.value)}
                required
                className="w-full bg-bg-surface-raised border border-border-subtle rounded-xl py-3 pl-10 pr-12 text-text-primary text-sm placeholder:text-text-muted focus:outline-none focus:border-accent-primary transition-colors"
                placeholder="Enter new password"
              />
              <button
                type="button"
                onClick={() => setShowNew(!showNew)}
                className="absolute inset-y-0 right-0 pr-3.5 flex items-center text-text-muted hover:text-text-secondary transition-colors"
              >
                {showNew ? <EyeOff className="w-4 h-4" /> : <Eye className="w-4 h-4" />}
              </button>
            </div>
          </div>

          {/* Confirm Password */}
          <div className="space-y-1.5">
            <label className="text-xs font-semibold text-text-secondary uppercase tracking-wider">
              Confirm New Password
            </label>
            <div className="relative">
              <div className="absolute inset-y-0 left-0 pl-3.5 flex items-center pointer-events-none text-text-muted">
                <KeyRound className="w-4 h-4" />
              </div>
              <input
                type="password"
                value={confirmPassword}
                onChange={(e) => setConfirmPassword(e.target.value)}
                required
                className={`w-full bg-bg-surface-raised border rounded-xl py-3 pl-10 pr-4 text-text-primary text-sm placeholder:text-text-muted focus:outline-none transition-colors ${
                  confirmPassword.length > 0
                    ? passwordsMatch
                      ? 'border-accent-success focus:border-accent-success'
                      : 'border-accent-danger focus:border-accent-danger'
                    : 'border-border-subtle focus:border-accent-primary'
                }`}
                placeholder="Confirm new password"
              />
            </div>
          </div>

          {/* Password Requirements */}
          <div className="p-4 rounded-xl bg-bg-base border border-border-subtle space-y-2">
            <span className="text-xs font-semibold text-text-secondary uppercase tracking-wider">
              Requirements
            </span>
            <div className="grid grid-cols-2 gap-1.5">
              {requirements.map((req, i) => {
                const met = req.test(newPassword)
                return (
                  <div key={i} className="flex items-center gap-1.5 text-xs">
                    {met ? (
                      <Check className="w-3.5 h-3.5 text-accent-success flex-shrink-0" />
                    ) : (
                      <X className="w-3.5 h-3.5 text-text-muted flex-shrink-0" />
                    )}
                    <span className={met ? 'text-accent-success' : 'text-text-muted'}>
                      {req.label}
                    </span>
                  </div>
                )
              })}
              <div className="flex items-center gap-1.5 text-xs">
                {passwordsMatch ? (
                  <Check className="w-3.5 h-3.5 text-accent-success flex-shrink-0" />
                ) : (
                  <X className="w-3.5 h-3.5 text-text-muted flex-shrink-0" />
                )}
                <span className={passwordsMatch ? 'text-accent-success' : 'text-text-muted'}>
                  Passwords match
                </span>
              </div>
            </div>
          </div>

          {/* Submit */}
          <button
            type="submit"
            disabled={isSubmitting || !allRequirementsMet || !passwordsMatch}
            className="w-full bg-accent-primary hover:bg-accent-primary/95 text-text-primary font-semibold py-3 rounded-xl shadow-lg hover:shadow-accent-primary/20 active:scale-[0.98] transition-all disabled:opacity-50 disabled:pointer-events-none text-sm flex justify-center items-center gap-2 mt-2 cursor-pointer"
          >
            {isSubmitting ? (
              <div className="w-5 h-5 border-2 border-text-primary border-t-transparent rounded-full animate-spin" />
            ) : (
              'Set New Password'
            )}
          </button>
        </form>

        <div className="text-center mt-5 text-xs text-text-muted">
          Passwords are hashed with Argon2id — never stored in plaintext
        </div>
      </div>
    </div>
  )
}
