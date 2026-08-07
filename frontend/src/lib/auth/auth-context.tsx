import React, { createContext, useContext, useState, useEffect } from 'react'
import { UserManager, WebStorageStateStore } from 'oidc-client-ts'

interface User {
  username: string
  roles: string[]
}

interface PublicOidcConfig {
  enabled: string
  authority: string
  clientId: string
}

interface AuthContextType {
  token: string | null
  user: User | null
  isAuthenticated: boolean
  isLoading: boolean
  isOidcEnabled: boolean
  login: (token: string, username: string, roles: string[]) => void
  logout: () => void
  oidcLogin: () => Promise<void>
  apiClient: <T>(url: string, options?: RequestInit) => Promise<T>
}

const AuthContext = createContext<AuthContextType | undefined>(undefined)

export const AuthProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [token, setToken] = useState<string | null>(null)
  const [user, setUser] = useState<User | null>(null)
  const [isLoading, setIsLoading] = useState(true)
  const [oidcConfig, setOidcConfig] = useState<PublicOidcConfig | null>(null)
  const [userManager, setUserManager] = useState<UserManager | null>(null)

  useEffect(() => {
    const init = async () => {
      let oidcHandled = false;
      // 1. Fetch OIDC Config
      try {
        const res = await fetch('/api/settings/public/oidc')
        if (res.ok) {
          const config = await res.json()
          setOidcConfig(config)
          
          if (config.enabled === 'true' && config.authority && config.clientId) {
            const um = new UserManager({
              authority: config.authority,
              client_id: config.clientId,
              redirect_uri: window.location.origin + '/login', // redirect back to login to process callback
              post_logout_redirect_uri: window.location.origin + '/login',
              response_type: 'code',
              scope: 'openid profile email',
              userStore: new WebStorageStateStore({ store: window.localStorage })
            })
            setUserManager(um)
            
            // Check for OIDC callback
            if (window.location.search.includes('code=') && window.location.search.includes('state=')) {
              try {
                const cbUser = await um.signinCallback()
                window.history.replaceState({}, document.title, window.location.pathname)
                
                if (cbUser) {
                  // Set token
                  setToken(cbUser.access_token)
                  setUser({
                    username: cbUser.profile?.preferred_username || cbUser.profile?.name || 'OIDC User',
                    roles: ['gnm-admin'] // Defaulting to admin for simplicity, should map from token
                  })
                  document.cookie = `jwt=${cbUser.access_token}; path=/; SameSite=Lax;`
                }
                setIsLoading(false)
                oidcHandled = true
              } catch (e) {
                console.error('Error processing OIDC callback', e)
              }
            } else {
              // Try to load existing OIDC user
              const oidcUser = await um.getUser()
              if (oidcUser && !oidcUser.expired) {
                setToken(oidcUser.access_token)
                setUser({
                  username: oidcUser.profile?.preferred_username || oidcUser.profile?.name || 'OIDC User',
                  roles: ['gnm-admin']
                })
                document.cookie = `jwt=${oidcUser.access_token}; path=/; SameSite=Lax;`
                setIsLoading(false)
                oidcHandled = true
              }
            }
          }
        }
      } catch (e) {
        console.error('Failed to load OIDC config', e)
      }

      if (oidcHandled) return;

      // 2. Hydrate local token if OIDC didn't take over
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
    }

    init()
  }, [])

  const login = (newToken: string, username: string, roles: string[]) => {
    localStorage.setItem('gnm_token', newToken)
    localStorage.setItem('gnm_username', username)
    localStorage.setItem('gnm_roles', JSON.stringify(roles))
    document.cookie = `jwt=${newToken}; path=/; SameSite=Lax;`
    setToken(newToken)
    setUser({ username, roles })
  }

  const oidcLogin = async () => {
    if (userManager) {
      await userManager.signinRedirect()
    }
  }

  const logout = async () => {
    if (userManager) {
      const user = await userManager.getUser()
      if (user) {
        await userManager.signoutRedirect()
        return
      }
    }
    
    localStorage.removeItem('gnm_token')
    localStorage.removeItem('gnm_username')
    localStorage.removeItem('gnm_roles')
    document.cookie = `jwt=; Max-Age=0; path=/; SameSite=Lax;`
    setToken(null)
    setUser(null)
  }

  const apiClient = async <T,>(url: string, options: RequestInit = {}): Promise<T> => {
    const headers = new Headers(options.headers || {})
    
    let activeToken = token
    if (!activeToken) {
        if (userManager) {
            const u = await userManager.getUser()
            if (u && !u.expired) {
                activeToken = u.access_token
            }
        }
        if (!activeToken) {
            activeToken = localStorage.getItem('gnm_token')
        }
    }
    
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
      if (!window.location.pathname.includes('/login')) {
         window.location.href = '/login'
      }
      throw new Error('Unauthorized. Redirecting to login.')
    }

    if (!response.ok) {
      const errorMsg = await response.text()
      throw new Error(errorMsg || `Request failed with status ${response.status}`)
    }

    const text = await response.text();
    if (!text) {
        return {} as T;
    }
    return JSON.parse(text) as T;
  }

  const value: AuthContextType = {
    token,
    user,
    isAuthenticated: !!token,
    isLoading,
    isOidcEnabled: oidcConfig?.enabled === 'true',
    login,
    logout,
    oidcLogin,
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
