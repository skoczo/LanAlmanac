import React, { useState } from 'react'
import { Link, useNavigate } from '@tanstack/react-router'
import { useAuth } from '../lib/auth/auth-context'
import {
  LayoutDashboard,
  Server,
  Network,
  KeyRound,
  Settings,
  LogOut,
  Bell,
  Search,
  User,
  ShieldAlert,
  ChevronLeft,
  ChevronRight,
  Wifi
} from 'lucide-react'

export const Layout: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const { user, logout } = useAuth()
  const [isCollapsed, setIsCollapsed] = useState(false)
  const [searchTerm, setSearchTerm] = useState('')
  const navigate = useNavigate()

  const handleLogout = () => {
    logout()
    navigate({ to: '/login' })
  }

  const menuItems = [
    { name: 'Dashboard', icon: LayoutDashboard, path: '/' },
    { name: 'Devices', icon: Server, path: '/devices' },
    { name: 'Network Map', icon: Network, path: '/topology' },
    { name: 'Vault', icon: KeyRound, path: '/vault' },
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
                GNM Core
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
            <button className="p-2 rounded-xl bg-bg-surface border border-border-subtle hover:bg-bg-surface-raised text-text-secondary hover:text-text-primary transition-all relative cursor-pointer group">
              <Bell className="w-4.5 h-4.5 group-hover:rotate-12 transition-transform" />
              <span className="absolute top-1 right-1 w-2 h-2 rounded-full bg-accent-warning animate-pulse" />
            </button>
          </div>
        </header>

        {/* Dynamic Route Content */}
        <main className="flex-1 overflow-y-auto p-8 relative">
          {children}
        </main>
      </div>
    </div>
  )
}
