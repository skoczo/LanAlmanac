import React, { useCallback, useEffect, useState } from 'react'
import { useAuth } from '../lib/auth/auth-context'
import {
  Activity,
  Copy,
  Download,
  Edit3,
  Play,
  Plus,
  Trash2,
  Upload,
  X
} from 'lucide-react'

interface OsProfileFull {
  id: string
  slug: string
  displayName: string
  transportType: string
  priority: number
  enabled: boolean
  isSystem: boolean
  detectionRules: unknown
  environmentVars: Record<string, string>
  inventorySpec: unknown
  updateCheckSpec: unknown
  patchExecSpec: unknown
  vulnerabilitySpec: unknown
  privilegeSpec: unknown
  lockSpec: unknown
}

interface WorkerHealthStatus {
  reachable: boolean
  workerHealth: {
    status: string
    contractVersion: string
    workerVersion: string
    implementation: string
    supportedParsers: string[]
  } | null
  errorMessage: string | null
}

interface DryRunResponse {
  matchedDetectionRules: boolean
  osFacts: Record<string, string>
  samplePackages: Array<{
    name: string
    version: string
    arch: string
    sourceName: string
    purl: string
  }>
  totalParsed: number
  skippedLines: number
  sampleSkippedLines: string[]
  durationMs: number
  credentialUsername: string
  usedFallback: boolean
}

interface DeviceOption {
  id: string
  displayName: string
}

