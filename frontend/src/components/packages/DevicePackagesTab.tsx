import React, { useCallback, useEffect, useState } from 'react'
import { useAuth } from '../../lib/auth/auth-context'
import {
  DeviceOsProfileStatus,
  DevicePackageItem,
  DevicePackageScanResult,
  OsProfileSummary
} from '../../lib/api/packages'
import {
  Cpu,
  Download,
  KeyRound,
  Lock,
  Package,
  RefreshCcw,
  Search,
  ShieldAlert,
  Unlock
} from 'lucide-react'

interface Props {
  deviceId: string
}

export const DevicePackagesTab: React.FC<Props> = ({ deviceId }) => {
  const { apiClient } = useAuth()
  const [packages, setPackages] = useState<DevicePackageItem[]>([])
  const [profileStatus, setProfileStatus] = useState<DeviceOsProfileStatus | null>(null)
  const [allProfiles, setAllProfiles] = useState<OsProfileSummary[]>([])
  const [query, setQuery] = useState('')
  const [vulnerableOnly, setVulnerableOnly] = useState(false)
  const [loading, setLoading] = useState(true)
  const [scanning, setScanning] = useState(false)
  const [detecting, setDetecting] = useState(false)
  const [errorMsg, setErrorMsg] = useState<string | null>(null)
  const [lastScan, setLastScan] = useState<DevicePackageScanResult | null>(null)

  const loadData = useCallback(async () => {
    setLoading(true)
    setErrorMsg(null)
    try {
      const params = new URLSearchParams()
      if (query.trim()) params.set('q', query.trim())
      if (vulnerableOnly) params.set('vulnerableOnly', 'true')
      const qs = params.toString() ? `?${params.toString()}` : ''

      const [pkgs, status, profiles] = await Promise.all([
        apiClient<DevicePackageItem[]>(`/api/devices/${deviceId}/packages${qs}`),
        apiClient<DeviceOsProfileStatus>(`/api/devices/${deviceId}/os-profile`),
        apiClient<OsProfileSummary[]>('/api/os-profiles').catch(() => [])
      ])
      setPackages(pkgs)
      setProfileStatus(status)
      setAllProfiles(profiles)
    } catch (err) {
      setErrorMsg(err instanceof Error ? err.message : 'Failed to load package inventory')
    } finally {
      setLoading(false)
    }
  }, [apiClient, deviceId, query, vulnerableOnly])

  useEffect(() => {
    loadData()
  }, [loadData])

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

  return (
    <div className="space-y-6">
      {/* OS Profile & Effective Credential Card */}
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
          </div>
        </div>
      </div>

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

      {/* Package Inventory Table */}
      <div className="bg-bg-surface border border-border-subtle rounded-2xl p-6 space-y-5 shadow-lg">
        <div className="flex flex-wrap items-center justify-between gap-4">
          <div className="flex items-center gap-2.5">
            <Package className="w-5 h-5 text-accent-primary" />
            <h3 className="font-bold text-base">Installed Packages ({packages.length})</h3>
          </div>

          <div className="flex flex-wrap items-center gap-2.5">
            <div className="relative">
              <Search className="w-3.5 h-3.5 text-text-muted absolute left-3 top-1/2 -translate-y-1/2" />
              <input
                type="text"
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                placeholder="Filter by package, version, source..."
                className="bg-bg-base border border-border-subtle rounded-lg pl-8 pr-3 py-1.5 text-xs text-text-primary focus:outline-none focus:border-accent-primary w-64"
              />
            </div>

            <label className="flex items-center gap-1.5 text-xs text-text-secondary cursor-pointer select-none">
              <input
                type="checkbox"
                checked={vulnerableOnly}
                onChange={(e) => setVulnerableOnly(e.target.checked)}
                className="accent-accent-primary"
              />
              Vulnerable only
            </label>

            <button
              type="button"
              onClick={handleDownloadSbom}
              disabled={packages.length === 0}
              className="px-3 py-1.5 rounded-lg border border-border-subtle bg-bg-surface-raised text-xs font-semibold text-text-primary hover:border-accent-primary flex items-center gap-1.5 cursor-pointer disabled:opacity-50"
            >
              <Download className="w-3.5 h-3.5" />
              Download SBOM (CycloneDX)
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
            No packages collected yet. Click <strong>Scan Inventory</strong> to collect installed packages over SSH.
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-left border-collapse text-xs">
              <thead>
                <tr className="border-b border-border-subtle text-text-secondary uppercase tracking-wider text-[10px]">
                  <th className="py-2.5 px-3">Package</th>
                  <th className="py-2.5 px-3">Version</th>
                  <th className="py-2.5 px-3">Arch</th>
                  <th className="py-2.5 px-3">Source Package</th>
                  <th className="py-2.5 px-3">PURL</th>
                  <th className="py-2.5 px-3 text-right">Security</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border-subtle/50">
                {packages.map((pkg) => (
                  <tr key={pkg.id} className="hover:bg-bg-surface-raised/60">
                    <td className="py-2.5 px-3 font-semibold text-text-primary font-mono">{pkg.name}</td>
                    <td className="py-2.5 px-3 font-mono text-text-secondary">{pkg.version}</td>
                    <td className="py-2.5 px-3 font-mono text-text-muted">{pkg.arch || '-'}</td>
                    <td className="py-2.5 px-3 font-mono text-text-secondary">{pkg.sourceName}</td>
                    <td className="py-2.5 px-3 font-mono text-[11px] text-text-muted truncate max-w-xs" title={pkg.purl}>
                      {pkg.purl}
                    </td>
                    <td className="py-2.5 px-3 text-right">
                      {pkg.openVulnerabilityCount > 0 ? (
                        <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[10px] font-bold bg-accent-danger/15 text-accent-danger border border-accent-danger/30">
                          <ShieldAlert className="w-3 h-3" />
                          {pkg.openVulnerabilityCount} CVE
                        </span>
                      ) : (
                        <span className="text-[10px] text-accent-success font-semibold">Clean</span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  )
}

