import React, { createContext, useContext, useState, useEffect } from 'react'

interface User {
  username: string
  roles: string[]
}

interface AuthContextType {
  token: string | null
  user: User | null
  isAuthenticated: boolean
  isLoading: boolean
  login: (token: string, username: string, roles: string[]) => void
  logout: () => void
  apiClient: <T>(url: string, options?: RequestInit) => Promise<T>
}

const AuthContext = createContext<AuthContextType | undefined>(undefined)

export const AuthProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [token, setToken] = useState<string | null>(null)
  const [user, setUser] = useState<User | null>(null)
  const [isLoading, setIsLoading] = useState(true)

  useEffect(() => {
    // Hydrate token on mount
    const savedToken = localStorage.getItem('gnm_token')
    const savedUsername = localStorage.getItem('gnm_username')
    const savedRoles = localStorage.getItem('gnm_roles')

    if (savedToken && savedUsername && savedRoles) {
      document.cookie = `jwt=${savedToken}; path=/; SameSite=Lax;`
      setToken(savedToken)
      setUser({
        username: savedUsername,
        roles: JSON.parse(savedRoles)
      })
    }
    setIsLoading(false)
  }, [])

  const login = (newToken: string, username: string, roles: string[]) => {
    localStorage.setItem('gnm_token', newToken)
    localStorage.setItem('gnm_username', username)
    localStorage.setItem('gnm_roles', JSON.stringify(roles))
    document.cookie = `jwt=${newToken}; path=/; SameSite=Lax;`
    setToken(newToken)
    setUser({ username, roles })
  }

  const logout = () => {
    localStorage.removeItem('gnm_token')
    localStorage.removeItem('gnm_username')
    localStorage.removeItem('gnm_roles')
    document.cookie = `jwt=; Max-Age=0; path=/; SameSite=Lax;`
    setToken(null)
    setUser(null)
  }

  const apiClient = async <T,>(url: string, options: RequestInit = {}): Promise<T> => {
    const headers = new Headers(options.headers || {})
    const activeToken = token || localStorage.getItem('gnm_token')
    
    if (activeToken) {
      headers.set('Authorization', `Bearer ${activeToken}`)
    }
    
    headers.set('Content-Type', 'application/json')

    const response = await fetch(url, {
      ...options,
      headers
    })

    if (response.status === 401) {
      logout()
      window.location.href = '/login'
      throw new Error('Unauthorized. Redirecting to login.')
    }

    if (!response.ok) {
      const errorMsg = await response.text()
      throw new Error(errorMsg || `Request failed with status ${response.status}`)
    }

    return response.json() as Promise<T>
  }

  const value: AuthContextType = {
    token,
    user,
    isAuthenticated: !!token,
    isLoading,
    login,
    logout,
    apiClient
  }

  return (
    <AuthContext.Provider value={value}>
      {!isLoading && children}
    </AuthContext.Provider>
  )
}

export const useAuth = () => {
  const context = useContext(AuthContext)
  if (context === undefined) {
    throw new Error('useAuth must be used within an AuthProvider')
  }
  return context
}
