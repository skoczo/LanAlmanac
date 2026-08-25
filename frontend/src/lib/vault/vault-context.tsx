import React, { createContext, useContext, useState, useEffect } from 'react'
import { useAuth } from '../auth/auth-context'

interface VaultStatus {
  initialized: boolean
  sealed: boolean
  autoUnsealEnabled: boolean
}

interface VaultContextType {
  initialized: boolean
  sealed: boolean
  autoUnsealEnabled: boolean
  showUnsealModal: boolean
  setShowUnsealModal: (show: boolean) => void
  refreshStatus: () => Promise<void>
  setLocalStatus: (initialized: boolean, sealed: boolean) => void
}

const VaultContext = createContext<VaultContextType | undefined>(undefined)

export const VaultProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const { apiClient, isAuthenticated } = useAuth()
  const [initialized, setInitialized] = useState(false)
  const [sealed, setSealed] = useState(true)
  const [autoUnsealEnabled, setAutoUnsealEnabled] = useState(false)
  const [showUnsealModal, setShowUnsealModal] = useState(false)

  const refreshStatus = async () => {
    if (!isAuthenticated) return
    try {
      const res = await apiClient<VaultStatus>('/api/vault/status')
      setInitialized(res.initialized)
      setSealed(res.sealed)
      setAutoUnsealEnabled(res.autoUnsealEnabled ?? false)
    } catch (e) {
      console.error("Failed to fetch vault status", e)
    }
  }

  useEffect(() => {
    refreshStatus()
  }, [isAuthenticated])

  return (
    <VaultContext.Provider value={{
      initialized,
      sealed,
      autoUnsealEnabled,
      showUnsealModal,
      setShowUnsealModal,
      refreshStatus,
      setLocalStatus: (init, sl) => {
        setInitialized(init)
        setSealed(sl)
      }
    }}>
      {children}
    </VaultContext.Provider>
  )
}

export const useVault = () => {
  const ctx = useContext(VaultContext)
  if (!ctx) throw new Error("useVault must be used within VaultProvider")
  return ctx
}
