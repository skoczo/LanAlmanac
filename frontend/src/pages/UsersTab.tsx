import React, { useState, useEffect } from 'react'
import { Loader2, AlertCircle, Plus, Trash2, ShieldAlert, KeyRound, Check, X } from 'lucide-react'
import { useAuth } from '../lib/auth/auth-context'

interface UserDto {
  id: string
  username: string
  displayName: string
  role: string
  mustChangePassword: boolean
  enabled: boolean
  createdAt: string
  updatedAt: string
}

export const UsersTab: React.FC = () => {
  const [users, setUsers] = useState<UserDto[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  
  // Modal states
  const [showAddModal, setShowAddModal] = useState(false)
  const [newUser, setNewUser] = useState({ username: '', password: '', displayName: '', role: 'gnm-viewer' })
  const [saving, setSaving] = useState(false)

  const { apiClient, user } = useAuth()
  const isAdmin = user?.roles.includes('gnm-admin')

  useEffect(() => {
    fetchUsers()
  }, [])

  const fetchUsers = async () => {
    try {
      setLoading(true)
      const data = await apiClient<UserDto[]>('/api/users')
      setUsers(data)
    } catch (err: any) {
      setError(err.message)
    } finally {
      setLoading(false)
    }
  }

  const handleAddUser = async (e: React.FormEvent) => {
    e.preventDefault()
    setSaving(true)
    try {
      await apiClient('/api/users', {
        method: 'POST',
        body: JSON.stringify(newUser)
      })
      setShowAddModal(false)
      setNewUser({ username: '', password: '', displayName: '', role: 'gnm-viewer' })
      fetchUsers()
    } catch (err: any) {
      alert(err.message)
    } finally {
      setSaving(false)
    }
  }

  const handleDelete = async (id: string) => {
    if (!confirm('Are you sure you want to delete this user?')) return
    try {
      await apiClient(`/api/users/${id}`, { method: 'DELETE' })
      fetchUsers()
    } catch (err: any) {
      alert(err.message)
    }
  }

  const handleResetPassword = async (id: string) => {
    const newPass = prompt('Enter new temporary password for this user (must be at least 8 chars):')
    if (!newPass) return
    try {
      await apiClient(`/api/users/${id}/reset-password`, {
        method: 'PUT',
        body: JSON.stringify({ newPassword: newPass })
      })
      alert('Password reset successfully. The user will be forced to change it on next login.')
      fetchUsers()
    } catch (err: any) {
      alert(err.message)
    }
  }

  const handleRoleChange = async (id: string, role: string) => {
    try {
      await apiClient(`/api/users/${id}`, {
        method: 'PUT',
        body: JSON.stringify({ role })
      })
      fetchUsers()
    } catch (err: any) {
      alert(err.message)
    }
  }

  const handleEnabledChange = async (id: string, enabled: boolean) => {
    try {
      await apiClient(`/api/users/${id}`, {
        method: 'PUT',
        body: JSON.stringify({ enabled })
      })
      fetchUsers()
    } catch (err: any) {
      alert(err.message)
    }
  }

  if (!isAdmin) {
    return (
      <div className="flex flex-col items-center justify-center py-12 text-accent-danger gap-4">
        <ShieldAlert className="w-12 h-12" />
        <p>You must be an administrator to manage users.</p>
      </div>
    )
  }

  if (loading) {
    return (
      <div className="flex items-center justify-center py-12">
        <Loader2 className="w-8 h-8 animate-spin text-accent-primary" />
      </div>
    )
  }

  return (
    <div className="space-y-6">
      <div className="flex justify-between items-center">
        <h2 className="text-lg font-bold text-text-primary">Local Users</h2>
        <button
          onClick={() => setShowAddModal(true)}
          className="bg-accent-primary text-bg-base px-4 py-2 rounded-xl text-sm font-semibold hover:bg-accent-primary/90 flex items-center gap-2"
        >
          <Plus className="w-4 h-4" /> Add User
        </button>
      </div>

      {error && (
        <div className="p-4 bg-accent-danger/10 border border-accent-danger/20 text-accent-danger rounded-xl flex items-center gap-2">
          <AlertCircle className="w-5 h-5" /> {error}
        </div>
      )}

      <div className="glass-panel rounded-2xl border border-border-subtle overflow-hidden">
        <div className="overflow-x-auto">
          <table className="w-full text-left text-sm text-text-secondary">
            <thead className="bg-bg-surface text-text-primary border-b border-border-subtle">
              <tr>
                <th className="px-6 py-4 font-semibold">Username</th>
                <th className="px-6 py-4 font-semibold">Display Name</th>
                <th className="px-6 py-4 font-semibold">Role</th>
                <th className="px-6 py-4 font-semibold">Status</th>
                <th className="px-6 py-4 font-semibold">Actions</th>
              </tr>
            </thead>
            <tbody>
              {users.map((u) => (
                <tr key={u.id} className="border-b border-border-subtle last:border-0 hover:bg-bg-surface-raised/50 transition-colors">
                  <td className="px-6 py-4 font-medium text-text-primary flex items-center gap-2">
                    {u.username}
                    {u.mustChangePassword && (
                      <span className="px-2 py-0.5 rounded text-[10px] uppercase font-bold bg-accent-warning/20 text-accent-warning border border-accent-warning/30" title="Must change password on next login">
                        Temp Pass
                      </span>
                    )}
                  </td>
                  <td className="px-6 py-4">{u.displayName || '-'}</td>
                  <td className="px-6 py-4">
                    <select
                      value={u.role}
                      onChange={(e) => handleRoleChange(u.id, e.target.value)}
                      className="bg-bg-base border border-border-subtle rounded px-2 py-1 text-xs focus:border-accent-primary outline-none"
                    >
                      <option value="gnm-admin">Admin</option>
                      <option value="gnm-operator">Operator</option>
                      <option value="gnm-viewer">Viewer</option>
                    </select>
                  </td>
                  <td className="px-6 py-4">
                    <button
                      onClick={() => handleEnabledChange(u.id, !u.enabled)}
                      className={`px-3 py-1 rounded-full text-xs font-semibold flex items-center gap-1.5 border ${
                        u.enabled
                          ? 'bg-accent-success/10 text-accent-success border-accent-success/20'
                          : 'bg-text-muted/10 text-text-muted border-text-muted/20'
                      }`}
                    >
                      {u.enabled ? <><Check className="w-3 h-3" /> Enabled</> : <><X className="w-3 h-3" /> Disabled</>}
                    </button>
                  </td>
                  <td className="px-6 py-4">
                    <div className="flex items-center gap-2">
                      <button
                        onClick={() => handleResetPassword(u.id)}
                        className="p-1.5 text-text-muted hover:text-accent-info hover:bg-accent-info/10 rounded transition-colors"
                        title="Reset Password"
                      >
                        <KeyRound className="w-4 h-4" />
                      </button>
                      <button
                        onClick={() => handleDelete(u.id)}
                        className="p-1.5 text-text-muted hover:text-accent-danger hover:bg-accent-danger/10 rounded transition-colors"
                        title="Delete User"
                      >
                        <Trash2 className="w-4 h-4" />
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      {showAddModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 backdrop-blur-sm">
          <div className="w-full max-w-md bg-bg-surface border border-border-subtle rounded-2xl p-6 shadow-2xl">
            <h3 className="text-xl font-bold text-text-primary mb-6">Add New User</h3>
            <form onSubmit={handleAddUser} className="space-y-4">
              <div>
                <label className="block text-xs font-semibold text-text-secondary uppercase mb-1">Username</label>
                <input
                  type="text"
                  required
                  value={newUser.username}
                  onChange={e => setNewUser({...newUser, username: e.target.value})}
                  className="w-full bg-bg-base border border-border-subtle rounded-lg px-3 py-2 text-sm text-text-primary outline-none focus:border-accent-primary"
                />
              </div>
              <div>
                <label className="block text-xs font-semibold text-text-secondary uppercase mb-1">Display Name</label>
                <input
                  type="text"
                  value={newUser.displayName}
                  onChange={e => setNewUser({...newUser, displayName: e.target.value})}
                  className="w-full bg-bg-base border border-border-subtle rounded-lg px-3 py-2 text-sm text-text-primary outline-none focus:border-accent-primary"
                />
              </div>
              <div>
                <label className="block text-xs font-semibold text-text-secondary uppercase mb-1">Initial Password</label>
                <input
                  type="password"
                  required
                  value={newUser.password}
                  onChange={e => setNewUser({...newUser, password: e.target.value})}
                  className="w-full bg-bg-base border border-border-subtle rounded-lg px-3 py-2 text-sm text-text-primary outline-none focus:border-accent-primary"
                  placeholder="Min 8 chars (user must change on login)"
                />
              </div>
              <div>
                <label className="block text-xs font-semibold text-text-secondary uppercase mb-1">Role</label>
                <select
                  value={newUser.role}
                  onChange={e => setNewUser({...newUser, role: e.target.value})}
                  className="w-full bg-bg-base border border-border-subtle rounded-lg px-3 py-2 text-sm text-text-primary outline-none focus:border-accent-primary"
                >
                  <option value="gnm-admin">Admin</option>
                  <option value="gnm-operator">Operator</option>
                  <option value="gnm-viewer">Viewer</option>
                </select>
              </div>

              <div className="flex justify-end gap-3 mt-8">
                <button
                  type="button"
                  onClick={() => setShowAddModal(false)}
                  className="px-4 py-2 text-sm font-semibold text-text-secondary hover:text-text-primary transition-colors"
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  disabled={saving || newUser.password.length < 8}
                  className="px-4 py-2 bg-accent-primary text-bg-base text-sm font-semibold rounded-lg hover:bg-accent-primary/90 transition-colors disabled:opacity-50"
                >
                  {saving ? 'Saving...' : 'Add User'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  )
}
