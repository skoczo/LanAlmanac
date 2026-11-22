import React, { useCallback, useEffect, useState } from 'react'
import { useAuth } from '../../lib/auth/auth-context'
import { DeviceVulnerabilityItem } from '../../lib/api/packages'
import { ExternalLink, RefreshCcw, ShieldAlert, ShieldCheck } from 'lucide-react'

interface Props {
  deviceId: string
}

const SEVERITY_STYLES: Record<string, string> = {
  CRITICAL: 'bg-accent-danger/20 text-accent-danger border-accent-danger/40',
  HIGH: 'bg-orange-500/20 text-orange-400 border-orange-500/40',
  MEDIUM: 'bg-accent-warning/20 text-accent-warning border-accent-warning/40',
  LOW: 'bg-accent-info/20 text-accent-info border-accent-info/40',
  UNKNOWN: 'bg-border-subtle/50 text-text-secondary border-border-subtle'
}

export const DeviceVulnerabilitiesTab: React.FC<Props> = ({ deviceId }) => {
  const { apiClient } = useAuth()
  const [vulns, setVulns] = useState<DeviceVulnerabilityItem[]>([])
  const [statusFilter, setStatusFilter] = useState('OPEN')
  const [severityFilter, setSeverityFilter] = useState('')
  const [fixAvailableOnly, setFixAvailableOnly] = useState(true)
  const [loading, setLoading] = useState(true)
  const [scanning, setScanning] = useState(false)
  const [errorMsg, setErrorMsg] = useState<string | null>(null)

  const loadVulns = useCallback(async () => {
    setLoading(true)
    setErrorMsg(null)
    try {
      const params = new URLSearchParams()
      if (statusFilter) params.set('status', statusFilter)
      if (severityFilter) params.set('severity', severityFilter)
      if (fixAvailableOnly) params.set('fixAvailable', 'true')
      const qs = params.toString() ? `?${params.toString()}` : ''
      const data = await apiClient<DeviceVulnerabilityItem[]>(
        `/api/devices/${deviceId}/vulnerabilities${qs}`
      )
      setVulns(data)
    } catch (err) {
      setErrorMsg(err instanceof Error ? err.message : 'Failed to load vulnerabilities')
    } finally {
      setLoading(false)
    }
  }, [apiClient, deviceId, statusFilter, severityFilter, fixAvailableOnly])

  useEffect(() => {
    loadVulns()
  }, [loadVulns])

  const handleRefreshAssessment = async () => {
    setScanning(true)
    setErrorMsg(null)
    try {
      await apiClient(`/api/devices/${deviceId}/vulnerabilities/scan`, { method: 'POST' })
      await loadVulns()
    } catch (err) {
      setErrorMsg(err instanceof Error ? err.message : 'Vulnerability assessment failed')
    } finally {
      setScanning(false)
    }
  }

  const handleUpdateStatus = async (vulnId: string, newStatus: string) => {
    const note =
      newStatus === 'OPEN'
        ? null
        : window.prompt(`Optional note for marking this vulnerability as ${newStatus}:`, '') || ''
    try {
      await apiClient(`/api/vulnerabilities/${vulnId}/status`, {
        method: 'PATCH',
        body: JSON.stringify({ status: newStatus, suppressionNote: note })
      })
      await loadVulns()
    } catch (err) {
      setErrorMsg(err instanceof Error ? err.message : 'Failed to update status')
    }
  }

  return (
    <div className="bg-bg-surface border border-border-subtle rounded-2xl p-6 space-y-5 shadow-lg">
      <div className="flex flex-wrap items-center justify-between gap-4 pb-4 border-b border-border-subtle">
        <div className="flex items-center gap-2.5">
          <ShieldAlert className="w-5 h-5 text-accent-danger" />
          <div>
            <h3 className="font-bold text-base">Vulnerability Assessment ({vulns.length})</h3>
            <p className="text-xs text-text-secondary">
              Passive server-side CVE matching against collected package inventory (OSV.dev)
            </p>
          </div>
        </div>

        <div className="flex flex-wrap items-center gap-2.5">
          <select
            aria-label="Filter by status"
            value={statusFilter}
            onChange={(e) => setStatusFilter(e.target.value)}
            className="bg-bg-base border border-border-subtle rounded-lg px-3 py-1.5 text-xs text-text-primary"
          >
            <option value="">All Statuses</option>
            <option value="OPEN">Open</option>
            <option value="ACCEPTED_RISK">Accepted Risk</option>
            <option value="FALSE_POSITIVE">False Positive</option>
            <option value="FIXED">Fixed</option>
          </select>

          <select
            aria-label="Filter by severity"
            value={severityFilter}
            onChange={(e) => setSeverityFilter(e.target.value)}
            className="bg-bg-base border border-border-subtle rounded-lg px-3 py-1.5 text-xs text-text-primary"
          >
            <option value="">All Severities</option>
            <option value="CRITICAL">Critical</option>
            <option value="HIGH">High</option>
            <option value="MEDIUM">Medium</option>
            <option value="LOW">Low</option>
          </select>

          <label className="flex items-center gap-1.5 text-xs text-text-secondary cursor-pointer bg-bg-base border border-border-subtle rounded-lg px-3 py-1.5">
            <input
              type="checkbox"
              checked={fixAvailableOnly}
              onChange={(e) => setFixAvailableOnly(e.target.checked)}
              className="accent-accent-primary rounded"
            />
            Fix available only
          </label>

          <button
            type="button"
            onClick={handleRefreshAssessment}
            disabled={scanning}
            className="px-3.5 py-1.5 rounded-lg bg-accent-primary text-white text-xs font-semibold hover:bg-accent-primary/90 flex items-center gap-1.5 cursor-pointer disabled:opacity-50"
          >
            <RefreshCcw className={`w-3.5 h-3.5 ${scanning ? 'animate-spin' : ''}`} />
            {scanning ? 'Assessing...' : 'Re-evaluate CVEs'}
          </button>
        </div>
      </div>

      {errorMsg && (
        <div className="p-4 rounded-xl bg-accent-danger/10 border border-accent-danger/30 text-accent-danger text-xs font-semibold">
          {errorMsg}
        </div>
      )}

      {loading ? (
        <div className="py-12 text-center text-xs text-text-muted">Loading vulnerabilities...</div>
      ) : vulns.length === 0 ? (
        <div className="py-12 text-center border border-dashed border-border-subtle rounded-xl text-xs text-text-secondary flex flex-col items-center gap-2">
          <ShieldCheck className="w-8 h-8 text-accent-success" />
          <span>No matching vulnerabilities found for the selected filter.</span>
        </div>
      ) : (
        <div className="space-y-3">
          {vulns.map((v) => (
            <div
              key={v.id}
              className="p-4 rounded-xl bg-bg-surface-raised border border-border-subtle flex flex-wrap items-start justify-between gap-4"
            >
              <div className="space-y-1.5 max-w-3xl">
                <div className="flex flex-wrap items-center gap-2">
                  <span
                    className={`px-2.5 py-0.5 rounded text-[10px] font-bold uppercase tracking-wider border ${
                      SEVERITY_STYLES[v.severity] || SEVERITY_STYLES.UNKNOWN
                    }`}
                  >
                    {v.severity}
                  </span>

                  <span
                    className={`px-2 py-0.5 rounded text-[10px] font-mono font-semibold border ${
                      v.cvssScore != null && v.cvssScore >= 9.0
                        ? 'bg-red-500/20 text-red-400 border-red-500/40'
                        : v.cvssScore != null && v.cvssScore >= 7.0
                        ? 'bg-orange-500/20 text-orange-400 border-orange-500/40'
                        : v.cvssScore != null && v.cvssScore >= 4.0
                        ? 'bg-yellow-500/20 text-yellow-400 border-yellow-500/40'
                        : v.cvssScore != null
                        ? 'bg-accent-info/20 text-accent-info border-accent-info/40'
                        : 'bg-bg-base border-border-subtle text-text-muted'
                    }`}
                    title={
                      v.cvssScore != null
                        ? `CVSS v3 Base Score: ${v.cvssScore}`
                        : 'CVSS Score not assigned by vendor'
                    }
                  >
                    {v.cvssScore != null ? `CVSS ${v.cvssScore.toFixed(1)}` : 'CVSS N/A'}
                  </span>

                  <span className="font-bold text-sm font-mono text-text-primary">{v.advisoryId}</span>
                  {v.cveAliases.map((cve) => (
                    <span
                      key={cve}
                      className="px-2 py-0.5 rounded text-[10px] font-mono font-semibold bg-accent-info/10 border border-accent-info/30 text-accent-info"
                    >
                      {cve}
                    </span>
                  ))}
                  <span className="px-2 py-0.5 rounded text-[10px] font-mono bg-accent-primary/10 text-accent-primary">
                    {v.packageName} @ {v.installedVersion}
                  </span>
                  {v.fixedVersion && (
                    <span className="px-2 py-0.5 rounded text-[10px] font-mono bg-accent-success/15 text-accent-success">
                      Fixed in: {v.fixedVersion}
                    </span>
                  )}
                  {v.fixAvailableNow && (
                    <span className="px-2 py-0.5 rounded-full text-[10px] font-bold bg-accent-success text-black">
                      Update Ready in Repo
                    </span>
                  )}
                </div>

                {v.summary && <p className="text-xs text-text-secondary">{v.summary}</p>}

                {v.suppressionNote && (
                  <p className="text-[11px] text-accent-warning">
                    Note ({v.status} by {v.suppressedBy || 'operator'}): {v.suppressionNote}
                  </p>
                )}

                {v.references.length > 0 && (
                  <div className="flex flex-wrap gap-3 pt-1">
                    {v.references.slice(0, 3).map((refUrl) => (
                      <a
                        key={refUrl}
                        href={refUrl}
                        target="_blank"
                        rel="noreferrer"
                        className="text-[11px] text-accent-primary hover:underline inline-flex items-center gap-1"
                      >
                        Advisory Reference <ExternalLink className="w-3 h-3" />
                      </a>
                    ))}
                  </div>
                )}
              </div>

              <div className="flex items-center gap-2">
                {v.status === 'OPEN' ? (
                  <>
                    <button
                      type="button"
                      onClick={() => handleUpdateStatus(v.id, 'ACCEPTED_RISK')}
                      className="px-2.5 py-1 rounded-lg border border-border-subtle bg-bg-surface text-[11px] font-semibold text-text-secondary hover:text-text-primary cursor-pointer"
                    >
                      Accept Risk
                    </button>
                    <button
                      type="button"
                      onClick={() => handleUpdateStatus(v.id, 'FALSE_POSITIVE')}
                      className="px-2.5 py-1 rounded-lg border border-border-subtle bg-bg-surface text-[11px] font-semibold text-text-secondary hover:text-text-primary cursor-pointer"
                    >
                      False Positive
                    </button>
                  </>
                ) : (
                  <button
                    type="button"
                    onClick={() => handleUpdateStatus(v.id, 'OPEN')}
                    className="px-2.5 py-1 rounded-lg border border-border-subtle bg-bg-surface text-[11px] font-semibold text-accent-primary hover:border-accent-primary cursor-pointer"
                  >
                    Reopen ({v.status.replace('_', ' ')})
                  </button>
                )}
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

