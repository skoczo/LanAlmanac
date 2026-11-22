import React, { useCallback, useEffect, useState } from 'react'
import { useAuth } from '../../lib/auth/auth-context'
import {
  DeviceOsProfileStatus,
  DevicePackageItem,
  DevicePackageScanResult,
  OsProfileSummary,
  PatchJobItem,
  SudoersSnippet
} from '../../lib/api/packages'
import {
  AlertTriangle,
  ArrowUpCircle,
  Check,
  CheckCircle2,
  Clock,
  Copy,
  Cpu,
  Download,
  FileCode2,
  KeyRound,
  Lock,
  Package,
  RefreshCcw,
  Search,
  ShieldAlert,
  ShieldCheck,
  Terminal,
  Unlock,
  X,
  XCircle
} from 'lucide-react'
import { PatchJobLogModal } from './PatchJobLogModal'

interface Props {
  deviceId: string
  rebootRequired?: boolean
}

type FilterView = 'ALL' | 'UPDATES' | 'VULNERABLE'

export const DevicePackagesTab: React.FC<Props> = ({ deviceId, rebootRequired }) => {
  const { apiClient } = useAuth()
  const [packages, setPackages] = useState<DevicePackageItem[]>([])
  const [patchJobs, setPatchJobs] = useState<PatchJobItem[]>([])
  const [profileStatus, setProfileStatus] = useState<DeviceOsProfileStatus | null>(null)
  const [allProfiles, setAllProfiles] = useState<OsProfileSummary[]>([])
  const [query, setQuery] = useState('')
  const [filterView, setFilterView] = useState<FilterView>('ALL')
  const [loading, setLoading] = useState(true)
  const [scanning, setScanning] = useState(false)
  const [detecting, setDetecting] = useState(false)
  const [checkingUpdates, setCheckingUpdates] = useState(false)
  const [startingPatchJob, setStartingPatchJob] = useState(false)
  const [activeJobId, setActiveJobId] = useState<string | null>(null)
  const [sudoersModal, setSudoersModal] = useState<SudoersSnippet | null>(null)
  const [copied, setCopied] = useState(false)
  const [errorMsg, setErrorMsg] = useState<string | null>(null)
  const [lastScan, setLastScan] = useState<DevicePackageScanResult | null>(null)
  const [selectedPkgs, setSelectedPkgs] = useState<string[]>([])

  const getJobStatusBadge = (status: string) => {
    const norm = (status || '').toUpperCase()
    if (norm === 'SUCCEEDED' || norm === 'SUCCESS' || norm === 'COMPLETED') {
      return {
        bg: 'bg-accent-success/15 text-accent-success border-accent-success/30',
        icon: <CheckCircle2 className="w-3.5 h-3.5 text-accent-success" />,
        label: 'SUCCEEDED'
      }
    }
    if (norm === 'RUNNING' || norm === 'PENDING') {
      return {
        bg: 'bg-accent-primary/15 text-accent-primary border-accent-primary/30 animate-pulse',
        icon: <RefreshCcw className="w-3.5 h-3.5 text-accent-primary animate-spin" />,
        label: norm === 'PENDING' ? 'PENDING' : 'RUNNING'
      }
    }
    if (norm === 'CANCELLED') {
      return {
        bg: 'bg-accent-warning/15 text-accent-warning border-accent-warning/30',
        icon: <AlertTriangle className="w-3.5 h-3.5 text-accent-warning" />,
        label: 'CANCELLED'
      }
    }
    return {
      bg: 'bg-accent-danger/15 text-accent-danger border-accent-danger/30',
      icon: <XCircle className="w-3.5 h-3.5 text-accent-danger" />,
      label: norm || 'FAILED'
    }
  }

  const activeRunningJob = patchJobs.find(
    (j) => j.status === 'RUNNING' || j.status === 'PENDING'
  )

  const loadData = useCallback(async () => {
    setLoading(true)
    setErrorMsg(null)
    try {
      const params = new URLSearchParams()
      if (query.trim()) params.set('q', query.trim())
      if (filterView === 'VULNERABLE') params.set('vulnerableOnly', 'true')
      if (filterView === 'UPDATES') params.set('updatesOnly', 'true')
      const qs = params.toString() ? `?${params.toString()}` : ''

      const [pkgs, status, profiles, jobs] = await Promise.all([
        apiClient<DevicePackageItem[]>(`/api/devices/${deviceId}/packages${qs}`),
        apiClient<DeviceOsProfileStatus>(`/api/devices/${deviceId}/os-profile`),
        apiClient<OsProfileSummary[]>('/api/os-profiles').catch(() => []),
        apiClient<PatchJobItem[]>(`/api/devices/${deviceId}/patch-jobs`).catch(() => [])
      ])
      setPackages(pkgs)
      setProfileStatus(status)
      setAllProfiles(profiles)
      setPatchJobs(jobs)
    } catch (err) {
      setErrorMsg(err instanceof Error ? err.message : 'Failed to load package inventory')
    } finally {
      setLoading(false)
    }
  }, [apiClient, deviceId, query, filterView])

  useEffect(() => {
    void loadData()
  }, [loadData])

  // Real-time polling when an operation/patch job is active on the node
  useEffect(() => {
    if (!activeRunningJob && !startingPatchJob && !scanning && !checkingUpdates) return

    const timer = setInterval(() => {
      void apiClient<PatchJobItem[]>(`/api/devices/${deviceId}/patch-jobs`)
        .then((jobs) => {
          setPatchJobs(jobs)
          const currentlyActive = jobs.some(
            (j) => j.status === 'RUNNING' || j.status === 'PENDING'
          )
          // If the job just completed, refresh the package inventory and updates list automatically
          if (activeRunningJob && !currentlyActive) {
            void loadData()
          }
        })
        .catch(() => { })
    }, 3000)

    return () => clearInterval(timer)
  }, [activeRunningJob, startingPatchJob, scanning, checkingUpdates, apiClient, deviceId, loadData])

  const handleScanInventory = async () => {
    setScanning(true)
    setErrorMsg(null)
    try {
      const scanRes = await apiClient<DevicePackageScanResult>(
        `/api/devices/${deviceId}/packages/scan`,
        { method: 'POST' }
      )
      setLastScan(scanRes)
      await loadData()
    } catch (err) {
      setErrorMsg(err instanceof Error ? err.message : 'Inventory scan failed')
    } finally {
      setScanning(false)
    }
  }

  const handleCheckUpdates = async () => {
    setCheckingUpdates(true)
    setErrorMsg(null)
    try {
      await apiClient(`/api/devices/${deviceId}/updates/check`, { method: 'POST' })
      await loadData()
    } catch (err) {
      setErrorMsg(err instanceof Error ? err.message : 'Update check failed')
    } finally {
      setCheckingUpdates(false)
    }
  }

  const handleStartPatchJob = async (
    mode: 'FULL_UPGRADE' | 'SECURITY_ONLY' | 'SELECTED_PACKAGES'
  ) => {
    setStartingPatchJob(true)
    setErrorMsg(null)
    try {
      const created = await apiClient<PatchJobItem>(`/api/devices/${deviceId}/patch-jobs`, {
        method: 'POST',
        body: JSON.stringify({
          mode,
          packages: mode === 'SELECTED_PACKAGES' ? selectedPkgs : []
        })
      })
      setPatchJobs((prev) => [created, ...prev.filter((j) => j.id !== created.id)])
      setActiveJobId(created.id)
      setSelectedPkgs([])
    } catch (err) {
      setErrorMsg(err instanceof Error ? err.message : 'Failed to launch patch job')
    } finally {
      setStartingPatchJob(false)
    }
  }

  const handleConfigureSudoers = async () => {
    setErrorMsg(null)
    try {
      const data = await apiClient<SudoersSnippet>(`/api/devices/${deviceId}/sudoers`)
      setSudoersModal(data)
      setCopied(false)
    } catch (err) {
      setErrorMsg(err instanceof Error ? err.message : 'Failed to generate sudoers configuration')
    }
  }

  const handleDetectOs = async () => {
    setDetecting(true)
    setErrorMsg(null)
    try {
      const status = await apiClient<DeviceOsProfileStatus>(
        `/api/devices/${deviceId}/os-profile/detect`,
        { method: 'POST' }
      )
      setProfileStatus(status)
    } catch (err) {
      setErrorMsg(err instanceof Error ? err.message : 'OS auto-detection failed')
    } finally {
      setDetecting(false)
    }
  }

  const handleAssignProfile = async (osProfileId: string, pinned: boolean) => {
    setErrorMsg(null)
    try {
      const status = await apiClient<DeviceOsProfileStatus>(
        `/api/devices/${deviceId}/os-profile`,
        {
          method: 'PUT',
          body: JSON.stringify({
            osProfileId: osProfileId || null,
            pinned
          })
        }
      )
      setProfileStatus(status)
    } catch (err) {
      setErrorMsg(err instanceof Error ? err.message : 'Failed to update OS profile')
    }
  }

  const handleDownloadSbom = async () => {
    try {
      const bom = await apiClient<Record<string, unknown>>(`/api/devices/${deviceId}/sbom`)
      const blob = new Blob([JSON.stringify(bom, null, 2)], { type: 'application/json' })
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = `sbom-${deviceId}.cdx.json`
      a.click()
      URL.revokeObjectURL(url)
    } catch (err) {
      setErrorMsg(err instanceof Error ? err.message : 'Failed to export CycloneDX SBOM')
    }
  }

  const toggleSelectPkg = (pkgName: string) => {
    setSelectedPkgs((prev) =>
      prev.includes(pkgName) ? prev.filter((p) => p !== pkgName) : [...prev, pkgName]
    )
  }

  const updatesCount = packages.filter((p) => !!p.candidateVersion).length
  const vulnerableCount = packages.filter((p) => p.openVulnerabilityCount > 0).length

  return (
    <div className="space-y-6 animate-fade-in">
      {/* Reboot Alert Banner */}
      {rebootRequired && (
        <div className="p-4 rounded-2xl bg-accent-danger/10 border border-accent-danger/30 flex items-center justify-between gap-4">
          <div className="flex items-center gap-3">
            <div className="p-2.5 rounded-xl bg-accent-danger/20 text-accent-danger shrink-0">
              <AlertTriangle className="w-5 h-5" />
            </div>
            <div>
              <div className="font-semibold text-text-primary text-sm">System Reboot Required</div>
              <p className="text-xs text-text-secondary mt-0.5">
                Recent package or kernel upgrades require a reboot before taking full effect.
              </p>
            </div>
          </div>
        </div>
      )}

      {/* OS Profile & Credentials Card */}
      <div className="bg-bg-surface border border-border-subtle rounded-2xl p-6 shadow-lg">
        <div className="flex flex-wrap items-center justify-between gap-4">
          <div className="flex items-center gap-3">
            <div className="p-2.5 rounded-xl bg-accent-primary/10 border border-accent-primary/20 text-accent-primary">
              <Cpu className="w-5 h-5" />
            </div>
            <div>
              <div className="flex items-center gap-2">
                <h3 className="font-bold text-sm text-text-primary">
                  OS Profile: {profileStatus?.osProfileName || 'Not Detected'}
                </h3>
                {profileStatus?.pinned && (
                  <span className="px-2 py-0.5 rounded-full text-[10px] font-bold uppercase bg-accent-warning/15 text-accent-warning border border-accent-warning/30 flex items-center gap-1">
                    <Lock className="w-3 h-3" /> Pinned
                  </span>
                )}
              </div>
              <div className="flex flex-wrap items-center gap-3 mt-1 text-xs text-text-secondary">
                <span className="flex items-center gap-1">
                  <KeyRound className="w-3.5 h-3.5" />
                  Effective SSH Credential:{' '}
                  {profileStatus?.effectiveCredentialUsername ? (
                    <strong className="font-mono text-text-primary">
                      {profileStatus.effectiveCredentialUsername} ({profileStatus.effectiveCredentialSource})
                    </strong>
                  ) : (
                    <span className="text-accent-danger font-semibold">None configured</span>
                  )}
                </span>
                {!profileStatus?.sshHostKeyTrusted && (
                  <span className="text-accent-warning font-semibold">
                    SSH Host Key Not Trusted (Trust in Services tab first)
                  </span>
                )}
              </div>
            </div>
          </div>

          <div className="flex flex-wrap items-center gap-2">
            <select
              aria-label="Select OS Profile"
              value={profileStatus?.osProfileId || ''}
              onChange={(e) => handleAssignProfile(e.target.value, true)}
              className="bg-bg-base border border-border-subtle rounded-lg px-3 py-1.5 text-xs text-text-primary focus:outline-none focus:border-accent-primary"
            >
              <option value="">-- Auto-Detect Profile --</option>
              {allProfiles.map((p) => (
                <option key={p.id} value={p.id}>
                  {p.displayName} ({p.slug})
                </option>
              ))}
            </select>

            {profileStatus?.osProfileId && (
              <button
                type="button"
                onClick={() => handleAssignProfile(profileStatus.osProfileId!, !profileStatus.pinned)}
                className="px-3 py-1.5 rounded-lg border border-border-subtle bg-bg-surface-raised text-xs font-semibold text-text-secondary hover:text-text-primary flex items-center gap-1 cursor-pointer"
                title={profileStatus.pinned ? 'Unpin OS Profile' : 'Pin OS Profile'}
              >
                {profileStatus.pinned ? <Unlock className="w-3.5 h-3.5" /> : <Lock className="w-3.5 h-3.5" />}
                {profileStatus.pinned ? 'Unpin' : 'Pin'}
              </button>
            )}

            <button
              type="button"
              onClick={handleDetectOs}
              disabled={detecting}
              className="px-3 py-1.5 rounded-lg border border-border-subtle bg-bg-surface-raised text-xs font-semibold text-text-primary hover:border-accent-primary flex items-center gap-1.5 cursor-pointer disabled:opacity-50"
            >
              <RefreshCcw className={`w-3.5 h-3.5 ${detecting ? 'animate-spin' : ''}`} />
              Detect OS
            </button>

            <button
              type="button"
              onClick={handleConfigureSudoers}
              className="px-3 py-1.5 rounded-lg border border-border-subtle bg-bg-surface-raised text-xs font-semibold text-text-primary hover:border-accent-primary flex items-center gap-1.5 cursor-pointer"
            >
              <FileCode2 className="w-3.5 h-3.5 text-accent-info" />
              Configure sudoers.d
            </button>
          </div>
        </div>
      </div>

      {/* Active Running Operation Banner */}
      {activeRunningJob && (
        <div className="p-4 rounded-2xl bg-accent-primary/10 border border-accent-primary/30 flex flex-wrap items-center justify-between gap-4 shadow-lg animate-pulse">
          <div className="flex items-center gap-3.5">
            <div className="p-2.5 rounded-xl bg-accent-primary/20 text-accent-primary flex items-center justify-center">
              <RefreshCcw className="w-5 h-5 animate-spin" />
            </div>
            <div>
              <div className="flex items-center gap-2">
                <span className="font-bold text-sm text-text-primary">
                  Operation in Progress: {activeRunningJob.mode.replace('_', ' ')}
                </span>
                <span className="px-2 py-0.5 rounded text-[10px] font-bold uppercase bg-accent-primary/20 text-accent-primary">
                  {activeRunningJob.status}
                </span>
                {activeRunningJob.currentPhase && (
                  <span className="px-2 py-0.5 rounded text-[10px] font-mono bg-bg-base border border-border-subtle text-accent-info">
                    Phase: {activeRunningJob.currentPhase}
                  </span>
                )}
              </div>
              <div className="text-xs text-text-secondary mt-1 flex items-center gap-2">
                <Clock className="w-3.5 h-3.5 text-text-muted" />
                <span>
                  Started: {new Date(activeRunningJob.startedAt || activeRunningJob.createdAt || Date.now()).toLocaleTimeString()}
                </span>
                {activeRunningJob.triggeredBy && <span>· By: {activeRunningJob.triggeredBy}</span>}
                {activeRunningJob.requestedPackages && activeRunningJob.requestedPackages.length > 0 && (
                  <span>· ({activeRunningJob.requestedPackages.length} package(s))</span>
                )}
              </div>
            </div>
          </div>
          <button
            type="button"
            onClick={() => setActiveJobId(activeRunningJob.id)}
            className="px-4 py-2 rounded-xl bg-accent-primary text-white text-xs font-semibold hover:bg-accent-primary/90 flex items-center gap-2 cursor-pointer shadow"
          >
            <Terminal className="w-4 h-4" />
            View Live Stream
          </button>
        </div>
      )}

      {errorMsg && (
        <div className="p-4 rounded-xl bg-accent-danger/10 border border-accent-danger/30 text-accent-danger text-xs font-semibold">
          {errorMsg}
        </div>
      )}

      {lastScan && (
        <div className="p-3.5 rounded-xl bg-accent-success/10 border border-accent-success/30 text-accent-success text-xs flex items-center justify-between">
          <span>
            Inventory scan completed ({lastScan.packageCount} packages parsed, {lastScan.skippedLines} lines skipped)
            {lastScan.usedFallback ? ' [Used Fallback Credential]' : ''}.
          </span>
        </div>
      )}

      {/* Package & Updates Unified Table */}
      <div className="bg-bg-surface border border-border-subtle rounded-2xl p-6 space-y-5 shadow-lg">
        {/* Action Header */}
        <div className="flex flex-wrap items-center justify-between gap-4">
          <div className="flex items-center gap-3">
            <Package className="w-5 h-5 text-accent-primary" />
            <h3 className="font-bold text-base">Package Inventory & Upgrades</h3>

            {/* Filter pills */}
            <div className="flex items-center p-1 rounded-xl bg-bg-surface-raised border border-border-subtle text-xs ml-2">
              <button
                type="button"
                onClick={() => setFilterView('ALL')}
                className={`px-3 py-1 rounded-lg font-semibold transition-all cursor-pointer ${filterView === 'ALL'
                    ? 'bg-accent-primary text-white shadow-sm'
                    : 'text-text-secondary hover:text-text-primary'
                  }`}
              >
                All Packages ({packages.length})
              </button>
              <button
                type="button"
                onClick={() => setFilterView('UPDATES')}
                className={`px-3 py-1 rounded-lg font-semibold transition-all cursor-pointer flex items-center gap-1.5 ${filterView === 'UPDATES'
                    ? 'bg-accent-primary text-white shadow-sm'
                    : 'text-text-secondary hover:text-text-primary'
                  }`}
              >
                <ArrowUpCircle className="w-3.5 h-3.5 text-accent-info" />
                Updates Available ({updatesCount})
              </button>
              <button
                type="button"
                onClick={() => setFilterView('VULNERABLE')}
                className={`px-3 py-1 rounded-lg font-semibold transition-all cursor-pointer flex items-center gap-1.5 ${filterView === 'VULNERABLE'
                    ? 'bg-accent-primary text-white shadow-sm'
                    : 'text-text-secondary hover:text-text-primary'
                  }`}
              >
                <ShieldAlert className="w-3.5 h-3.5 text-accent-danger" />
                Vulnerable ({vulnerableCount})
              </button>
            </div>
          </div>

          <div className="flex flex-wrap items-center gap-2">
            <div className="relative">
              <Search className="w-3.5 h-3.5 text-text-muted absolute left-3 top-1/2 -translate-y-1/2" />
              <input
                type="text"
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                placeholder="Search packages, versions..."
                className="bg-bg-base border border-border-subtle rounded-lg pl-8 pr-3 py-1.5 text-xs text-text-primary focus:outline-none focus:border-accent-primary w-56"
              />
            </div>

            <button
              type="button"
              onClick={handleCheckUpdates}
              disabled={checkingUpdates}
              className="px-3.5 py-1.5 rounded-lg border border-border-subtle bg-bg-surface-raised text-xs font-semibold text-text-primary hover:border-accent-primary flex items-center gap-1.5 cursor-pointer disabled:opacity-50"
              title="Query remote repositories for candidate version upgrades"
            >
              <RefreshCcw className={`w-3.5 h-3.5 text-accent-info ${checkingUpdates ? 'animate-spin' : ''}`} />
              {checkingUpdates ? 'Checking...' : 'Check Updates'}
            </button>

            {updatesCount > 0 && (
              <>
                <button
                  type="button"
                  onClick={() => handleStartPatchJob('FULL_UPGRADE')}
                  disabled={startingPatchJob}
                  className="px-3.5 py-1.5 rounded-lg bg-accent-success text-white text-xs font-semibold hover:bg-accent-success/90 flex items-center gap-1.5 cursor-pointer disabled:opacity-50"
                >
                  <ArrowUpCircle className="w-3.5 h-3.5" />
                  Upgrade All ({updatesCount})
                </button>

                {selectedPkgs.length > 0 && (
                  <button
                    type="button"
                    onClick={() => handleStartPatchJob('SELECTED_PACKAGES')}
                    disabled={startingPatchJob}
                    className="px-3.5 py-1.5 rounded-lg bg-accent-primary text-white text-xs font-semibold hover:bg-accent-primary/90 flex items-center gap-1.5 cursor-pointer disabled:opacity-50"
                  >
                    Upgrade Selected ({selectedPkgs.length})
                  </button>
                )}
              </>
            )}

            <button
              type="button"
              onClick={handleDownloadSbom}
              disabled={packages.length === 0}
              className="px-3 py-1.5 rounded-lg border border-border-subtle bg-bg-surface-raised text-xs font-semibold text-text-secondary hover:text-text-primary flex items-center gap-1.5 cursor-pointer disabled:opacity-50"
              title="Export CycloneDX SBOM"
            >
              <Download className="w-3.5 h-3.5" />
              SBOM
            </button>

            <button
              type="button"
              onClick={handleScanInventory}
              disabled={scanning}
              className="px-3.5 py-1.5 rounded-lg bg-accent-primary text-white text-xs font-semibold hover:bg-accent-primary/90 flex items-center gap-1.5 cursor-pointer disabled:opacity-50"
            >
              <RefreshCcw className={`w-3.5 h-3.5 ${scanning ? 'animate-spin' : ''}`} />
              {scanning ? 'Scanning...' : 'Scan Inventory'}
            </button>
          </div>
        </div>

        {loading ? (
          <div className="py-12 text-center text-xs text-text-muted">Loading package inventory...</div>
        ) : packages.length === 0 ? (
          <div className="py-12 text-center border border-dashed border-border-subtle rounded-xl text-xs text-text-secondary">
            No packages matched the current filter. Click <strong>Scan Inventory</strong> to collect packages or <strong>Check Updates</strong> to detect updates.
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-left border-collapse text-xs">
              <thead>
                <tr className="border-b border-border-subtle text-text-secondary uppercase tracking-wider text-[10px]">
                  <th className="py-2.5 px-3 w-8">
                    <span className="sr-only">Select</span>
                  </th>
                  <th className="py-2.5 px-3">Package</th>
                  <th className="py-2.5 px-3">Installed Version</th>
                  <th className="py-2.5 px-3">Status / Update</th>
                  <th className="py-2.5 px-3">Arch</th>
                  <th className="py-2.5 px-3 text-right">Security</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border-subtle/50">
                {packages.map((pkg) => {
                  const hasUpdate = !!pkg.candidateVersion
                  const isSelected = selectedPkgs.includes(pkg.name)

                  return (
                    <tr
                      key={pkg.id}
                      className={`transition-colors ${hasUpdate ? 'bg-accent-primary/[0.02] hover:bg-accent-primary/[0.05]' : 'hover:bg-bg-surface-raised/60'
                        }`}
                    >
                      <td className="py-2.5 px-3">
                        {hasUpdate ? (
                          <input
                            type="checkbox"
                            checked={isSelected}
                            onChange={() => toggleSelectPkg(pkg.name)}
                            className="rounded accent-accent-primary cursor-pointer"
                            aria-label={`Select ${pkg.name} for update`}
                          />
                        ) : (
                          <span className="w-3.5 h-3.5 inline-block text-text-muted" />
                        )}
                      </td>
                      <td className="py-2.5 px-3">
                        <div className="font-semibold text-text-primary font-mono">{pkg.name}</div>
                        {pkg.sourceName && pkg.sourceName !== pkg.name && (
                          <div className="text-[10px] text-text-muted font-mono">src: {pkg.sourceName}</div>
                        )}
                      </td>
                      <td className="py-2.5 px-3 font-mono text-text-secondary">{pkg.version}</td>
                      <td className="py-2.5 px-3">
                        {hasUpdate ? (
                          <div className="flex flex-wrap items-center gap-1.5">
                            <span className="font-mono font-bold text-accent-info">{pkg.candidateVersion}</span>
                            <span className="px-1.5 py-0.5 rounded text-[9px] font-bold uppercase bg-accent-info/15 text-accent-info border border-accent-info/30">
                              Update Available
                            </span>
                            {pkg.isSecurityUpdate && (
                              <span className="px-1.5 py-0.5 rounded text-[9px] font-bold uppercase bg-accent-warning/15 text-accent-warning border border-accent-warning/30">
                                Security
                              </span>
                            )}
                          </div>
                        ) : (
                          <span className="text-[10px] text-text-muted">Up to date</span>
                        )}
                      </td>
                      <td className="py-2.5 px-3 font-mono text-text-muted">{pkg.arch || '-'}</td>
                      <td className="py-2.5 px-3 text-right">
                        {pkg.openVulnerabilityCount > 0 ? (
                          <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[10px] font-bold bg-accent-danger/15 text-accent-danger border border-accent-danger/30">
                            <ShieldAlert className="w-3 h-3" />
                            {pkg.openVulnerabilityCount} CVE
                          </span>
                        ) : (
                          <span className="inline-flex items-center gap-1 text-[10px] text-accent-success font-semibold">
                            <ShieldCheck className="w-3 h-3" /> Clean
                          </span>
                        )}
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {/* Operations & Execution History Card */}
      {patchJobs.length > 0 && (
        <div className="bg-bg-surface border border-border-subtle rounded-2xl p-6 space-y-4 shadow-lg">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-2.5">
              <Terminal className="w-4 h-4 text-accent-primary" />
              <h4 className="font-bold text-sm">Node Operations & Execution History</h4>
            </div>
            <span className="text-xs text-text-muted">{patchJobs.length} operation(s) recorded</span>
          </div>

          <div className="divide-y divide-border-subtle/50 text-xs">
            {patchJobs.map((job) => {
              const badge = getJobStatusBadge(job.status)
              return (
                <button
                  type="button"
                  key={job.id}
                  className="w-full py-3.5 flex flex-wrap items-center justify-between gap-3 hover:bg-bg-surface-raised/40 px-2.5 rounded-xl transition-colors cursor-pointer text-left"
                  onClick={() => setActiveJobId(job.id)}
                >
                  <div className="flex items-center gap-3">
                    <span className={`p-2 rounded-xl border ${badge.bg}`}>
                      {badge.icon}
                    </span>
                    <div>
                      <div className="font-semibold text-text-primary flex items-center gap-2">
                        <span>{job.mode.replace('_', ' ')}</span>
                        {job.osProfileSlug && (
                          <span className="text-[10px] font-mono px-1.5 py-0.5 rounded bg-bg-base border border-border-subtle text-text-muted">
                            {job.osProfileSlug}
                          </span>
                        )}
                        {job.currentPhase && (
                          <span className="text-[10px] font-mono px-1.5 py-0.5 rounded bg-accent-info/10 text-accent-info border border-accent-info/20">
                            {job.currentPhase}
                          </span>
                        )}
                      </div>
                      <div className="text-[10px] text-text-secondary mt-1 flex flex-wrap items-center gap-2">
                        <span className="flex items-center gap-1 text-text-muted">
                          <Clock className="w-3 h-3" />
                          {new Date(job.startedAt || job.createdAt || Date.now()).toLocaleString()}
                        </span>
                        {job.triggeredBy && <span>· By: {job.triggeredBy}</span>}
                        {job.exitCode !== null && job.exitCode !== undefined && (
                          <span>· Exit code: {job.exitCode}</span>
                        )}
                        {job.rebootRequired && (
                          <span className="text-accent-warning font-semibold">· Reboot Required</span>
                        )}
                        {job.errorSummary && (
                          <span className="text-accent-danger font-medium">· Error: {job.errorSummary}</span>
                        )}
                      </div>
                    </div>
                  </div>

                  <div className="flex items-center gap-2">
                    <span
                      className={`px-2 py-0.5 rounded text-[10px] font-bold uppercase border ${badge.bg}`}
                    >
                      {badge.label}
                    </span>
                    <span className="text-accent-primary text-xs font-semibold hover:underline">
                      View Logs
                    </span>
                  </div>
                </button>
              )
            })}
          </div>
        </div>
      )}

      {/* Streaming Patch Logs Modal */}
      {activeJobId && (
        <PatchJobLogModal
          jobId={activeJobId}
          onClose={() => {
            setActiveJobId(null)
            void loadData()
          }}
          onJobUpdated={(updated) => {
            setPatchJobs((prev) => prev.map((j) => (j.id === updated.id ? updated : j)))
            const norm = (updated.status || '').toUpperCase()
            if (norm === 'SUCCEEDED' || norm === 'SUCCESS' || norm === 'FAILED' || norm === 'CANCELLED') {
              void loadData()
            }
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
                    void navigator.clipboard.writeText(sudoersModal.snippet)
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
