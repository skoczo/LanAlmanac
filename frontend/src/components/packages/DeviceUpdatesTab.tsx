import React, { useCallback, useEffect, useState } from 'react'
import { useAuth } from '../../lib/auth/auth-context'
import {
  DeviceAvailableUpdateItem,
  PatchJobItem,
  SudoersSnippet
} from '../../lib/api/packages'
import { DeviceScheduleCard } from './DeviceScheduleCard'
import { PatchJobLogModal } from './PatchJobLogModal'
import {
  ArrowUpCircle,
  Check,
  Copy,
  FileTerminal,
  History,
  Play,
  RefreshCcw,
  Shield,
  ShieldAlert,
  ShieldCheck,
  Terminal,
  X
} from 'lucide-react'

interface Props {
  deviceId: string
  rebootRequired?: boolean
}

export const DeviceUpdatesTab: React.FC<Props> = ({ deviceId, rebootRequired }) => {
  const { apiClient } = useAuth()
  const [updates, setUpdates] = useState<DeviceAvailableUpdateItem[]>([])
  const [jobs, setJobs] = useState<PatchJobItem[]>([])
  const [selectedPkgs, setSelectedPkgs] = useState<string[]>([])
  const [loading, setLoading] = useState(true)
  const [checking, setChecking] = useState(false)
  const [startingJob, setStartingJob] = useState(false)
  const [activeJobId, setActiveJobId] = useState<string | null>(null)
  const [errorMsg, setErrorMsg] = useState<string | null>(null)
  const [sudoersModal, setSudoersModal] = useState<SudoersSnippet | null>(null)
  const [copied, setCopied] = useState(false)

  const loadData = useCallback(async () => {
    setLoading(true)
    setErrorMsg(null)
    try {
      const [updatesData, jobsData] = await Promise.all([
        apiClient<DeviceAvailableUpdateItem[]>(`/api/devices/${deviceId}/updates`),
        apiClient<PatchJobItem[]>(`/api/devices/${deviceId}/patch-jobs`)
      ])
      setUpdates(updatesData)
      setJobs(jobsData)
    } catch (err) {
      setErrorMsg(err instanceof Error ? err.message : 'Failed to load available updates')
    } finally {
      setLoading(false)
    }
  }, [apiClient, deviceId])

  useEffect(() => {
    loadData()
  }, [loadData])

  const handleCheckUpdates = async () => {
    setChecking(true)
    setErrorMsg(null)
    try {
      const data = await apiClient<DeviceAvailableUpdateItem[]>(
        `/api/devices/${deviceId}/updates/check`,
        { method: 'POST' }
      )
      setUpdates(data)
      setSelectedPkgs([])
    } catch (err) {
      setErrorMsg(err instanceof Error ? err.message : 'Update check failed')
    } finally {
      setChecking(false)
    }
  }

  const handleStartPatchJob = async (
    mode: 'FULL_UPGRADE' | 'SECURITY_ONLY' | 'SELECTED_PACKAGES'
  ) => {
    setStartingJob(true)
    setErrorMsg(null)
    try {
      const created = await apiClient<PatchJobItem>(`/api/devices/${deviceId}/patch-jobs`, {
        method: 'POST',
        body: JSON.stringify({
          mode,
          packages: mode === 'SELECTED_PACKAGES' ? selectedPkgs : []
        })
      })
      setJobs((prev) => [created, ...prev.filter((j) => j.id !== created.id)])
      setActiveJobId(created.id)
    } catch (err) {
      setErrorMsg(err instanceof Error ? err.message : 'Failed to launch patch job')
    } finally {
      setStartingJob(false)
    }
  }

  const handleOpenSudoers = async () => {
    setErrorMsg(null)
    try {
      const data = await apiClient<SudoersSnippet>(`/api/devices/${deviceId}/sudoers`)
      setSudoersModal(data)
      setCopied(false)
    } catch (err) {
      setErrorMsg(err instanceof Error ? err.message : 'Failed to generate sudoers snippet')
    }
  }

  const toggleSelectPackage = (pkgName: string) => {
    setSelectedPkgs((prev) =>
      prev.includes(pkgName) ? prev.filter((p) => p !== pkgName) : [...prev, pkgName]
    )
  }

  const hostRebootRequired =
    rebootRequired || (jobs.length > 0 && jobs[0].rebootRequired)

  return (
    <div className="space-y-6">
      {hostRebootRequired && (
        <div className="p-4 rounded-xl bg-accent-warning/15 border border-accent-warning/40 text-accent-warning text-xs font-semibold flex items-center gap-2.5">
          <ShieldAlert className="w-4 h-4 shrink-0" />
          <span>System reboot is required on this host to complete previously installed updates.</span>
        </div>
      )}

      <div className="bg-bg-surface border border-border-subtle rounded-2xl p-6 space-y-5 shadow-lg">
        <div className="flex flex-wrap items-center justify-between gap-4 pb-4 border-b border-border-subtle">
          <div className="flex items-center gap-2.5">
            <ArrowUpCircle className="w-5 h-5 text-accent-primary" />
            <div>
              <h3 className="font-bold text-base">Available Package Updates ({updates.length})</h3>
              <p className="text-xs text-text-secondary">
                Non-invasive dry-run update detection and agentless SSH patch execution
              </p>
            </div>
          </div>

          <div className="flex flex-wrap items-center gap-2">
            <button
              type="button"
              onClick={handleOpenSudoers}
              className="px-3 py-1.5 rounded-lg border border-border-subtle bg-bg-surface-raised text-xs font-semibold text-text-primary hover:border-accent-primary flex items-center gap-1.5 cursor-pointer"
            >
              <FileTerminal className="w-3.5 h-3.5" />
              Configure sudoers.d
            </button>

            <button
              type="button"
              onClick={handleCheckUpdates}
              disabled={checking || startingJob}
              className="px-3 py-1.5 rounded-lg border border-border-subtle bg-bg-surface-raised text-xs font-semibold text-text-primary hover:border-accent-primary flex items-center gap-1.5 cursor-pointer disabled:opacity-50"
            >
              <RefreshCcw className={`w-3.5 h-3.5 ${checking ? 'animate-spin' : ''}`} />
              {checking ? 'Checking...' : 'Check Updates'}
            </button>

            {selectedPkgs.length > 0 && (
              <button
                type="button"
                onClick={() => handleStartPatchJob('SELECTED_PACKAGES')}
                disabled={startingJob}
                className="px-3.5 py-1.5 rounded-lg bg-accent-primary text-white text-xs font-semibold hover:bg-accent-primary/90 flex items-center gap-1.5 cursor-pointer disabled:opacity-50"
              >
                <Play className="w-3.5 h-3.5" />
                Upgrade Selected ({selectedPkgs.length})
              </button>
            )}

            <button
              type="button"
              onClick={() => handleStartPatchJob('SECURITY_ONLY')}
              disabled={startingJob}
              className="px-3.5 py-1.5 rounded-lg bg-accent-warning text-black text-xs font-bold hover:bg-accent-warning/90 flex items-center gap-1.5 cursor-pointer disabled:opacity-50"
            >
              <Shield className="w-3.5 h-3.5" />
              Security Only
            </button>

            <button
              type="button"
              onClick={() => handleStartPatchJob('FULL_UPGRADE')}
              disabled={startingJob}
              className="px-3.5 py-1.5 rounded-lg bg-accent-success text-black text-xs font-bold hover:bg-accent-success/90 flex items-center gap-1.5 cursor-pointer disabled:opacity-50"
            >
              <ArrowUpCircle className="w-3.5 h-3.5" />
              Upgrade All
            </button>
          </div>
        </div>

        {errorMsg && (
          <div className="p-4 rounded-xl bg-accent-danger/10 border border-accent-danger/30 text-accent-danger text-xs font-semibold">
            {errorMsg}
          </div>
        )}

        {loading ? (
          <div className="py-12 text-center text-xs text-text-muted">Loading available updates...</div>
        ) : updates.length === 0 ? (
          <div className="py-12 text-center border border-dashed border-border-subtle rounded-xl text-xs text-text-secondary flex flex-col items-center gap-2">
            <ShieldCheck className="w-8 h-8 text-accent-success" />
            <span>System is up to date (or click Check Updates to query remote repositories).</span>
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-left border-collapse text-xs">
              <thead>
                <tr className="border-b border-border-subtle text-text-secondary uppercase tracking-wider text-[10px]">
                  <th className="py-2.5 px-3 w-8">
                    <input
                      type="checkbox"
                      aria-label="Select all updates"
                      checked={selectedPkgs.length === updates.length && updates.length > 0}
                      onChange={(e) =>
                        setSelectedPkgs(e.target.checked ? updates.map((u) => u.packageName) : [])
                      }
                      className="accent-accent-primary"
                    />
                  </th>
                  <th className="py-2.5 px-3">Package</th>
                  <th className="py-2.5 px-3">Current Version</th>
                  <th className="py-2.5 px-3">Candidate Version</th>
                  <th className="py-2.5 px-3">Arch</th>
                  <th className="py-2.5 px-3">Security / CVE Correlation</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border-subtle/50">
                {updates.map((u) => (
                  <tr key={u.id} className="hover:bg-bg-surface-raised/60">
                    <td className="py-2.5 px-3">
                      <input
                        type="checkbox"
                        checked={selectedPkgs.includes(u.packageName)}
                        onChange={() => toggleSelectPackage(u.packageName)}
                        className="accent-accent-primary"
                      />
                    </td>
                    <td className="py-2.5 px-3 font-semibold font-mono text-text-primary">
                      {u.packageName}
                    </td>
                    <td className="py-2.5 px-3 font-mono text-text-secondary">
                      {u.currentVersion || '-'}
                    </td>
                    <td className="py-2.5 px-3 font-mono text-accent-success font-semibold">
                      {u.candidateVersion}
                    </td>
                    <td className="py-2.5 px-3 font-mono text-text-muted">{u.arch || '-'}</td>
                    <td className="py-2.5 px-3">
                      <div className="flex flex-wrap gap-1.5">
                        {u.isSecurityUpdate && (
                          <span className="px-2 py-0.5 rounded text-[10px] font-bold bg-accent-warning/15 text-accent-warning border border-accent-warning/30">
                            Security Repo
                          </span>
                        )}
                        {u.fixedAdvisories.map((adv) => (
                          <span
                            key={adv}
                            className="px-2 py-0.5 rounded text-[10px] font-bold font-mono bg-accent-danger/15 text-accent-danger border border-accent-danger/30"
                          >
                            Fixes {adv}
                          </span>
                        ))}
                        {!u.isSecurityUpdate && u.fixedAdvisories.length === 0 && (
                          <span className="text-[11px] text-text-muted">Standard</span>
                        )}
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {/* Patch Job History Table */}
      <div className="bg-bg-surface border border-border-subtle rounded-2xl p-6 space-y-4 shadow-lg">
        <div className="flex items-center justify-between pb-3 border-b border-border-subtle">
          <div className="flex items-center gap-2.5">
            <History className="w-5 h-5 text-accent-primary" />
            <div>
              <h3 className="font-bold text-base">Patch Execution History ({jobs.length})</h3>
              <p className="text-xs text-text-secondary">
                Audit trail of manual and scheduled update executions with live and persisted console output
              </p>
            </div>
          </div>
        </div>

        {jobs.length === 0 ? (
          <div className="py-8 text-center text-xs text-text-muted">
            No patch executions recorded for this host yet.
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-left border-collapse text-xs">
              <thead>
                <tr className="border-b border-border-subtle text-text-secondary uppercase tracking-wider text-[10px]">
                  <th className="py-2.5 px-3">Started At</th>
                  <th className="py-2.5 px-3">Mode</th>
                  <th className="py-2.5 px-3">Status</th>
                  <th className="py-2.5 px-3">Credential</th>
                  <th className="py-2.5 px-3">Triggered By</th>
                  <th className="py-2.5 px-3 text-right">Console</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border-subtle/50">
                {jobs.map((job) => (
                  <tr key={job.id} className="hover:bg-bg-surface-raised/60">
                    <td className="py-2.5 px-3 font-mono text-text-secondary">
                      {new Date(job.startedAt).toLocaleString()}
                    </td>
                    <td className="py-2.5 px-3 font-mono font-semibold text-text-primary">
                      {job.mode}
                      {job.requestedPackages && job.requestedPackages.length > 0 && (
                        <span className="text-text-muted ml-1">
                          ({job.requestedPackages.length} pkgs)
                        </span>
                      )}
                    </td>
                    <td className="py-2.5 px-3">
                      <span
                        className={`px-2 py-0.5 rounded text-[10px] font-bold uppercase ${
                          job.status === 'COMPLETED'
                            ? 'bg-accent-success/15 text-accent-success border border-accent-success/30'
                            : job.status === 'RUNNING' || job.status === 'PENDING'
                            ? 'bg-accent-primary/15 text-accent-primary border border-accent-primary/30'
                            : job.status === 'CONNECTION_LOST'
                            ? 'bg-accent-warning/15 text-accent-warning border border-accent-warning/30'
                            : 'bg-accent-danger/15 text-accent-danger border border-accent-danger/30'
                        }`}
                      >
                        {job.status}
                      </span>
                      {job.rebootRequired && (
                        <span className="ml-1.5 px-1.5 py-0.5 rounded text-[10px] font-bold bg-accent-warning/15 text-accent-warning">
                          Reboot
                        </span>
                      )}
                    </td>
                    <td className="py-2.5 px-3 font-mono text-text-secondary">
                      {job.credentialUsername || '-'}
                      {job.usedFallback && (
                        <span className="ml-1 text-[10px] text-accent-warning">(fallback)</span>
                      )}
                    </td>
                    <td className="py-2.5 px-3 text-text-secondary">
                      {job.triggeredBy || 'manual'}
                    </td>
                    <td className="py-2.5 px-3 text-right">
                      <button
                        type="button"
                        onClick={() => setActiveJobId(job.id)}
                        className="px-2.5 py-1 rounded-lg border border-border-subtle bg-bg-surface-raised text-[11px] font-semibold text-text-primary hover:border-accent-primary inline-flex items-center gap-1 cursor-pointer"
                      >
                        <Terminal className="w-3 h-3" />
                        View Logs
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {/* Per-Device Automation Schedule (ADR-5) */}
      <DeviceScheduleCard deviceId={deviceId} />

      {/* Live / Historical Log Viewer Modal */}
      {activeJobId && (
        <PatchJobLogModal
          jobId={activeJobId}
          onClose={() => {
            setActiveJobId(null)
            loadData()
          }}
          onJobUpdated={(updated) => {
            setJobs((prev) => prev.map((j) => (j.id === updated.id ? updated : j)))
          }}
        />
      )}

      {/* Sudoers Modal */}
      {sudoersModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm p-4">
          <div className="bg-bg-surface rounded-2xl w-full max-w-2xl shadow-2xl border border-border-subtle flex flex-col">
            <div className="p-5 border-b border-border-subtle flex items-center justify-between">
              <div>
                <h3 className="font-bold text-base">
                  Least-Privilege Sudoers Configuration ({sudoersModal.osProfileName})
                </h3>
                <p className="text-xs text-text-secondary mt-0.5">
                  Install on remote host for user <code className="font-mono">{sudoersModal.username}</code>
                </p>
              </div>
              <button
                type="button"
                onClick={() => setSudoersModal(null)}
                className="text-text-muted hover:text-text-primary p-1 cursor-pointer"
              >
                <X className="w-5 h-5" />
              </button>
            </div>
            <div className="p-5 space-y-4">
              <pre className="p-4 rounded-xl bg-bg-base border border-border-subtle font-mono text-xs text-text-primary overflow-x-auto whitespace-pre-wrap">
                {sudoersModal.snippet}
              </pre>
              <div className="flex justify-end gap-2">
                <button
                  type="button"
                  onClick={() => {
                    navigator.clipboard.writeText(sudoersModal.snippet)
                    setCopied(true)
                  }}
                  className="px-3.5 py-1.5 rounded-lg bg-accent-primary text-white text-xs font-semibold flex items-center gap-1.5 cursor-pointer"
                >
                  {copied ? <Check className="w-3.5 h-3.5" /> : <Copy className="w-3.5 h-3.5" />}
                  {copied ? 'Copied!' : 'Copy Snippet'}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
