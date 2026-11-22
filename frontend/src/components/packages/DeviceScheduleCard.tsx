import React, { useCallback, useEffect, useState } from 'react'
import { useAuth } from '../../lib/auth/auth-context'
import { DevicePackageScheduleDto } from '../../lib/api/packages'
import { CalendarClock, Check, Clock, Save } from 'lucide-react'

interface Props {
  deviceId: string
}

const CRON_PRESETS: Array<{ label: string; cron: string }> = [
  { label: 'Daily at 03:00', cron: '0 3 * * *' },
  { label: 'Daily at 04:00', cron: '0 4 * * *' },
  { label: 'Every 6 Hours', cron: '0 */6 * * *' },
  { label: 'Weekly (Sunday 05:00)', cron: '0 5 * * 0' }
]

export const DeviceScheduleCard: React.FC<Props> = ({ deviceId }) => {
  const { apiClient } = useAuth()
  const [schedule, setSchedule] = useState<DevicePackageScheduleDto | null>(null)
  const [inventoryEnabled, setInventoryEnabled] = useState(false)
  const [inventoryCron, setInventoryCron] = useState('0 3 * * *')
  const [updateCheckEnabled, setUpdateCheckEnabled] = useState(false)
  const [updateCheckCron, setUpdateCheckCron] = useState('0 4 * * *')
  const [autoPatchEnabled, setAutoPatchEnabled] = useState(false)
  const [autoPatchMode, setAutoPatchMode] = useState<'SECURITY_ONLY' | 'FULL_UPGRADE'>(
    'SECURITY_ONLY'
  )
  const [autoPatchCron, setAutoPatchCron] = useState('0 5 * * 0')
  const [timezone, setTimezone] = useState('UTC')
  const [saving, setSaving] = useState(false)
  const [savedNotice, setSavedNotice] = useState(false)
  const [errorMsg, setErrorMsg] = useState<string | null>(null)

  const applyScheduleState = (data: DevicePackageScheduleDto) => {
    setSchedule(data)
    setInventoryEnabled(data.inventoryEnabled)
    setInventoryCron(data.inventoryCron || '0 3 * * *')
    setUpdateCheckEnabled(data.updateCheckEnabled)
    setUpdateCheckCron(data.updateCheckCron || '0 4 * * *')
    setAutoPatchEnabled(data.autoPatchEnabled)
    setAutoPatchMode(
      data.autoPatchMode === 'FULL_UPGRADE' ? 'FULL_UPGRADE' : 'SECURITY_ONLY'
    )
    setAutoPatchCron(data.autoPatchCron || '0 5 * * 0')
    setTimezone(data.timezone || 'UTC')
  }

  const loadSchedule = useCallback(async () => {
    try {
      const data = await apiClient<DevicePackageScheduleDto>(
        `/api/devices/${deviceId}/package-schedule`
      )
      applyScheduleState(data)
    } catch (err) {
      setErrorMsg(err instanceof Error ? err.message : 'Failed to load package schedule')
    }
  }, [apiClient, deviceId])

  useEffect(() => {
    void loadSchedule()
  }, [loadSchedule])

  const handleSave = async (e: React.FormEvent) => {
    e.preventDefault()
    setSaving(true)
    setSavedNotice(false)
    setErrorMsg(null)
    try {
      const data = await apiClient<DevicePackageScheduleDto>(
        `/api/devices/${deviceId}/package-schedule`,
        {
          method: 'PUT',
          body: JSON.stringify({
            inventoryEnabled,
            inventoryCron,
            updateCheckEnabled,
            updateCheckCron,
            autoPatchEnabled,
            autoPatchMode,
            autoPatchCron,
            timezone
          })
        }
      )
      applyScheduleState(data)
      setSavedNotice(true)
    } catch (err) {
      setErrorMsg(err instanceof Error ? err.message : 'Failed to save package schedule')
    } finally {
      setSaving(false)
    }
  }

  return (
    <form
      onSubmit={handleSave}
      className="bg-bg-surface border border-border-subtle rounded-2xl p-6 space-y-5 shadow-lg"
    >
      <div className="flex flex-wrap items-center justify-between gap-4 pb-4 border-b border-border-subtle">
        <div className="flex items-center gap-2.5">
          <CalendarClock className="w-5 h-5 text-accent-primary" />
          <div>
            <h3 className="font-bold text-base">Per-Device Automation Schedule</h3>
            <p className="text-xs text-text-secondary">
              Configure recurring package inventory scans, update checks, and opt-in automatic patching
            </p>
          </div>
        </div>

        <div className="flex items-center gap-2.5">
          <label className="text-xs text-text-secondary flex items-center gap-1.5">
            <span>Timezone:</span>
            <select
              value={timezone}
              onChange={(e) => setTimezone(e.target.value)}
              className="px-2.5 py-1 rounded-lg bg-bg-base border border-border-subtle text-xs font-mono text-text-primary"
            >
              <option value="UTC">UTC</option>
              <option value="Europe/Warsaw">Europe/Warsaw</option>
              <option value="Europe/London">Europe/London</option>
              <option value="America/New_York">America/New_York</option>
            </select>
          </label>

          <button
            type="submit"
            disabled={saving}
            className="px-3.5 py-1.5 rounded-lg bg-accent-primary text-white text-xs font-semibold hover:bg-accent-primary/90 flex items-center gap-1.5 cursor-pointer disabled:opacity-50"
          >
            {savedNotice ? <Check className="w-3.5 h-3.5" /> : <Save className="w-3.5 h-3.5" />}
            {saving ? 'Saving...' : savedNotice ? 'Schedule Saved' : 'Save Schedule'}
          </button>
        </div>
      </div>

      {errorMsg && (
        <div className="p-3.5 rounded-xl bg-accent-danger/10 border border-accent-danger/30 text-accent-danger text-xs font-semibold">
          {errorMsg}
        </div>
      )}

      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        {/* Inventory Schedule */}
        <div className="p-4 rounded-xl border border-border-subtle bg-bg-surface-raised space-y-3">
          <label className="flex items-center justify-between cursor-pointer">
            <span className="text-xs font-bold text-text-primary">Inventory & CVE Scan</span>
            <input
              type="checkbox"
              checked={inventoryEnabled}
              onChange={(e) => setInventoryEnabled(e.target.checked)}
              className="accent-accent-primary"
            />
          </label>
          <div className="space-y-1.5">
            <label className="text-[11px] text-text-secondary block">Cron (5-field)</label>
            <input
              type="text"
              value={inventoryCron}
              onChange={(e) => setInventoryCron(e.target.value)}
              className="w-full px-2.5 py-1.5 rounded-lg bg-bg-base border border-border-subtle font-mono text-xs text-text-primary"
              placeholder="0 3 * * *"
            />
            <div className="flex flex-wrap gap-1 pt-1">
              {CRON_PRESETS.map((p) => (
                <button
                  key={p.cron}
                  type="button"
                  onClick={() => setInventoryCron(p.cron)}
                  className="px-2 py-0.5 rounded bg-bg-base border border-border-subtle text-[10px] text-text-secondary hover:border-accent-primary cursor-pointer"
                >
                  {p.label}
                </button>
              ))}
            </div>
          </div>
          {schedule?.nextInventoryRunAt && inventoryEnabled && (
            <p className="text-[11px] text-text-muted font-mono">
              Next: {new Date(schedule.nextInventoryRunAt).toLocaleString()}
            </p>
          )}
        </div>

        {/* Update Check Schedule */}
        <div className="p-4 rounded-xl border border-border-subtle bg-bg-surface-raised space-y-3">
          <label className="flex items-center justify-between cursor-pointer">
            <span className="text-xs font-bold text-text-primary">Available Updates Check</span>
            <input
              type="checkbox"
              checked={updateCheckEnabled}
              onChange={(e) => setUpdateCheckEnabled(e.target.checked)}
              className="accent-accent-primary"
            />
          </label>
          <div className="space-y-1.5">
            <label className="text-[11px] text-text-secondary block">Cron (5-field)</label>
            <input
              type="text"
              value={updateCheckCron}
              onChange={(e) => setUpdateCheckCron(e.target.value)}
              className="w-full px-2.5 py-1.5 rounded-lg bg-bg-base border border-border-subtle font-mono text-xs text-text-primary"
              placeholder="0 4 * * *"
            />
          </div>
          {schedule?.nextUpdateCheckRunAt && updateCheckEnabled && (
            <p className="text-[11px] text-text-muted font-mono">
              Next: {new Date(schedule.nextUpdateCheckRunAt).toLocaleString()}
            </p>
          )}
        </div>

        {/* Auto-Patch Schedule (Opt-In) */}
        <div className="p-4 rounded-xl border border-border-subtle bg-bg-surface-raised space-y-3">
          <label className="flex items-center justify-between cursor-pointer">
            <span className="text-xs font-bold text-text-primary">Automatic Patching (Opt-In)</span>
            <input
              type="checkbox"
              checked={autoPatchEnabled}
              onChange={(e) => setAutoPatchEnabled(e.target.checked)}
              className="accent-accent-primary"
            />
          </label>
          <div className="space-y-1.5">
            <label className="text-[11px] text-text-secondary block">Patch Mode</label>
            <select
              value={autoPatchMode}
              onChange={(e) =>
                setAutoPatchMode(
                  e.target.value === 'FULL_UPGRADE' ? 'FULL_UPGRADE' : 'SECURITY_ONLY'
                )
              }
              className="w-full px-2.5 py-1.5 rounded-lg bg-bg-base border border-border-subtle text-xs text-text-primary"
            >
              <option value="SECURITY_ONLY">Security Updates Only</option>
              <option value="FULL_UPGRADE">Full System Upgrade</option>
            </select>
          </div>
          <div className="space-y-1.5">
            <label className="text-[11px] text-text-secondary block">Cron (5-field)</label>
            <input
              type="text"
              value={autoPatchCron}
              onChange={(e) => setAutoPatchCron(e.target.value)}
              className="w-full px-2.5 py-1.5 rounded-lg bg-bg-base border border-border-subtle font-mono text-xs text-text-primary"
              placeholder="0 5 * * 0"
            />
          </div>
          {schedule?.nextAutoPatchRunAt && autoPatchEnabled && (
            <p className="text-[11px] text-text-muted font-mono">
              Next: {new Date(schedule.nextAutoPatchRunAt).toLocaleString()}
            </p>
          )}
        </div>
      </div>

      {schedule?.nextPreviewRuns && schedule.nextPreviewRuns.length > 0 && (
        <div className="pt-2 border-t border-border-subtle flex flex-wrap items-center gap-3 text-xs text-text-secondary">
          <span className="flex items-center gap-1 font-semibold">
            <Clock className="w-3.5 h-3.5 text-accent-primary" />
            Upcoming Inventory Runs ({schedule.timezone}):
          </span>
          {schedule.nextPreviewRuns.map((ts) => (
            <span
              key={ts}
              className="px-2 py-0.5 rounded bg-bg-base border border-border-subtle font-mono text-[11px]"
            >
              {new Date(ts).toLocaleString()}
            </span>
          ))}
        </div>
      )}
    </form>
  )
}

