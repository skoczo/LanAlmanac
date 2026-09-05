import { useState } from 'react'
import { Download, Upload, AlertTriangle, Loader2 } from 'lucide-react'

export const BackupSettingsTab = () => {
  const [backupPassword, setBackupPassword] = useState('')
  const [restorePassword, setRestorePassword] = useState('')
  const [file, setFile] = useState<File | null>(null)
  const [isRestoring, setIsRestoring] = useState(false)
  const [restoreMessage, setRestoreMessage] = useState<string | null>(null)

  const handleBackup = () => {
    if (!backupPassword) {
      alert('Please enter a password for the backup.')
      return
    }
    // API client expects JSON by default, but this is a file download.
    // Easiest is to construct the URL and use window.open or a hidden anchor tag.
    const token = localStorage.getItem('jwt')
    const url = `/api/backup/download?password=${encodeURIComponent(backupPassword)}`
    
    // We can fetch it to include Auth headers
    fetch(url, {
      headers: {
        'Authorization': `Bearer ${token}`
      }
    })
    .then(response => {
      if (!response.ok) throw new Error('Backup failed')
      return response.blob()
    })
    .then(blob => {
      const downloadUrl = window.URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.style.display = 'none'
      a.href = downloadUrl
      // Get filename from response header if possible, or use a default
      a.download = `gnm_backup_${new Date().getTime()}.gnmbak`
      document.body.appendChild(a)
      a.click()
      window.URL.revokeObjectURL(downloadUrl)
    })
    .catch(err => {
      console.error(err)
      alert('Failed to download backup: ' + err.message)
    })
  }

  const handleRestore = async () => {
    if (!file) {
      alert('Please select a backup file.')
      return
    }
    if (!restorePassword) {
      alert('Please enter the backup password.')
      return
    }

    const confirmWipe = window.confirm(
      "WARNING: Restoring a backup will wipe all current system data and restart the application. Are you sure you want to proceed?"
    )
    if (!confirmWipe) return

    setIsRestoring(true)
    setRestoreMessage(null)

    const formData = new FormData()
    formData.append('file', file)
    formData.append('password', restorePassword)

    try {
      const response = await fetch('/api/backup/restore', {
        method: 'POST',
        headers: {
          'Authorization': `Bearer ${localStorage.getItem('jwt')}`
        },
        body: formData
      })

      if (!response.ok) {
        const errText = await response.text()
        throw new Error(errText)
      }

      setRestoreMessage("Restore initiated. The system will restart shortly. Please refresh the page in a minute.")
    } catch (err: any) {
      alert('Restore failed: ' + err.message)
    } finally {
      setIsRestoring(false)
    }
  }

  return (
    <div className="space-y-6">
      <div className="glass-panel rounded-2xl border border-border-subtle overflow-hidden">
        <div className="p-6 space-y-4">
          <h2 className="text-lg font-semibold text-text-primary flex items-center gap-2">
            <Download className="w-5 h-5 text-accent-info" />
            Create System Backup
          </h2>
          <p className="text-sm text-text-secondary">
            Download a full, encrypted backup of the system database and vault keys. 
            Store this file securely along with the password you choose.
          </p>
          <div className="flex flex-col md:flex-row gap-4 items-end">
            <div className="flex-1 w-full">
              <label className="block text-sm font-medium text-text-primary mb-1">Backup Password</label>
              <input
                type="password"
                value={backupPassword}
                onChange={e => setBackupPassword(e.target.value)}
                placeholder="Enter a strong password to encrypt the backup"
                className="w-full bg-bg-base border border-border-subtle rounded-lg px-3 py-2 text-sm text-text-primary focus:outline-none focus:border-accent-primary"
              />
            </div>
            <button
              onClick={handleBackup}
              disabled={!backupPassword}
              className="w-full md:w-auto px-4 py-2 bg-accent-primary text-bg-base rounded-lg text-sm font-semibold hover:bg-accent-primary/90 disabled:opacity-50 flex justify-center items-center gap-2"
            >
              <Download className="w-4 h-4" /> Download Backup
            </button>
          </div>
        </div>
      </div>

      <div className="glass-panel rounded-2xl border border-accent-danger/30 overflow-hidden">
        <div className="p-6 space-y-4">
          <h2 className="text-lg font-semibold text-accent-danger flex items-center gap-2">
            <Upload className="w-5 h-5" />
            Restore System
          </h2>
          <div className="p-3 bg-accent-danger/10 border border-accent-danger/20 rounded-lg flex gap-3 text-accent-danger text-sm">
            <AlertTriangle className="w-5 h-5 flex-shrink-0" />
            <p>
              <strong>Danger Zone:</strong> Restoring a backup will overwrite all current settings, devices, and credentials. 
              The application will automatically restart after a successful restore.
            </p>
          </div>
          
          <div className="space-y-4">
            <div>
              <label className="block text-sm font-medium text-text-primary mb-1">Backup File (.gnmbak)</label>
              <input
                type="file"
                accept=".gnmbak"
                onChange={e => setFile(e.target.files?.[0] || null)}
                className="w-full bg-bg-base border border-border-subtle rounded-lg px-3 py-2 text-sm text-text-primary focus:outline-none focus:border-accent-primary"
              />
            </div>
            <div>
              <label className="block text-sm font-medium text-text-primary mb-1">Decryption Password</label>
              <input
                type="password"
                value={restorePassword}
                onChange={e => setRestorePassword(e.target.value)}
                placeholder="Enter the password used to create this backup"
                className="w-full bg-bg-base border border-border-subtle rounded-lg px-3 py-2 text-sm text-text-primary focus:outline-none focus:border-accent-primary"
              />
            </div>
            
            <button
              onClick={handleRestore}
              disabled={!file || !restorePassword || isRestoring}
              className="w-full px-4 py-2 bg-accent-danger text-white rounded-lg text-sm font-semibold hover:bg-accent-danger/90 disabled:opacity-50 flex justify-center items-center gap-2"
            >
              {isRestoring ? <Loader2 className="w-4 h-4 animate-spin" /> : <Upload className="w-4 h-4" />}
              Restore System
            </button>

            {restoreMessage && (
              <p className="text-accent-success text-sm font-medium text-center">{restoreMessage}</p>
            )}
          </div>
        </div>
      </div>
    </div>
  )
}
