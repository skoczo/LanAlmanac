import { useState, useEffect } from 'react'
import { Save, Loader2, AlertCircle, Settings as SettingsIcon, Users } from 'lucide-react'
import { useAuth } from '../lib/auth/auth-context'
import { UsersTab } from './UsersTab'

interface Setting {
  key: string
  value: string
}

export const Settings = () => {
  const [activeTab, setActiveTab] = useState<'system' | 'users'>('system')
  const [settings, setSettings] = useState<Setting[]>([])
  const [interfaces, setInterfaces] = useState<string[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [savingKey, setSavingKey] = useState<string | null>(null)
  const [editedValues, setEditedValues] = useState<Record<string, string>>({})
  
  const { apiClient, user } = useAuth()
  const isAdmin = user?.roles.includes('gnm-admin')

  useEffect(() => {
    fetchSettings()
  }, [])

  const fetchSettings = async () => {
    try {
      setLoading(true)
      const data = await apiClient<Setting[]>('/api/settings')
      
      // Ensure specific keys are always present
      const requiredKeys = ['gnm.listen.interface', 'oidc.enabled', 'oidc.authority.url', 'oidc.client.id', 'oidc.role.claim.path']
      requiredKeys.forEach(k => {
        if (!data.some((s: Setting) => s.key === k)) {
          data.push({ key: k, value: '' })
        }
      })
      
      setSettings(data)
      
      try {
        const ifaces = await apiClient<string[]>('/api/settings/interfaces')
        setInterfaces(ifaces)
      } catch (err) {
        console.error('Failed to fetch network interfaces', err)
      }
      
      const initialEdits: Record<string, string> = {}
      data.forEach((s: Setting) => {
        initialEdits[s.key] = s.value
      })
      setEditedValues(initialEdits)
    } catch (err: any) {
      setError(err.message)
    } finally {
      setLoading(false)
    }
  }

  const handleSave = async (settingKey: string) => {
    try {
      setSavingKey(settingKey)
      const newValue = editedValues[settingKey]
      
      await apiClient(`/api/settings/${settingKey}`, {
        method: 'PUT',
        body: JSON.stringify({ key: settingKey, value: newValue })
      })
      
      setSettings(settings.map((s: Setting) => s.key === settingKey ? { ...s, value: newValue } : s))
    } catch (err: any) {
      alert(`Error saving ${settingKey}: ${err.message}`)
    } finally {
      setSavingKey(null)
    }
  }

  const handleValueChange = (key: string, value: string) => {
    setEditedValues(prev => ({
      ...prev,
      [key]: value
    }))
  }

  if (loading) {
    return (
      <div className="flex items-center justify-center h-full">
        <Loader2 className="w-8 h-8 animate-spin text-accent-primary" />
      </div>
    )
  }

  if (error) {
    return (
      <div className="flex flex-col items-center justify-center h-full text-accent-danger gap-4">
        <AlertCircle className="w-12 h-12" />
        <p>{error}</p>
        <button 
          onClick={fetchSettings}
          className="px-4 py-2 bg-accent-danger/10 border border-accent-danger/20 rounded-xl hover:bg-accent-danger/20 transition-colors"
        >
          Retry
        </button>
      </div>
    )
  }

  return (
    <div className="max-w-5xl mx-auto space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-text-primary tracking-tight">System Settings</h1>
          <p className="text-sm text-text-secondary mt-1">
            Configure global application behavior and users.
          </p>
        </div>
      </div>

      <div className="flex items-center gap-2 border-b border-border-subtle">
        <button
          onClick={() => setActiveTab('system')}
          className={`flex items-center gap-2 px-4 py-3 text-sm font-semibold border-b-2 transition-colors ${
            activeTab === 'system'
              ? 'border-accent-primary text-accent-primary'
              : 'border-transparent text-text-secondary hover:text-text-primary'
          }`}
        >
          <SettingsIcon className="w-4 h-4" /> Global Settings
        </button>
        {isAdmin && (
          <button
            onClick={() => setActiveTab('users')}
            className={`flex items-center gap-2 px-4 py-3 text-sm font-semibold border-b-2 transition-colors ${
              activeTab === 'users'
                ? 'border-accent-primary text-accent-primary'
                : 'border-transparent text-text-secondary hover:text-text-primary'
            }`}
          >
            <Users className="w-4 h-4" /> User Management
          </button>
        )}
      </div>

      {activeTab === 'system' ? (
        <div className="glass-panel rounded-2xl border border-border-subtle overflow-hidden">
          <div className="p-6 space-y-6">
            {settings.length === 0 ? (
              <div className="text-center py-8 text-text-muted">
                No settings found.
              </div>
            ) : (
              <div className="space-y-4">
                {settings.map((setting) => {
                  const isChanged = setting.value !== editedValues[setting.key]
                  const isSaving = savingKey === setting.key
                  
                  return (
                    <div key={setting.key} className="flex flex-col md:flex-row md:items-center gap-4 p-4 rounded-xl bg-bg-surface border border-border-subtle">
                      <div className="flex-1">
                        <label className="block text-sm font-semibold text-text-primary mb-1">
                          {setting.key}
                        </label>
                        {setting.key === 'gnm.listen.interface' ? (
                          <select
                            value={editedValues[setting.key] || ''}
                            onChange={(e) => handleValueChange(setting.key, e.target.value)}
                            className="w-full bg-bg-base border border-border-subtle rounded-lg px-3 py-2 text-sm text-text-primary focus:outline-none focus:border-accent-primary transition-colors"
                          >
                            <option value="" disabled>Select an interface</option>
                            {interfaces.map((iface) => (
                              <option key={iface} value={iface}>
                                {iface}
                              </option>
                            ))}
                            {editedValues[setting.key] && !interfaces.includes(editedValues[setting.key]) && (
                              <option value={editedValues[setting.key]}>{editedValues[setting.key]} (Not found)</option>
                            )}
                          </select>
                        ) : setting.key === 'oidc.enabled' ? (
                          <select
                            value={editedValues[setting.key] || 'false'}
                            onChange={(e) => handleValueChange(setting.key, e.target.value)}
                            className="w-full bg-bg-base border border-border-subtle rounded-lg px-3 py-2 text-sm text-text-primary focus:outline-none focus:border-accent-primary transition-colors"
                          >
                            <option value="true">True</option>
                            <option value="false">False</option>
                          </select>
                        ) : (
                          <input
                            type="text"
                            value={editedValues[setting.key] || ''}
                            onChange={(e) => handleValueChange(setting.key, e.target.value)}
                            placeholder={setting.key === 'oidc.role.claim.path' ? 'e.g. groups or realm_access/roles' : ''}
                            className="w-full bg-bg-base border border-border-subtle rounded-lg px-3 py-2 text-sm text-text-primary focus:outline-none focus:border-accent-primary transition-colors"
                          />
                        )}
                      </div>
                      
                      <div className="flex items-end md:self-end h-[60px] pb-1">
                        <button
                          onClick={() => handleSave(setting.key)}
                          disabled={!isChanged || isSaving}
                          className={`flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-semibold transition-all ${
                            isChanged 
                              ? 'bg-accent-primary text-bg-base hover:bg-accent-primary/90 glow-primary cursor-pointer' 
                              : 'bg-bg-surface-raised text-text-muted border border-border-subtle cursor-not-allowed'
                          }`}
                        >
                          {isSaving ? (
                            <Loader2 className="w-4 h-4 animate-spin" />
                          ) : (
                            <Save className="w-4 h-4" />
                          )}
                          <span>Save</span>
                        </button>
                      </div>
                    </div>
                  )
                })}
              </div>
            )}
          </div>
        </div>
      ) : (
        <UsersTab />
      )}
    </div>
  )
}