export const OsProfilesSettingsTab: React.FC = () => {
  const { apiClient, user } = useAuth()
  const isAdmin = Boolean(user?.roles.includes('gnm-admin'))

  const [profiles, setProfiles] = useState<OsProfileFull[]>([])
  const [devices, setDevices] = useState<DeviceOption[]>([])
  const [workerHealth, setWorkerHealth] = useState<WorkerHealthStatus | null>(null)
  const [loading, setLoading] = useState(true)
  const [errorMsg, setErrorMsg] = useState<string | null>(null)

  const [editingProfile, setEditingProfile] = useState<OsProfileFull | null>(null)
  const [jsonEditorText, setJsonEditorText] = useState('')
  const [dryRunDeviceId, setDryRunDeviceId] = useState('')
  const [dryRunning, setDryRunning] = useState(false)
  const [dryRunResult, setDryRunResult] = useState<DryRunResponse | null>(null)

  const [importModalOpen, setImportModalOpen] = useState(false)
  const [importJsonText, setImportJsonText] = useState('')

  const loadData = useCallback(async () => {
    setLoading(true)
    setErrorMsg(null)
    try {
      const [profilesData, healthData, devicesData] = await Promise.all([
        apiClient<OsProfileFull[]>('/api/os-profiles'),
        apiClient<WorkerHealthStatus>('/api/os-profiles/worker-health').catch(() => null),
        apiClient<DeviceOption[]>('/api/devices').catch(() => [])
      ])
      setProfiles(profilesData)
      setWorkerHealth(healthData)
      setDevices(devicesData)
      if (devicesData.length > 0 && !dryRunDeviceId) {
        setDryRunDeviceId(devicesData[0].id)
      }
    } catch (err) {
      setErrorMsg(err instanceof Error ? err.message : 'Failed to load OS profiles')
    } finally {
      setLoading(false)
    }
  }, [apiClient, dryRunDeviceId])

  useEffect(() => {
    void loadData()
  }, [loadData])

  const handleClone = async (profile: OsProfileFull) => {
    setErrorMsg(null)
    try {
      const cloned = await apiClient<OsProfileFull>(`/api/os-profiles/${profile.id}/clone`, {
        method: 'POST',
        body: JSON.stringify({
          newSlug: `${profile.slug}-custom-${Math.floor(Math.random() * 900 + 100)}`,
          newDisplayName: `${profile.displayName} (Custom)`
        })
      })
      setProfiles((prev) => [...prev, cloned])
    } catch (err) {
      setErrorMsg(err instanceof Error ? err.message : 'Failed to clone profile')
    }
  }

  const handleExport = async (profile: OsProfileFull) => {
    try {
      const data = await apiClient<Record<string, unknown>>(
        `/api/os-profiles/${profile.id}/export`
      )
      const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' })
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = `os-profile-${profile.slug}.json`
      a.click()
      URL.revokeObjectURL(url)
    } catch (err) {
      setErrorMsg(err instanceof Error ? err.message : 'Failed to export profile')
    }
  }

  const handleDelete = async (profile: OsProfileFull) => {
    setErrorMsg(null)
    try {
      await apiClient(`/api/os-profiles/${profile.id}`, { method: 'DELETE' })
      setProfiles((prev) => prev.filter((p) => p.id !== profile.id))
    } catch (err) {
      setErrorMsg(err instanceof Error ? err.message : 'Failed to delete profile')
    }
  }

  const openEditor = (profile: OsProfileFull) => {
    setEditingProfile(profile)
    setDryRunResult(null)
    const editablePayload = {
      slug: profile.slug,
      displayName: profile.displayName,
      transportType: profile.transportType,
      priority: profile.priority,
      enabled: profile.enabled,
      detectionRules: profile.detectionRules,
      environmentVars: profile.environmentVars,
      inventorySpec: profile.inventorySpec,
      updateCheckSpec: profile.updateCheckSpec,
      patchExecSpec: profile.patchExecSpec,
      vulnerabilitySpec: profile.vulnerabilitySpec,
      privilegeSpec: profile.privilegeSpec,
      lockSpec: profile.lockSpec
    }
    setJsonEditorText(JSON.stringify(editablePayload, null, 2))
  }

  const handleSaveEditedProfile = async () => {
    if (!editingProfile) return
    setErrorMsg(null)
    try {
      const parsed = JSON.parse(jsonEditorText)
      const updated = await apiClient<OsProfileFull>(`/api/os-profiles/${editingProfile.id}`, {
        method: 'PUT',
        body: JSON.stringify(parsed)
      })
      setProfiles((prev) => prev.map((p) => (p.id === updated.id ? updated : p)))
      setEditingProfile(null)
    } catch (err) {
      setErrorMsg(err instanceof Error ? err.message : 'Failed to save OS profile')
    }
  }

  const handleDryRun = async () => {
    if (!dryRunDeviceId) return
    setDryRunning(true)
    setErrorMsg(null)
    setDryRunResult(null)
    try {
      const parsed = JSON.parse(jsonEditorText)
      const res = await apiClient<DryRunResponse>('/api/os-profiles/test', {
        method: 'POST',
        body: JSON.stringify({
          deviceId: dryRunDeviceId,
          profile: parsed
        })
      })
      setDryRunResult(res)
    } catch (err) {
      setErrorMsg(err instanceof Error ? err.message : 'Dry-run test failed')
    } finally {
      setDryRunning(false)
    }
  }

  const handleImportProfile = async () => {
    setErrorMsg(null)
    try {
      const parsed = JSON.parse(importJsonText)
      const created = await apiClient<OsProfileFull>('/api/os-profiles/import', {
        method: 'POST',
        body: JSON.stringify(parsed)
      })
      setProfiles((prev) => [...prev, created])
      setImportModalOpen(false)
      setImportJsonText('')
    } catch (err) {
      setErrorMsg(err instanceof Error ? err.message : 'Failed to import profile JSON')
    }
  }

  return (
    <div className="space-y-6">
      {/* Worker Health & Action Header */}
      <div className="bg-bg-surface border border-border-subtle rounded-2xl p-6 flex flex-wrap items-center justify-between gap-4 shadow-lg">
        <div>
          <div className="flex items-center gap-3">
            <h2 className="text-lg font-bold text-text-primary">
              Declarative OS & Package Manager Profiles ({profiles.length})
            </h2>
            {workerHealth && (
              <span
                className={`px-2.5 py-0.5 rounded-full text-[11px] font-bold inline-flex items-center gap-1.5 ${
                  workerHealth.reachable
                    ? 'bg-accent-success/15 text-accent-success border border-accent-success/30'
                    : 'bg-accent-warning/15 text-accent-warning border border-accent-warning/30'
                }`}
              >
                <Activity className="w-3 h-3" />
                {workerHealth.reachable
                  ? `Worker Online (${workerHealth.workerHealth?.implementation || 'go'} ${workerHealth.workerHealth?.contractVersion || 'v1'})`
                  : 'Worker Unreachable'}
              </span>
            )}
          </div>
          <p className="text-xs text-text-secondary mt-1">
            Zero-hardcode OS detection rules, generic output parsers, privilege wrappers, and dry-run testing
          </p>
        </div>

        {isAdmin && (
          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={() => setImportModalOpen(true)}
              className="px-3.5 py-1.5 rounded-lg border border-border-subtle bg-bg-surface-raised text-xs font-semibold text-text-primary hover:border-accent-primary flex items-center gap-1.5 cursor-pointer"
            >
              <Upload className="w-3.5 h-3.5" />
              Import Profile JSON
            </button>
          </div>
        )}
      </div>

      {errorMsg && (
        <div className="p-4 rounded-xl bg-accent-danger/10 border border-accent-danger/30 text-accent-danger text-xs font-semibold">
          {errorMsg}
        </div>
      )}

      {loading ? (
        <div className="py-12 text-center text-xs text-text-muted">Loading OS profiles...</div>
      ) : (
        <div className="bg-bg-surface border border-border-subtle rounded-2xl p-6 shadow-lg overflow-x-auto">
          <table className="w-full text-left border-collapse text-xs">
            <thead>
              <tr className="border-b border-border-subtle text-text-secondary uppercase tracking-wider text-[10px]">
                <th className="py-2.5 px-3">Priority</th>
                <th className="py-2.5 px-3">Slug</th>
                <th className="py-2.5 px-3">Display Name</th>
                <th className="py-2.5 px-3">Transport</th>
                <th className="py-2.5 px-3">Type</th>
                <th className="py-2.5 px-3 text-right">Actions</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border-subtle/50">
              {profiles.map((p) => (
                <tr key={p.id} className="hover:bg-bg-surface-raised/60">
                  <td className="py-2.5 px-3 font-mono text-text-secondary">{p.priority}</td>
                  <td className="py-2.5 px-3 font-mono font-bold text-text-primary">{p.slug}</td>
                  <td className="py-2.5 px-3 text-text-primary font-semibold">{p.displayName}</td>
                  <td className="py-2.5 px-3 font-mono text-text-secondary">{p.transportType}</td>
                  <td className="py-2.5 px-3">
                    {p.isSystem ? (
                      <span className="px-2 py-0.5 rounded text-[10px] font-bold bg-accent-primary/15 text-accent-primary border border-accent-primary/30">
                        Built-In (Read-Only)
                      </span>
                    ) : (
                      <span className="px-2 py-0.5 rounded text-[10px] font-bold bg-accent-success/15 text-accent-success border border-accent-success/30">
                        Custom
                      </span>
                    )}
                  </td>
                  <td className="py-2.5 px-3 text-right">
                    <div className="inline-flex items-center gap-1.5">
                      <button
                        type="button"
                        onClick={() => handleExport(p)}
                        className="px-2.5 py-1 rounded-lg border border-border-subtle bg-bg-surface-raised text-[11px] font-semibold text-text-primary hover:border-accent-primary inline-flex items-center gap-1 cursor-pointer"
                      >
                        <Download className="w-3 h-3" />
                        Export JSON
                      </button>

                      <button
                        type="button"
                        onClick={() => openEditor(p)}
                        className="px-2.5 py-1 rounded-lg border border-border-subtle bg-bg-surface-raised text-[11px] font-semibold text-text-primary hover:border-accent-primary inline-flex items-center gap-1 cursor-pointer"
                      >
                        <Edit3 className="w-3 h-3" />
                        {p.isSystem || !isAdmin ? 'Inspect / Dry-Run' : 'Edit / Dry-Run'}
                      </button>

                      {isAdmin && (
                        <>
                          <button
                            type="button"
                            onClick={() => handleClone(p)}
                            className="px-2.5 py-1 rounded-lg border border-border-subtle bg-bg-surface-raised text-[11px] font-semibold text-text-primary hover:border-accent-primary inline-flex items-center gap-1 cursor-pointer"
                          >
                            <Copy className="w-3 h-3" />
                            Clone
                          </button>

                          {!p.isSystem && (
                            <button
                              type="button"
                              onClick={() => handleDelete(p)}
                              className="px-2 py-1 rounded-lg border border-accent-danger/30 bg-accent-danger/10 text-[11px] font-semibold text-accent-danger hover:bg-accent-danger/20 inline-flex items-center gap-1 cursor-pointer"
                            >
                              <Trash2 className="w-3 h-3" />
                            </button>
                          )}
                        </>
                      )}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {/* Profile Editor & Dry-Run Modal */}
      {editingProfile && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 backdrop-blur-sm p-4">
          <div className="bg-bg-surface rounded-2xl w-full max-w-4xl shadow-2xl border border-border-subtle flex flex-col max-h-[88vh]">
            <div className="p-5 border-b border-border-subtle flex items-center justify-between">
              <div>
                <h3 className="font-bold text-base">
                  OS Profile Definition: {editingProfile.displayName} ({editingProfile.slug})
                </h3>
                <p className="text-xs text-text-secondary mt-0.5">
                  {editingProfile.isSystem
                    ? 'Built-in profile is read-only. Clone it to save modifications, or run a Dry-Run test below.'
                    : 'Edit declarative JSON specification and test against a live host.'}
                </p>
              </div>
              <button
                type="button"
                onClick={() => setEditingProfile(null)}
                className="text-text-muted hover:text-text-primary p-1 cursor-pointer"
              >
                <X className="w-5 h-5" />
              </button>
            </div>

            <div className="p-5 space-y-4 overflow-y-auto flex-1">
              <textarea
                value={jsonEditorText}
                onChange={(e) => setJsonEditorText(e.target.value)}
                readOnly={editingProfile.isSystem || !isAdmin}
                rows={16}
                className="w-full p-4 rounded-xl bg-bg-base border border-border-subtle font-mono text-xs text-text-primary"
              />

              {isAdmin && (
                <div className="p-4 rounded-xl border border-border-subtle bg-bg-surface-raised space-y-3">
                  <div className="flex flex-wrap items-center justify-between gap-3">
                    <div>
                      <h4 className="text-xs font-bold text-text-primary">
                        Dry-Run Test Against Target Device (Read-Only)
                      </h4>
                      <p className="text-[11px] text-text-secondary">
                        Executes OS probe and inventory parser preview (up to 50 packages) without saving to database
                      </p>
                    </div>
                    <div className="flex items-center gap-2">
                      <select
                        value={dryRunDeviceId}
                        onChange={(e) => setDryRunDeviceId(e.target.value)}
                        className="px-2.5 py-1.5 rounded-lg bg-bg-base border border-border-subtle text-xs text-text-primary"
                      >
                        {devices.map((d) => (
                          <option key={d.id} value={d.id}>
                            {d.displayName}
                          </option>
                        ))}
                      </select>
                      <button
                        type="button"
                        onClick={handleDryRun}
                        disabled={dryRunning || !dryRunDeviceId}
                        className="px-3.5 py-1.5 rounded-lg bg-accent-primary text-white text-xs font-semibold hover:bg-accent-primary/90 flex items-center gap-1.5 cursor-pointer disabled:opacity-50"
                      >
                        <Play className="w-3.5 h-3.5" />
                        {dryRunning ? 'Running Dry-Run...' : 'Run Dry-Run'}
                      </button>
                    </div>
                  </div>

                  {dryRunResult && (
                    <div className="p-3.5 rounded-xl bg-bg-base border border-border-subtle space-y-2 text-xs">
                      <div className="flex flex-wrap items-center gap-3 font-semibold">
                        <span>
                          Detection Match:{' '}
                          <strong
                            className={
                              dryRunResult.matchedDetectionRules
                                ? 'text-accent-success'
                                : 'text-accent-warning'
                            }
                          >
                            {dryRunResult.matchedDetectionRules ? 'MATCHED' : 'NOT MATCHED'}
                          </strong>
                        </span>
                        <span>Total Parsed: {dryRunResult.totalParsed}</span>
                        <span>Skipped Lines: {dryRunResult.skippedLines}</span>
                        <span>Duration: {dryRunResult.durationMs} ms</span>
                      </div>
                      {dryRunResult.samplePackages.length > 0 && (
                        <div className="font-mono text-[11px] text-text-secondary max-h-36 overflow-y-auto space-y-0.5 pt-1">
                          {dryRunResult.samplePackages.slice(0, 10).map((pkg) => (
                            <div key={`${pkg.name}-${pkg.version}`}>
                              {pkg.name} @ {pkg.version} ({pkg.purl})
                            </div>
                          ))}
                        </div>
                      )}
                    </div>
                  )}
                </div>
              )}
            </div>

            <div className="p-4 border-t border-border-subtle flex justify-end gap-2">
              <button
                type="button"
                onClick={() => setEditingProfile(null)}
                className="px-4 py-1.5 rounded-lg border border-border-subtle bg-bg-surface-raised text-xs font-semibold text-text-primary cursor-pointer"
              >
                Close
              </button>
              {isAdmin && !editingProfile.isSystem && (
                <button
                  type="button"
                  onClick={handleSaveEditedProfile}
                  className="px-4 py-1.5 rounded-lg bg-accent-primary text-white text-xs font-semibold cursor-pointer"
                >
                  Save Profile Changes
                </button>
              )}
            </div>
          </div>
        </div>
      )}

      {/* Import Profile JSON Modal */}
      {importModalOpen && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 backdrop-blur-sm p-4">
          <div className="bg-bg-surface rounded-2xl w-full max-w-2xl shadow-2xl border border-border-subtle flex flex-col">
            <div className="p-5 border-b border-border-subtle flex items-center justify-between">
              <h3 className="font-bold text-base">Import OS Profile JSON (schemaVersion: 1)</h3>
              <button
                type="button"
                onClick={() => setImportModalOpen(false)}
                className="text-text-muted hover:text-text-primary p-1 cursor-pointer"
              >
                <X className="w-5 h-5" />
              </button>
            </div>
            <div className="p-5 space-y-4">
              <textarea
                value={importJsonText}
                onChange={(e) => setImportJsonText(e.target.value)}
                rows={12}
                placeholder='{"schemaVersion": 1, "slug": "my-custom-os", ...}'
                className="w-full p-4 rounded-xl bg-bg-base border border-border-subtle font-mono text-xs text-text-primary"
              />
              <div className="flex justify-end gap-2">
                <button
                  type="button"
                  onClick={() => setImportModalOpen(false)}
                  className="px-4 py-1.5 rounded-lg border border-border-subtle bg-bg-surface-raised text-xs font-semibold text-text-primary cursor-pointer"
                >
                  Cancel
                </button>
                <button
                  type="button"
                  onClick={handleImportProfile}
                  className="px-4 py-1.5 rounded-lg bg-accent-primary text-white text-xs font-semibold flex items-center gap-1.5 cursor-pointer"
                >
                  <Plus className="w-3.5 h-3.5" />
                  Import Profile
                </button>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}

