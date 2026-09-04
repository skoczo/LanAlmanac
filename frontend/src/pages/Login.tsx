import React, { useState, useEffect } from 'react'
import { useAuth } from '../lib/auth/auth-context'
import { KeyRound, ShieldCheck, User } from 'lucide-react'
import { useNavigate } from '@tanstack/react-router'

export const Login: React.FC = () => {
  const { login, isOidcEnabled, oidcLogin, isAuthenticated } = useAuth()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [isSubmitting, setIsSubmitting] = useState(false)
  const navigate = useNavigate()

  useEffect(() => {
    if (isAuthenticated) {
      navigate({ to: '/', replace: true })
    }
  }, [isAuthenticated, navigate])

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setError(null)
    setIsSubmitting(true)

    try {
      const response = await fetch('/api/auth/login', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({ username, password }),
      })

      if (!response.ok) {
        const errorData = await response.json().catch(() => null)
        throw new Error(
          errorData?.error ||
          (response.status === 401
            ? 'Invalid username or password'
            : 'Server error. Please try again later.')
        )
      }

      const data = await response.json()
      login(data.token, data.username, data.roles, data.mustChangePassword)
      navigate({ to: '/', replace: true })
    } catch (err: any) {
      setError(err.message || 'Something went wrong')
    } finally {
      setIsSubmitting(false)
    }
  }

  return (
    <div className="min-h-screen bg-bg-base flex items-center justify-center p-6 relative overflow-hidden select-none">
      {/* Background gradients */}
      <div className="absolute top-1/4 left-1/4 w-96 h-96 bg-accent-primary/10 rounded-full blur-[100px] pointer-events-none" />
      <div className="absolute bottom-1/4 right-1/4 w-96 h-96 bg-accent-info/5 rounded-full blur-[120px] pointer-events-none" />

      <div className="w-full max-w-md bg-bg-surface border border-border-subtle rounded-2xl p-8 shadow-2xl relative z-10 glow-primary transition-all duration-300">
        <div className="text-center space-y-2 mb-8">
          <div className="inline-flex p-3 rounded-xl bg-accent-primary/10 border border-accent-primary/20 text-accent-primary mb-2">
            <ShieldCheck className="w-8 h-8" />
          </div>
          <h1 className="text-2xl font-bold bg-gradient-to-r from-text-primary to-text-secondary bg-clip-text text-transparent">
            NetAlmanac
          </h1>
          <p className="text-sm text-text-secondary">
            Sign in to access your single pane of glass
          </p>
        </div>

        {error && (
          <div className="mb-6 p-3 rounded-lg bg-accent-danger/10 border border-accent-danger/25 text-sm text-accent-danger flex items-center gap-2">
            <span className="w-1.5 h-1.5 rounded-full bg-accent-danger animate-ping" />
            {error}
          </div>
        )}

        {isOidcEnabled && (
          <div className="mb-6">
            <button
              type="button"
              onClick={() => oidcLogin()}
              className="w-full bg-bg-surface-raised border border-border-subtle hover:border-accent-primary hover:text-accent-primary text-text-primary font-semibold py-3 rounded-xl shadow-sm transition-all text-sm flex justify-center items-center gap-2 cursor-pointer"
            >
              Sign In with SSO
            </button>
            <div className="relative mt-6 mb-2">
              <div className="absolute inset-0 flex items-center">
                <div className="w-full border-t border-border-subtle"></div>
              </div>
              <div className="relative flex justify-center text-xs">
                <span className="bg-bg-surface px-2 text-text-muted">Or continue with local account</span>
              </div>
            </div>
          </div>
        )}

        <form onSubmit={handleSubmit} className="space-y-5">
          <div className="space-y-1.5">
            <label className="text-xs font-semibold text-text-secondary uppercase tracking-wider">
              Username
            </label>
            <div className="relative">
              <div className="absolute inset-y-0 left-0 pl-3.5 flex items-center pointer-events-none text-text-muted">
                <User className="w-4 h-4" />
              </div>
              <input
                type="text"
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                required
                autoFocus
                className="w-full bg-bg-surface-raised border border-border-subtle rounded-xl py-3 pl-10 pr-4 text-text-primary text-sm placeholder:text-text-muted focus:outline-none focus:border-accent-primary transition-colors"
                placeholder="Enter username"
              />
            </div>
          </div>

          <div className="space-y-1.5">
            <label className="text-xs font-semibold text-text-secondary uppercase tracking-wider">
              Password
            </label>
            <div className="relative">
              <div className="absolute inset-y-0 left-0 pl-3.5 flex items-center pointer-events-none text-text-muted">
                <KeyRound className="w-4 h-4" />
              </div>
              <input
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                required
                className="w-full bg-bg-surface-raised border border-border-subtle rounded-xl py-3 pl-10 pr-4 text-text-primary text-sm placeholder:text-text-muted focus:outline-none focus:border-accent-primary transition-colors"
                placeholder="Enter password"
              />
            </div>
          </div>

          <button
            type="submit"
            disabled={isSubmitting}
            className="w-full bg-accent-primary hover:bg-accent-primary/95 text-text-primary font-semibold py-3 rounded-xl shadow-lg hover:shadow-accent-primary/20 active:scale-[0.98] transition-all disabled:opacity-50 disabled:pointer-events-none text-sm flex justify-center items-center gap-2 mt-8 cursor-pointer"
          >
            {isSubmitting ? (
              <div className="w-5 h-5 border-2 border-text-primary border-t-transparent rounded-full animate-spin" />
            ) : (
              'Sign In'
            )}
          </button>
        </form>

        <div className="text-center mt-6 text-xs text-text-muted">
          Secured with Argon2id & AES-256-GCM Envelope Encryption
        </div>
      </div>
    </div>
  )
}
