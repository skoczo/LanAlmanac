import React, { useState, useEffect } from 'react'
import { Link, useNavigate } from '@tanstack/react-router'
import { useAuth } from '../lib/auth/auth-context'
import {
  LayoutDashboard,
  Server,
  Network,
  KeyRound,
  LogOut,
  Bell,
  Search,
  ChevronLeft,
  ChevronRight,
  Wifi,
  X,
  ShieldAlert,
  Settings
} from 'lucide-react'
import { VaultUnsealModal } from './VaultUnsealModal'

interface ToastMessage {
  id: string
  type: string
  title: string
  text: string
}

export const Layout: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const { user, logout } = useAuth()
  const [isCollapsed, setIsCollapsed] = useState(false)
  const [searchTerm, setSearchTerm] = useState('')
  const [toasts, setToasts] = useState<ToastMessage[]>([])
  const [notificationHistory, setNotificationHistory] = useState<ToastMessage[]>([])
  const [showNotifications, setShowNotifications] = useState(false)
  const navigate = useNavigate()

  useEffect(() => {
    // Determine WebSocket URL dynamically (using same hostname/port)
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
    const host = window.location.host
    const wsUrl = `${protocol}//${host}/ws/events`
    
    LOG_ws_connect(wsUrl)
    
    let ws: WebSocket
    let reconnectTimer: any

    const connect = () => {
      ws = new WebSocket(wsUrl)

      ws.onmessage = (event) => {
        try {
          const data = JSON.parse(event.data)
          const isAlarm = data.type === 'ALARM';
          const newToast: ToastMessage = {
            id: Math.random().toString(),
            type: data.type,
            title: isAlarm ? 'Security Alert' : (data.type === 'NEW_DEVICE' ? 'New Host Discovered' : 'Status Update'),
            text: isAlarm ? data.message : `${data.displayName} (${data.ipAddress}) is now ${data.status}`
          }
          
          setToasts((prev) => {
            // Deduplicate identical toasts (e.g. from React Strict Mode double-connections)
            if (prev.some(t => t.text === newToast.text && t.type === newToast.type)) {
              return prev;
            }
            return [...prev, newToast];
          })
          setNotificationHistory((prev) => {
            if (prev.some(t => t.text === newToast.text && t.type === newToast.type)) {
              return prev;
            }
            return [newToast, ...prev].slice(0, 50); // Keep last 50
          })

          // Configurable timeout: 15 seconds for alarms, 4.5 seconds for status updates
          const timeoutDuration = isAlarm ? 15000 : 4500;
          setTimeout(() => {
            setToasts((prev) => prev.filter((t) => t.id !== newToast.id))
          }, timeoutDuration)
        } catch (err) {
          console.error('Failed to parse WebSocket message', err)
        }
      }

      ws.onclose = () => {
        // Attempt reconnect after 3 seconds if disconnected
        reconnectTimer = setTimeout(connect, 3000)
      }

      ws.onerror = (err) => {
        console.error('WebSocket error occurred', err)
      }
    }

    connect()

    return () => {
      if (ws) ws.close()
      clearTimeout(reconnectTimer)
    }
  }, [])

  const LOG_ws_connect = (url: string) => {
    console.log(`Connecting to events WebSocket stream: ${url}`)
  }

  const dismissToast = (id: string) => {
    setToasts((prev) => prev.filter((t) => t.id !== id))
  }

  const handleLogout = () => {
    logout()
    navigate({ to: '/login' })
  }

  const menuItems = [
    { name: 'Dashboard', icon: LayoutDashboard, path: '/' },
    { name: 'Devices', icon: Server, path: '/devices' },
    { name: 'Network Map', icon: Network, path: '/topology' },
    { name: 'Vault', icon: KeyRound, path: '/vault' },
    { name: 'Alerts', icon: ShieldAlert, path: '/alerts' },
    { name: 'Settings', icon: Settings, path: '/settings' },
  ]

  return (
    <div className="min-h-screen bg-bg-base text-text-primary flex relative">
      {/* Background gradients */}
      <div className="absolute top-0 right-0 w-96 h-96 bg-accent-primary/5 rounded-full blur-[120px] pointer-events-none" />
      <div className="absolute bottom-0 left-0 w-96 h-96 bg-accent-info/5 rounded-full blur-[120px] pointer-events-none" />

      {/* Sidebar */}
      <aside
        className={`glass-panel border-r border-border-subtle flex flex-col transition-all duration-300 z-20 ${
          isCollapsed ? 'w-20' : 'w-64'
        }`}
      >
        {/* Brand Logo */}
        <div className="h-16 border-b border-border-subtle flex items-center justify-between px-5">
          <div className="flex items-center gap-3 overflow-hidden">
            <div className="flex-shrink-0 w-8 h-8 rounded-lg bg-gradient-to-tr from-accent-primary to-accent-info flex items-center justify-center shadow-lg shadow-accent-primary/20">
              <Network className="w-5 h-5 text-text-primary" />
            </div>
            {!isCollapsed && (
              <span className="font-bold text-base tracking-wide bg-gradient-to-r from-text-primary to-text-secondary bg-clip-text text-transparent truncate">
                NetAlmanac
              </span>
            )}
          </div>
          <button
            onClick={() => setIsCollapsed(!isCollapsed)}
            className="p-1 rounded-md hover:bg-bg-surface-raised border border-border-subtle text-text-secondary hover:text-text-primary cursor-pointer hidden md:block"
          >
            {isCollapsed ? <ChevronRight className="w-4 h-4" /> : <ChevronLeft className="w-4 h-4" />}
          </button>
        </div>

        {/* Navigation */}
        <nav className="flex-1 py-6 px-4 space-y-1.5 overflow-y-auto">
          {menuItems.map((item) => (
            <Link
              key={item.path}
              to={item.path}
              activeProps={{
                className: 'bg-accent-primary/10 border-accent-primary/40 text-accent-primary shadow-sm shadow-accent-primary/5 glow-primary'
              }}
              inactiveProps={{
                className: 'hover:bg-bg-surface-raised border-transparent text-text-secondary hover:text-text-primary'
              }}
              className="flex items-center gap-3.5 px-4.5 py-3 rounded-xl border text-sm font-medium transition-all group cursor-pointer"
            >
              <item.icon className="w-5 h-5 flex-shrink-0 group-hover:scale-105 transition-transform" />
              {!isCollapsed && <span className="truncate">{item.name}</span>}
            </Link>
          ))}
        </nav>

        {/* Footer info / Logout */}
        <div className="p-4 border-t border-border-subtle space-y-2">
          {!isCollapsed && user && (
            <div className="px-3.5 py-2.5 rounded-xl bg-bg-surface-raised border border-border-subtle flex items-center gap-3">
              <div className="w-8 h-8 rounded-full bg-accent-primary/10 border border-accent-primary/20 flex items-center justify-center text-accent-primary font-semibold text-sm">
                {user.username.charAt(0).toUpperCase()}
              </div>
              <div className="flex-1 min-w-0">
                <p className="text-xs font-semibold text-text-primary truncate">{user.username}</p>
                <p className="text-[10px] text-text-muted uppercase tracking-wider font-semibold">
                  Administrator
                </p>
              </div>
            </div>
          )}

          <button
            onClick={handleLogout}
            className="w-full flex items-center gap-3.5 px-4.5 py-3 rounded-xl border border-transparent text-sm font-medium text-accent-danger hover:bg-accent-danger/5 hover:border-accent-danger/20 transition-all cursor-pointer group"
          >
            <LogOut className="w-5 h-5 flex-shrink-0 group-hover:translate-x-0.5 transition-transform" />
            {!isCollapsed && <span>Sign Out</span>}
          </button>
        </div>
      </aside>

      {/* Main Content Area */}
      <div className="flex-1 flex flex-col min-w-0 h-screen overflow-hidden">
        {/* Header */}
        <header className="h-16 border-b border-border-subtle bg-bg-glass backdrop-blur-md flex items-center justify-between px-6 z-10">
          <div className="flex items-center gap-4 flex-1 max-w-lg">
            <div className="relative w-full">
              <div className="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none text-text-muted">
                <Search className="w-4 h-4" />
              </div>
              <input
                type="text"
                value={searchTerm}
                onChange={(e) => setSearchTerm(e.target.value)}
                className="w-full bg-bg-surface border border-border-subtle rounded-xl py-2 pl-9 pr-4 text-xs text-text-primary placeholder:text-text-muted focus:outline-none focus:border-accent-primary transition-colors"
                placeholder="Search devices, IPs, services... (Ctrl+K)"
              />
            </div>
          </div>

          <div className="flex items-center gap-4">
            {/* System Status */}
            <div className="flex items-center gap-1.5 px-3 py-1.5 rounded-full bg-accent-success/5 border border-accent-success/20 text-accent-success text-[10px] font-bold tracking-wider uppercase select-none glow-success">
              <Wifi className="w-3.5 h-3.5 animate-pulse-slow" />
              <span>Core Online</span>
            </div>

            {/* Notification Bell */}
            <div className="relative">
              <button 
                onClick={() => setShowNotifications(!showNotifications)}
                className="p-2 rounded-xl bg-bg-surface border border-border-subtle hover:bg-bg-surface-raised text-text-secondary hover:text-text-primary transition-all relative cursor-pointer group"
              >
                <Bell className="w-4.5 h-4.5 group-hover:rotate-12 transition-transform" />
                {notificationHistory.length > 0 && (
                  <span className="absolute top-1 right-1 w-2 h-2 rounded-full bg-accent-warning animate-pulse" />
                )}
              </button>

              {/* Notifications Dropdown */}
              {showNotifications && (
                <div className="absolute top-full right-0 mt-3 w-80 bg-bg-surface/95 backdrop-blur-md border border-border-subtle rounded-2xl shadow-2xl z-50 overflow-hidden flex flex-col max-h-[400px] animate-fade-in">
                  <div className="p-3 border-b border-border-subtle flex justify-between items-center bg-bg-surface-raised">
                    <h3 className="text-xs font-bold uppercase tracking-wider text-text-primary">Notifications</h3>
                    <button 
                      onClick={() => setNotificationHistory([])}
                      className="text-[10px] font-semibold text-text-muted hover:text-accent-danger transition-colors cursor-pointer"
                    >
                      Clear All
                    </button>
                  </div>
                  <div className="overflow-y-auto flex-1 p-2 space-y-2">
                    {notificationHistory.length === 0 ? (
                      <p className="text-xs text-text-muted text-center py-6">No notifications</p>
                    ) : (
                      notificationHistory.map(notif => (
                        <div key={notif.id} className="p-3 rounded-xl bg-bg-base border border-border-subtle hover:border-accent-primary/30 transition-colors">
                          <div className="flex gap-3 items-start">
                            <div className={`w-2 h-2 rounded-full mt-1.5 flex-shrink-0 ${
                              notif.type === 'ALARM' ? 'bg-accent-danger' : 
                              notif.type === 'NEW_DEVICE' ? 'bg-accent-primary' : 'bg-accent-info'
                            }`} />
                            <div>
                              <h4 className="text-xs font-bold text-text-primary">{notif.title}</h4>
                              <p className="text-xs text-text-secondary mt-1">{notif.text}</p>
                            </div>
                          </div>
                        </div>
                      ))
                    )}
                  </div>
                </div>
              )}
            </div>
          </div>
        </header>

        {/* Dynamic Route Content */}
        <main className="flex-1 overflow-y-auto p-8 relative">
          {children}
        </main>
      </div>

      {/* Floating Toast Notification Container */}
      <div className="fixed bottom-6 right-6 z-50 flex flex-col gap-3.5 max-w-sm w-full pointer-events-none">
        {toasts.map((toast) => (
          <div
            key={toast.id}
            className={`pointer-events-auto p-4 rounded-xl border bg-bg-surface/85 backdrop-blur-md shadow-2xl flex gap-3.5 items-start animate-fade-in transition-all duration-300 ${
              toast.type === 'ALARM'
                ? 'border-accent-danger/30 hover:border-accent-danger/50 shadow-accent-danger/10'
                : toast.type === 'NEW_DEVICE'
                ? 'border-accent-primary/20 hover:border-accent-primary/40 shadow-accent-primary/5'
                : 'border-accent-info/20 hover:border-accent-info/40 shadow-accent-info/5'
            }`}
          >
            <div
              className={`w-2 h-2 rounded-full mt-1.5 animate-pulse flex-shrink-0 ${
                toast.type === 'ALARM' ? 'bg-accent-danger'
                : toast.type === 'NEW_DEVICE' ? 'bg-accent-primary' : 'bg-accent-info'
              }`}
            />
            <div className="flex-1 min-w-0">
              <h4 className="text-xs font-bold text-text-primary tracking-wide">
                {toast.title}
              </h4>
              <p className="text-sm text-text-secondary mt-1 leading-snug line-clamp-2 break-all">
                {toast.text}
              </p>
              {toast.type === 'ALARM' && (
                <Link to="/alerts" className="text-accent-danger text-xs font-bold mt-2 inline-block hover:underline cursor-pointer">
                  View Alert Details &rarr;
                </Link>
              )}
            </div>
            <button
              onClick={() => dismissToast(toast.id)}
              className="p-1 rounded-md hover:bg-bg-surface-raised text-text-muted hover:text-text-primary cursor-pointer transition-colors"
            >
              <X className="w-3.5 h-3.5" />
            </button>
          </div>
        ))}
      </div>
      
      <VaultUnsealModal />
    </div>
  )
}
