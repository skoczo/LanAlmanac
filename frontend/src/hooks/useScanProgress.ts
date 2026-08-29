import { useState, useEffect } from 'react'
import { useAuth } from '../lib/auth/auth-context'

export interface ScanProgress {
  queuedDevices: number
  activeScans: number
  currentlyScanningIPs: string[]
  totalScanned: number
}

export const useScanProgress = (pollIntervalMs = 5000) => {
  const { apiClient } = useAuth()
  const [progress, setProgress] = useState<ScanProgress | null>(null)
  const [error, setError] = useState<Error | null>(null)

  useEffect(() => {
    let isMounted = true

    const fetchProgress = async () => {
      try {
        const data = await apiClient<ScanProgress>('/api/scanner/progress')
        if (isMounted) {
          setProgress(data)
          setError(null)
        }
      } catch (err: any) {
        if (isMounted) {
          setError(err)
        }
      }
    }

    fetchProgress() // Initial fetch
    const interval = setInterval(fetchProgress, pollIntervalMs)

    return () => {
      isMounted = false
      clearInterval(interval)
    }
  }, [apiClient, pollIntervalMs])

  return { progress, error }
}
