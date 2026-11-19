import React, { useCallback, useEffect, useState } from 'react'
import { Link } from '@tanstack/react-router'
import { useAuth } from '../lib/auth/auth-context'
import { DeviceVulnerabilityItem } from '../lib/api/packages'
import { ShieldAlert, ShieldCheck } from 'lucide-react'

export const VulnerabilitiesPage: React.FC = () => {
  const { apiClient } = useAuth()
  const [vulns, setVulns] = useState<DeviceVulnerabilityItem[]>([])
  const [severity, setSeverity] = useState('')
  const [status, setStatus] = useState('OPEN')
  const [fixAvailableOnly, setFixAvailableOnly] = useState(false)
  const [loading, setLoading] = useState(true)

  const loadFleetVulns = useCallback(async () => {
    setLoading(true)
    try {
      const params = new URLSearchParams()
      if (severity) params.set('severity', severity)
      if (status) params.set('status', status)
      if (fixAvailableOnly) params.set('fixAvailable', 'true')
      const qs = params.toString() ? `?${params.toString()}` : ''
      const data = await apiClient<DeviceVulnerabilityItem[]>(`/api/vulnerabilities${qs}`)
      setVulns(data)
    } catch (err) {
      console.error(err)
    } finally {
      setLoading(false)
    }
  }, [apiClient, severity, status, fixAvailableOnly])

  useEffect(() => {
    loadFleetVulns()
  }, [loadFleetVulns])

  const criticalCount = vulns.filter((v) => v.severity === 'CRITICAL').length
  const highCount = vulns.filter((v) => v.severity === 'HIGH').length
  const fixReadyCount = vulns.filter((v) => v.fixAvailableNow).length

  return (
    <div className="p-8 space-y-6 max-w-7xl mx-auto">
      <div className="flex flex-wrap items-center justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold tracking-tight flex items-center gap-2.5">
            <ShieldAlert className="w-7 h-7 text-accent-danger" />
            Fleet Vulnerability Center
          </h1>
          <p className="text-sm text-text-secondary mt-1">
            Centralized passive CVE assessment across all managed network hosts
          </p>
        </div>

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
        {loading ? (
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
  )
}

