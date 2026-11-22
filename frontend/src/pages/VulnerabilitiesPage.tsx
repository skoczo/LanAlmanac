import React, { useCallback, useEffect, useState } from 'react'
import { Link } from '@tanstack/react-router'
import { useAuth } from '../lib/auth/auth-context'
import {
  DeviceVulnerabilityItem,
  VulnerabilitySyncSettings,
  VulnerabilitySyncStatusItem
} from '../lib/api/packages'
import {
  AlertCircle,
  Calendar,
  CheckCircle2,
  Clock,
  Database,
  Loader2,
  RefreshCw,
  Save,
  Server,
  ShieldAlert,
  ShieldCheck
} from 'lucide-react'

const CRON_PRESETS = [
  { label: 'Daily at 02:00 UTC (Recommended)', value: '0 2 * * *' },
  { label: 'Daily at 00:00 UTC', value: '0 0 * * *' },
  { label: 'Every 12 Hours', value: '0 */12 * * *' },
  { label: 'Every 6 Hours', value: '0 */6 * * *' },
  { label: 'Weekly on Sunday at 03:00 UTC', value: '0 3 * * 0' },
  { label: 'Custom Cron Expression...', value: 'CUSTOM' }
]

export const VulnerabilitiesPage: React.FC = () => {
  const { apiClient, user } = useAuth()
  const canEdit = user?.roles.includes('gnm-admin') || user?.roles.includes('gnm-operator')

  const [activeTab, setActiveTab] = useState<'findings' | 'database'>('findings')

  // Findings state
  const [vulns, setVulns] = useState<DeviceVulnerabilityItem[]>([])
  const [severity, setSeverity] = useState('')
  const [status, setStatus] = useState('OPEN')
  const [fixAvailableOnly, setFixAvailableOnly] = useState(false)
  const [loadingVulns, setLoadingVulns] = useState(true)

  // Sync settings & status state
  const [syncSettings, setSyncSettings] = useState<VulnerabilitySyncSettings | null>(null)
  const [syncStatuses, setSyncStatuses] = useState<VulnerabilitySyncStatusItem[]>([])
  const [loadingSync, setLoadingSync] = useState(false)
  const [savingSettings, setSavingSettings] = useState(false)
  const [saveSuccessMsg, setSaveSuccessMsg] = useState<string | null>(null)
  const [saveErrorMsg, setSaveErrorMsg] = useState<string | null>(null)

  // Edit form state
  const [autoSyncEnabled, setAutoSyncEnabled] = useState(true)
  const [cronPreset, setCronPreset] = useState('0 2 * * *')
  const [customCron, setCustomCron] = useState('')
  const [autoDetect, setAutoDetect] = useState(true)
  const [configuredEcosystemsInput, setConfiguredEcosystemsInput] = useState('Debian')
  const [syncingEcosystem, setSyncingEcosystem] = useState<string | null>(null)

  const loadFleetVulns = useCallback(async () => {
    setLoadingVulns(true)
    try {
      const params = new URLSearchParams()
      if (severity) params.set('severity', severity)
      if (status) params.set('status', status)
      if (fixAvailableOnly) params.set('fixAvailable', 'true')
      const qs = params.toString() ? `?${params.toString()}` : ''
      const data = await apiClient<DeviceVulnerabilityItem[]>(`/api/vulnerabilities${qs}`)
      setVulns(data)
    } catch (err) {
      console.error('Failed to load fleet vulnerabilities', err)
    } finally {
      setLoadingVulns(false)
    }
  }, [apiClient, severity, status, fixAvailableOnly])

  const loadSyncData = useCallback(async () => {
    setLoadingSync(true)
    try {
      const [settings, statuses] = await Promise.all([
        apiClient<VulnerabilitySyncSettings>('/api/vulnerabilities/sync/settings'),
        apiClient<VulnerabilitySyncStatusItem[]>('/api/vulnerabilities/sync/status')
      ])

      setSyncSettings(settings)
      setSyncStatuses(statuses)

      setAutoSyncEnabled(settings.autoSyncEnabled)
      setAutoDetect(settings.autoDetectEcosystems)
      setConfiguredEcosystemsInput(settings.configuredEcosystems.join(', '))

      const matchedPreset = CRON_PRESETS.find((p) => p.value === settings.cronExpression)
      if (matchedPreset) {
        setCronPreset(matchedPreset.value)
        setCustomCron('')
      } else {
        setCronPreset('CUSTOM')
        setCustomCron(settings.cronExpression)
      }
    } catch (err) {
      console.error('Failed to load vulnerability sync data', err)
    } finally {
      setLoadingSync(false)
    }
  }, [apiClient])

  useEffect(() => {
    loadFleetVulns()
  }, [loadFleetVulns])

  useEffect(() => {
    if (activeTab === 'database') {
      loadSyncData()
    }
  }, [activeTab, loadSyncData])

  // Polling when any ecosystem is syncing
  const hasActiveSync = syncStatuses.some((s) => s.status === 'SYNCING') || syncingEcosystem !== null
  useEffect(() => {
    if (activeTab !== 'database') return
    if (!hasActiveSync) return

    const interval = setInterval(() => {
      loadSyncData()
    }, 3000)
    return () => clearInterval(interval)
  }, [activeTab, hasActiveSync, loadSyncData])

  const handleSaveSettings = async () => {
    setSavingSettings(true)
    setSaveSuccessMsg(null)
    setSaveErrorMsg(null)

    const finalCron = cronPreset === 'CUSTOM' ? customCron.trim() : cronPreset
    const finalEcosystems = configuredEcosystemsInput
      .split(',')
      .map((s) => s.trim())
      .filter(Boolean)

    try {
      const updated = await apiClient<VulnerabilitySyncSettings>('/api/vulnerabilities/sync/settings', {
        method: 'PUT',
        body: JSON.stringify({
          autoSyncEnabled,
          cronExpression: finalCron,
          configuredEcosystems: finalEcosystems.length > 0 ? finalEcosystems : ['Debian'],
          autoDetectEcosystems: autoDetect
        })
      })

      setSyncSettings(updated)
      setSaveSuccessMsg('Vulnerability database synchronization settings saved successfully.')
      setTimeout(() => setSaveSuccessMsg(null), 4000)
    } catch (err: unknown) {
      setSaveErrorMsg(err instanceof Error ? err.message : String(err))
    } finally {
      setSavingSettings(false)
    }
  }

  const handleTriggerSync = async (ecosystem?: string) => {
    const target = ecosystem || 'ALL'
    setSyncingEcosystem(target)
    try {
      const queryParam = ecosystem ? `?ecosystem=${encodeURIComponent(ecosystem)}&async=true` : '?async=true'
      await apiClient(`/api/vulnerabilities/sync${queryParam}`, {
        method: 'POST'
      })
      await loadSyncData()
    } catch (err: unknown) {
      alert(`Sync trigger failed: ${err instanceof Error ? err.message : String(err)}`)
    } finally {
      setSyncingEcosystem(null)
    }
  }

  const criticalCount = vulns.filter((v) => v.severity === 'CRITICAL').length
  const highCount = vulns.filter((v) => v.severity === 'HIGH').length
  const fixReadyCount = vulns.filter((v) => v.fixAvailableNow).length

  return (
    <div className="p-8 space-y-6 max-w-7xl mx-auto">
      {/* Page Header */}
      <div className="flex flex-wrap items-center justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold tracking-tight flex items-center gap-2.5 text-text-primary">
            <ShieldAlert className="w-7 h-7 text-accent-danger" />
            Fleet Vulnerability Center
          </h1>
          <p className="text-sm text-text-secondary mt-1">
            Centralized passive CVE assessment across managed hosts & local offline database management
          </p>
        </div>
      </div>

      {/* Tabs */}
      <div className="flex items-center gap-2 border-b border-border-subtle">
        <button
          onClick={() => setActiveTab('findings')}
          className={`flex items-center gap-2 px-4 py-3 text-sm font-semibold border-b-2 transition-colors cursor-pointer ${
            activeTab === 'findings'
              ? 'border-accent-primary text-accent-primary'
              : 'border-transparent text-text-secondary hover:text-text-primary'
          }`}
        >
          <ShieldAlert className="w-4 h-4" /> Fleet Findings
          {vulns.length > 0 && (
            <span className="ml-1.5 px-2 py-0.5 rounded-full text-[10px] font-bold bg-bg-surface-raised border border-border-subtle">
              {vulns.length}
            </span>
          )}
        </button>
        <button
          onClick={() => setActiveTab('database')}
          className={`flex items-center gap-2 px-4 py-3 text-sm font-semibold border-b-2 transition-colors cursor-pointer ${
            activeTab === 'database'
              ? 'border-accent-primary text-accent-primary'
              : 'border-transparent text-text-secondary hover:text-text-primary'
          }`}
        >
          <Database className="w-4 h-4" /> Vulnerability Database & Sync
          {hasActiveSync && (
            <span className="inline-block w-2 h-2 rounded-full bg-accent-warning animate-pulse ml-1" />
          )}
        </button>
      </div>

      {/* Tab 1: Fleet Findings */}
      {activeTab === 'findings' && (
        <div className="space-y-6">
          {/* Filters Bar */}
          <div className="flex flex-wrap items-center justify-between gap-4">
            <div className="flex flex-wrap items-center gap-3">
              <select
                aria-label="Filter by status"
                value={status}
                onChange={(e) => setStatus(e.target.value)}
                className="bg-bg-surface border border-border-subtle rounded-xl px-3 py-2 text-xs text-text-primary"
              >
                <option value="">All Statuses</option>
                <option value="OPEN">Open</option>
                <option value="ACCEPTED_RISK">Accepted Risk</option>
                <option value="FALSE_POSITIVE">False Positive</option>
                <option value="FIXED">Fixed</option>
              </select>

              <select
                aria-label="Filter by severity"
                value={severity}
                onChange={(e) => setSeverity(e.target.value)}
                className="bg-bg-surface border border-border-subtle rounded-xl px-3 py-2 text-xs text-text-primary"
              >
                <option value="">All Severities</option>
                <option value="CRITICAL">Critical</option>
                <option value="HIGH">High</option>
                <option value="MEDIUM">Medium</option>
                <option value="LOW">Low</option>
              </select>

              <label className="flex items-center gap-2 text-xs text-text-secondary cursor-pointer">
                <input
                  type="checkbox"
                  checked={fixAvailableOnly}
                  onChange={(e) => setFixAvailableOnly(e.target.checked)}
                  className="accent-accent-primary"
                />
                Fix available in repo
              </label>
            </div>
          </div>

          {/* Summary KPIs */}
          <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
            <div className="bg-bg-surface border border-border-subtle rounded-2xl p-5">
              <span className="text-xs text-text-secondary uppercase font-bold">Total Matching</span>
              <p className="text-2xl font-bold text-text-primary mt-1">{vulns.length}</p>
            </div>
            <div className="bg-bg-surface border border-accent-danger/30 rounded-2xl p-5">
              <span className="text-xs text-accent-danger uppercase font-bold">Critical</span>
              <p className="text-2xl font-bold text-accent-danger mt-1">{criticalCount}</p>
            </div>
            <div className="bg-bg-surface border border-orange-500/30 rounded-2xl p-5">
              <span className="text-xs text-orange-400 uppercase font-bold">High</span>
              <p className="text-2xl font-bold text-orange-400 mt-1">{highCount}</p>
            </div>
            <div className="bg-bg-surface border border-accent-success/30 rounded-2xl p-5">
              <span className="text-xs text-accent-success uppercase font-bold">Patch Ready Now</span>
              <p className="text-2xl font-bold text-accent-success mt-1">{fixReadyCount}</p>
            </div>
          </div>

          {/* Findings Table */}
          <div className="bg-bg-surface border border-border-subtle rounded-2xl p-6 shadow-lg">
            {loadingVulns ? (
              <div className="py-12 text-center text-xs text-text-muted">Loading fleet vulnerabilities...</div>
            ) : vulns.length === 0 ? (
              <div className="py-12 text-center text-xs text-text-secondary flex flex-col items-center gap-2">
                <ShieldCheck className="w-8 h-8 text-accent-success" />
                <span>No vulnerabilities match the current filter across the fleet.</span>
              </div>
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-left border-collapse text-xs">
                  <thead>
                    <tr className="border-b border-border-subtle text-text-secondary uppercase tracking-wider text-[10px]">
                      <th className="py-2.5 px-3">Severity</th>
                      <th className="py-2.5 px-3">Advisory / CVE</th>
                      <th className="py-2.5 px-3">Device</th>
                      <th className="py-2.5 px-3">Package</th>
                      <th className="py-2.5 px-3">Installed</th>
                      <th className="py-2.5 px-3">Fixed Version</th>
                      <th className="py-2.5 px-3">Status</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-border-subtle/50">
                    {vulns.map((v) => (
                      <tr key={v.id} className="hover:bg-bg-surface-raised/60">
                        <td className="py-2.5 px-3 font-bold">{v.severity}</td>
                        <td className="py-2.5 px-3 font-mono font-semibold text-text-primary">
                          {v.advisoryId}
                          {v.cveAliases.length > 0 && (
                            <span className="ml-2 text-text-muted">({v.cveAliases.join(', ')})</span>
                          )}
                        </td>
                        <td className="py-2.5 px-3">
                          <Link
                            to="/devices/$id"
                            params={{ id: v.deviceId }}
                            className="text-accent-primary hover:underline font-semibold"
                          >
                            {v.deviceName || v.deviceId}
                          </Link>
                        </td>
                        <td className="py-2.5 px-3 font-mono">{v.packageName}</td>
                        <td className="py-2.5 px-3 font-mono text-text-secondary">{v.installedVersion}</td>
                        <td className="py-2.5 px-3 font-mono text-accent-success">
                          {v.fixedVersion || '-'}
                          {v.fixAvailableNow && (
                            <span className="ml-2 px-1.5 py-0.5 rounded text-[10px] font-bold bg-accent-success/20 text-accent-success">
                              Ready
                            </span>
                          )}
                        </td>
                        <td className="py-2.5 px-3">{v.status}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        </div>
      )}

      {/* Tab 2: Database & Sync */}
      {activeTab === 'database' && (
        <div className="space-y-6">
          {loadingSync && !syncSettings ? (
            <div className="py-16 text-center text-xs text-text-muted flex items-center justify-center gap-2">
              <Loader2 className="w-5 h-5 animate-spin text-accent-primary" />
              <span>Loading vulnerability database settings & feeds...</span>
            </div>
          ) : (
            <>
              {/* Auto-Sync Configuration Card */}
          <div className="bg-bg-surface border border-border-subtle rounded-2xl p-6 shadow-lg space-y-5">
            <div className="flex flex-wrap items-center justify-between gap-4 pb-4 border-b border-border-subtle">
              <div>
                <h2 className="text-base font-bold text-text-primary flex items-center gap-2">
                  <Clock className="w-5 h-5 text-accent-primary" />
                  Automated Database Synchronization
                </h2>
                <p className="text-xs text-text-secondary mt-0.5">
                  Configure recurring background downloads of official OSV vulnerability feeds for local matching
                </p>
              </div>

              {canEdit && (
                <button
                  onClick={handleSaveSettings}
                  disabled={savingSettings}
                  className="flex items-center gap-2 px-4 py-2 bg-accent-primary text-text-inverse font-semibold text-xs rounded-xl hover:bg-accent-primary/90 transition-colors disabled:opacity-50 cursor-pointer"
                >
                  {savingSettings ? <Loader2 className="w-4 h-4 animate-spin" /> : <Save className="w-4 h-4" />}
                  Save Settings
                </button>
              )}
            </div>

            {saveSuccessMsg && (
              <div className="p-3 bg-accent-success/10 border border-accent-success/30 rounded-xl text-xs text-accent-success flex items-center gap-2">
                <CheckCircle2 className="w-4 h-4 shrink-0" />
                <span>{saveSuccessMsg}</span>
              </div>
            )}

            {saveErrorMsg && (
              <div className="p-3 bg-accent-danger/10 border border-accent-danger/30 rounded-xl text-xs text-accent-danger flex items-center gap-2">
                <AlertCircle className="w-4 h-4 shrink-0" />
                <span>{saveErrorMsg}</span>
              </div>
            )}

            <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
              {/* Left Column: Enable toggle & Schedule */}
              <div className="space-y-4">
                <div className="flex items-center justify-between p-4 bg-bg-surface-raised/40 border border-border-subtle rounded-xl">
                  <div>
                    <span className="text-xs font-bold text-text-primary block">Automated Sync Schedule</span>
                    <span className="text-[11px] text-text-secondary block mt-0.5">
                      Enable periodic virtual-thread sync of OSV ecosystem feeds
                    </span>
                  </div>
                  <label className="relative inline-flex items-center cursor-pointer">
                    <input
                      type="checkbox"
                      checked={autoSyncEnabled}
                      disabled={!canEdit}
                      onChange={(e) => setAutoSyncEnabled(e.target.checked)}
                      className="sr-only peer"
                    />
                    <div className="w-11 h-6 bg-border-subtle peer-focus:outline-none rounded-full peer peer-checked:after:translate-x-full peer-checked:after:border-white after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-white after:border-gray-300 after:border after:rounded-full after:h-5 after:w-5 after:transition-all peer-checked:bg-accent-primary"></div>
                  </label>
                </div>

                <div className="space-y-1.5">
                  <label className="text-xs font-bold text-text-primary block">Sync Frequency</label>
                  <select
                    disabled={!canEdit || !autoSyncEnabled}
                    value={cronPreset}
                    onChange={(e) => setCronPreset(e.target.value)}
                    className="w-full bg-bg-surface-raised border border-border-subtle rounded-xl px-3 py-2 text-xs text-text-primary disabled:opacity-50"
                  >
                    {CRON_PRESETS.map((p) => (
                      <option key={p.value} value={p.value}>
                        {p.label}
                      </option>
                    ))}
                  </select>
                </div>

                {cronPreset === 'CUSTOM' && (
                  <div className="space-y-1.5">
                    <label className="text-xs font-bold text-text-primary block">
                      Custom POSIX Cron Expression (5-field)
                    </label>
                    <input
                      type="text"
                      disabled={!canEdit || !autoSyncEnabled}
                      value={customCron}
                      onChange={(e) => setCustomCron(e.target.value)}
                      placeholder="e.g. 0 2 * * *"
                      className="w-full bg-bg-surface-raised border border-border-subtle rounded-xl px-3 py-2 text-xs font-mono text-text-primary disabled:opacity-50"
                    />
                    <span className="text-[10px] text-text-muted block">
                      Format: minute (0-59) hour (0-23) day-of-month (1-31) month (1-12) day-of-week (0-7)
                    </span>
                  </div>
                )}
              </div>

              {/* Right Column: Ecosystem detection & schedule previews */}
              <div className="space-y-4">
                <div className="p-4 bg-bg-surface-raised/40 border border-border-subtle rounded-xl space-y-3">
                  <div className="flex items-center justify-between">
                    <div>
                      <span className="text-xs font-bold text-text-primary block">Fleet Ecosystem Auto-Detection</span>
                      <span className="text-[11px] text-text-secondary block mt-0.5">
                        Automatically include distributions detected across managed hosts
                      </span>
                    </div>
                    <label className="relative inline-flex items-center cursor-pointer">
                      <input
                        type="checkbox"
                        checked={autoDetect}
                        disabled={!canEdit}
                        onChange={(e) => setAutoDetect(e.target.checked)}
                        className="sr-only peer"
                      />
                      <div className="w-11 h-6 bg-border-subtle peer-focus:outline-none rounded-full peer peer-checked:after:translate-x-full peer-checked:after:border-white after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-white after:border-gray-300 after:border after:rounded-full after:h-5 after:w-5 after:transition-all peer-checked:bg-accent-primary"></div>
                    </label>
                  </div>

                  {syncSettings?.detectedEcosystems && syncSettings.detectedEcosystems.length > 0 && (
                    <div className="pt-2 border-t border-border-subtle flex flex-wrap items-center gap-2">
                      <span className="text-[10px] text-text-muted uppercase font-bold">Detected in fleet:</span>
                      {syncSettings.detectedEcosystems.map((eco) => (
                        <span
                          key={eco}
                          className="px-2 py-0.5 rounded-lg text-[10px] font-mono font-semibold bg-accent-primary/15 text-accent-primary border border-accent-primary/30"
                        >
                          {eco}
                        </span>
                      ))}
                    </div>
                  )}
                </div>

                <div className="space-y-1.5">
                  <label className="text-xs font-bold text-text-primary block">Additional Configured Ecosystems</label>
                  <input
                    type="text"
                    disabled={!canEdit}
                    value={configuredEcosystemsInput}
                    onChange={(e) => setConfiguredEcosystemsInput(e.target.value)}
                    placeholder="e.g. Debian, Ubuntu, Alpine"
                    className="w-full bg-bg-surface-raised border border-border-subtle rounded-xl px-3 py-2 text-xs font-mono text-text-primary disabled:opacity-50"
                  />
                  <span className="text-[10px] text-text-muted block">
                    Comma-separated ecosystem feed names (e.g. Debian, Ubuntu, Alpine)
                  </span>
                </div>

                {/* Schedule Run Previews */}
                <div className="grid grid-cols-2 gap-3 pt-1">
                  <div className="p-3 bg-bg-surface-raised border border-border-subtle rounded-xl">
                    <span className="text-[10px] text-text-muted uppercase font-bold flex items-center gap-1.5">
                      <Calendar className="w-3.5 h-3.5" /> Next Scheduled Run
                    </span>
                    <p className="text-xs font-mono font-semibold text-text-primary mt-1">
                      {syncSettings?.nextRunAt ? new Date(syncSettings.nextRunAt).toLocaleString() : 'Disabled'}
                    </p>
                  </div>

                  <div className="p-3 bg-bg-surface-raised border border-border-subtle rounded-xl">
                    <span className="text-[10px] text-text-muted uppercase font-bold flex items-center gap-1.5">
                      <Clock className="w-3.5 h-3.5" /> Last Auto Sync
                    </span>
                    <p className="text-xs font-mono font-semibold text-text-primary mt-1">
                      {syncSettings?.lastRunAt ? new Date(syncSettings.lastRunAt).toLocaleString() : 'Never'}
                    </p>
                  </div>
                </div>
              </div>
            </div>
          </div>

          {/* Local Feeds Status & Manual Sync Card */}
          <div className="bg-bg-surface border border-border-subtle rounded-2xl p-6 shadow-lg space-y-4">
            <div className="flex flex-wrap items-center justify-between gap-4">
              <div>
                <h2 className="text-base font-bold text-text-primary flex items-center gap-2">
                  <Server className="w-5 h-5 text-accent-success" />
                  Local Ecosystem Feeds & Offline Database
                </h2>
                <p className="text-xs text-text-secondary mt-0.5">
                  Status of indexed OSV security advisory dumps stored locally in PostgreSQL
                </p>
              </div>

              {canEdit && (
                <button
                  onClick={() => handleTriggerSync()}
                  disabled={hasActiveSync}
                  className="flex items-center gap-2 px-3.5 py-2 bg-bg-surface-raised border border-border-subtle text-text-primary font-semibold text-xs rounded-xl hover:bg-bg-surface-raised/80 transition-colors disabled:opacity-50 cursor-pointer"
                >
                  <RefreshCw className={`w-3.5 h-3.5 ${hasActiveSync ? 'animate-spin' : ''}`} />
                  Sync All Feeds Now
                </button>
              )}
            </div>

            {/* Feeds Table */}
            <div className="overflow-x-auto">
              <table className="w-full text-left border-collapse text-xs">
                <thead>
                  <tr className="border-b border-border-subtle text-text-secondary uppercase tracking-wider text-[10px]">
                    <th className="py-2.5 px-3">Ecosystem</th>
                    <th className="py-2.5 px-3">Status</th>
                    <th className="py-2.5 px-3">Cached Advisories</th>
                    <th className="py-2.5 px-3">Last Synced</th>
                    <th className="py-2.5 px-3">Error Details</th>
                    {canEdit && <th className="py-2.5 px-3 text-right">Actions</th>}
                  </tr>
                </thead>
                <tbody className="divide-y divide-border-subtle/50">
                  {syncStatuses.length === 0 ? (
                    <tr>
                      <td colSpan={6} className="py-8 text-center text-text-secondary text-xs">
                        No vulnerability feeds have been synced yet. Click &quot;Sync All Feeds Now&quot; or wait for the
                        scheduled auto-sync.
                      </td>
                    </tr>
                  ) : (
                    syncStatuses.map((feed) => {
                      const isFeedSyncing = feed.status === 'SYNCING' || syncingEcosystem === feed.ecosystem
                      return (
                        <tr key={feed.ecosystem} className="hover:bg-bg-surface-raised/60">
                          <td className="py-3 px-3 font-mono font-bold text-text-primary">{feed.ecosystem}</td>
                          <td className="py-3 px-3">
                            {feed.status === 'SUCCESS' && (
                              <span className="inline-flex items-center gap-1.5 px-2 py-0.5 rounded-full text-[10px] font-bold bg-accent-success/20 text-accent-success">
                                <CheckCircle2 className="w-3 h-3" /> Synced
                              </span>
                            )}
                            {feed.status === 'SYNCING' && (
                              <span className="inline-flex items-center gap-1.5 px-2 py-0.5 rounded-full text-[10px] font-bold bg-accent-warning/20 text-accent-warning">
                                <Loader2 className="w-3 h-3 animate-spin" /> Syncing...
                              </span>
                            )}
                            {feed.status === 'FAILED' && (
                              <span className="inline-flex items-center gap-1.5 px-2 py-0.5 rounded-full text-[10px] font-bold bg-accent-danger/20 text-accent-danger">
                                <AlertCircle className="w-3 h-3" /> Failed
                              </span>
                            )}
                            {feed.status === 'IDLE' && (
                              <span className="inline-flex items-center gap-1.5 px-2 py-0.5 rounded-full text-[10px] font-bold bg-bg-surface-raised text-text-muted">
                                Idle
                              </span>
                            )}
                          </td>
                          <td className="py-3 px-3 font-mono text-text-primary">
                            {feed.status === 'SYNCING' ? (
                              <span className="inline-flex items-center gap-1.5 text-accent-warning">
                                <Loader2 className="w-3.5 h-3.5 animate-spin" />
                                {feed.advisoryCount > 0 ? (
                                  <span>{feed.advisoryCount.toLocaleString()} indexed...</span>
                                ) : (
                                  <span className="text-text-muted">Downloading feed...</span>
                                )}
                              </span>
                            ) : feed.advisoryCount ? (
                              `${feed.advisoryCount.toLocaleString()} advisories`
                            ) : (
                              '-'
                            )}
                          </td>
                          <td className="py-3 px-3 text-text-secondary">
                            {feed.lastSyncedAt ? new Date(feed.lastSyncedAt).toLocaleString() : 'Never'}
                          </td>
                          <td className="py-3 px-3 text-accent-danger font-mono text-[11px]">
                            {feed.errorMessage || '-'}
                          </td>
                          {canEdit && (
                            <td className="py-3 px-3 text-right">
                              <button
                                onClick={() => handleTriggerSync(feed.ecosystem)}
                                disabled={isFeedSyncing}
                                className="inline-flex items-center gap-1.5 px-2.5 py-1 text-xs font-semibold rounded-lg bg-bg-surface-raised border border-border-subtle hover:bg-bg-surface text-text-primary disabled:opacity-50 cursor-pointer"
                              >
                                <RefreshCw className={`w-3 h-3 ${isFeedSyncing ? 'animate-spin' : ''}`} />
                                Sync Now
                              </button>
                            </td>
                          )}
                        </tr>
                      )
                    })
                  )}
                </tbody>
              </table>
            </div>
          </div>
        </>
      )}
        </div>
      )}
    </div>
  )
}
