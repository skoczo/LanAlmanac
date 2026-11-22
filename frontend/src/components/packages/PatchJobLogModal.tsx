import React, { useCallback, useEffect, useRef, useState } from 'react'
import { useAuth } from '../../lib/auth/auth-context'
import { PatchJobDetail, PatchJobItem, PatchJobLogLine } from '../../lib/api/packages'
import {
  AlertTriangle,
  CheckCircle2,
  Loader2,
  Square,
  Terminal,
  X,
  XCircle
} from 'lucide-react'

interface Props {
  jobId: string
  onClose: () => void
  onJobUpdated?: (job: PatchJobItem) => void
}

export const PatchJobLogModal: React.FC<Props> = ({ jobId, onClose, onJobUpdated }) => {
  const { apiClient } = useAuth()
  const [job, setJob] = useState<PatchJobItem | null>(null)
  const [logs, setLogs] = useState<PatchJobLogLine[]>([])
  const [phase, setPhase] = useState<string | null>(null)
  const [cancelling, setCancelling] = useState(false)
  const [errorMsg, setErrorMsg] = useState<string | null>(null)
  const logContainerRef = useRef<HTMLDivElement | null>(null)
  const lastSeqRef = useRef<number>(0)

  const refreshJobDetail = useCallback(async () => {
    try {
      const data = await apiClient<PatchJobDetail>(`/api/patch-jobs/${jobId}`)
      setJob(data.job)
      onJobUpdated?.(data.job)
      if (data.logs && data.logs.length > 0) {
        setLogs(data.logs)
        const maxSeq = data.logs[data.logs.length - 1].seq
        if (maxSeq > lastSeqRef.current) {
          lastSeqRef.current = maxSeq
        }
      }
      return data.job
    } catch (err) {
      setErrorMsg(err instanceof Error ? err.message : 'Failed to load patch job detail')
      return null
    }
  }, [apiClient, jobId, onJobUpdated])

  useEffect(() => {
    let evtSource: EventSource | null = null
    let cancelled = false

    const startStream = async () => {
      const currentJob = await refreshJobDetail()
      if (cancelled || !currentJob) return

      if (currentJob.status !== 'PENDING' && currentJob.status !== 'RUNNING') {
        return
      }

      try {
        const { ticket } = await apiClient<{ ticket: string; expiresAt: string }>(
          '/api/auth/ticket',
          {
            method: 'POST',
            body: JSON.stringify({ purpose: 'sse-patch-job', resourceId: jobId })
          }
        )
        if (cancelled) return

        const url = `/api/patch-jobs/${jobId}/stream?ticket=${encodeURIComponent(ticket)}&fromSeq=${lastSeqRef.current}`
        evtSource = new EventSource(url)

        evtSource.onmessage = (event) => {
          try {
            const payload = JSON.parse(event.data)
            if (payload.type === 'log' && typeof payload.seq === 'number') {
              if (payload.seq > lastSeqRef.current) {
                lastSeqRef.current = payload.seq
                setLogs((prev) => [
                  ...prev,
                  {
                    seq: payload.seq,
                    stream: payload.stream || 'stdout',
                    line: payload.line || '',
                    createdAt: new Date().toISOString()
                  }
                ])
              }
            } else if (payload.type === 'phase' && payload.phase) {
              setPhase(payload.phase)
            } else if (payload.type === 'result') {
              void refreshJobDetail()
              evtSource?.close()
            }
          } catch {
            // ignore non-json comments/heartbeats
          }
        }

        evtSource.onerror = () => {
          evtSource?.close()
          void refreshJobDetail()
        }
      } catch (err) {
        setErrorMsg(err instanceof Error ? err.message : 'Failed to connect live log stream')
      }
    }

    void startStream()

    return () => {
      cancelled = true
      evtSource?.close()
    }
  }, [apiClient, jobId, refreshJobDetail])

  useEffect(() => {
    if (logContainerRef.current) {
      logContainerRef.current.scrollTop = logContainerRef.current.scrollHeight
    }
  }, [logs])

  const handleCancel = async () => {
    setCancelling(true)
    setErrorMsg(null)
    try {
      const updated = await apiClient<PatchJobItem>(`/api/patch-jobs/${jobId}/cancel`, {
        method: 'POST'
      })
      setJob(updated)
      onJobUpdated?.(updated)
    } catch (err) {
      setErrorMsg(err instanceof Error ? err.message : 'Failed to cancel patch job')
    } finally {
      setCancelling(false)
    }
  }

  const isRunning = job?.status === 'PENDING' || job?.status === 'RUNNING'

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 backdrop-blur-sm p-4">
      <div className="bg-bg-surface rounded-2xl w-full max-w-4xl shadow-2xl border border-border-subtle flex flex-col max-h-[85vh]">
        <div className="p-5 border-b border-border-subtle flex items-center justify-between gap-4">
          <div className="flex items-center gap-3">
            <Terminal className="w-5 h-5 text-accent-primary" />
            <div>
              <div className="flex items-center gap-2.5">
                <h3 className="font-bold text-base">
                  Patch Execution Log ({job?.mode || 'LOADING'})
                </h3>
                {job && (
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
                )}
                {phase && isRunning && (
                  <span className="text-[11px] font-mono text-text-secondary">
                    Phase: {phase}
                  </span>
                )}
              </div>
              <p className="text-xs text-text-secondary mt-0.5 font-mono">
                Job ID: {jobId}
                {job?.credentialUsername && ` • User: ${job.credentialUsername}`}
                {job?.usedFallback && ' (fallback credential)'}
              </p>
            </div>
          </div>

          <div className="flex items-center gap-2">
            {isRunning && (
              <button
                type="button"
                onClick={handleCancel}
                disabled={cancelling}
                className="px-3 py-1.5 rounded-lg bg-accent-danger text-white text-xs font-semibold hover:bg-accent-danger/90 flex items-center gap-1.5 cursor-pointer disabled:opacity-50"
              >
                <Square className="w-3.5 h-3.5" />
                {cancelling ? 'Cancelling...' : 'Cancel Execution'}
              </button>
            )}
            <button
              type="button"
              onClick={onClose}
              className="text-text-muted hover:text-text-primary p-1 cursor-pointer"
              aria-label="Close modal"
            >
              <X className="w-5 h-5" />
            </button>
          </div>
        </div>

        {errorMsg && (
          <div className="mx-5 mt-4 p-3 rounded-xl bg-accent-danger/10 border border-accent-danger/30 text-accent-danger text-xs font-semibold">
            {errorMsg}
          </div>
        )}

        {job?.errorMessage && (
          <div className="mx-5 mt-4 p-3 rounded-xl bg-accent-danger/10 border border-accent-danger/30 text-accent-danger text-xs font-semibold flex items-center gap-2">
            <XCircle className="w-4 h-4 shrink-0" />
            <span>
              {job.errorCode ? `[${job.errorCode}] ` : ''}
              {job.errorMessage}
            </span>
          </div>
        )}

        {job?.rebootRequired && (
          <div className="mx-5 mt-4 p-3 rounded-xl bg-accent-warning/15 border border-accent-warning/40 text-accent-warning text-xs font-semibold flex items-center gap-2">
            <AlertTriangle className="w-4 h-4 shrink-0" />
            <span>Host reported that a system reboot is required to complete this update.</span>
          </div>
        )}

        <div
          ref={logContainerRef}
          className="flex-1 m-5 p-4 rounded-xl bg-bg-base border border-border-subtle font-mono text-xs overflow-y-auto space-y-1 min-h-[300px]"
        >
          {logs.length === 0 ? (
            <div className="text-text-muted flex items-center gap-2 py-4">
              {isRunning && <Loader2 className="w-4 h-4 animate-spin" />}
              <span>{isRunning ? 'Waiting for remote stdout/stderr...' : 'No console output recorded.'}</span>
            </div>
          ) : (
            logs.map((entry) => (
              <div
                key={entry.seq}
                className={`whitespace-pre-wrap break-all leading-relaxed ${
                  entry.stream === 'stderr'
                    ? 'text-accent-warning'
                    : entry.stream === 'system'
                    ? 'text-accent-primary font-semibold'
                    : 'text-text-primary'
                }`}
              >
                <span className="text-text-muted select-none mr-3 inline-block w-8 text-right">
                  {entry.seq}
                </span>
                {entry.line}
              </div>
            ))
          )}
        </div>

        <div className="px-5 py-3.5 border-t border-border-subtle flex items-center justify-between text-xs text-text-secondary">
          <div className="flex items-center gap-2">
            {isRunning ? (
              <>
                <Loader2 className="w-3.5 h-3.5 animate-spin text-accent-primary" />
                <span>Streaming live execution output via SSE...</span>
              </>
            ) : job?.status === 'COMPLETED' ? (
              <>
                <CheckCircle2 className="w-4 h-4 text-accent-success" />
                <span>Execution finished and post-patch inventory re-synced.</span>
              </>
            ) : (
              <span>Execution ended ({job?.status || 'UNKNOWN'}).</span>
            )}
          </div>
          <button
            type="button"
            onClick={onClose}
            className="px-4 py-1.5 rounded-lg border border-border-subtle bg-bg-surface-raised text-xs font-semibold text-text-primary hover:border-accent-primary cursor-pointer"
          >
            Close
          </button>
        </div>
      </div>
    </div>
  )
}

