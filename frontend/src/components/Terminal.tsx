import React, { useEffect, useRef, useState } from 'react'
import { Terminal as XTerm } from 'xterm'
import { FitAddon } from 'xterm-addon-fit'
import 'xterm/css/xterm.css'

interface TerminalProps {
  deviceId: string
  credentialId: string
  onClose: () => void
}

export const Terminal: React.FC<TerminalProps> = ({ deviceId, credentialId, onClose }) => {
  const terminalRef = useRef<HTMLDivElement>(null)
  const xtermRef = useRef<XTerm | null>(null)
  const fitAddonRef = useRef<FitAddon | null>(null)
  const wsRef = useRef<WebSocket | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!terminalRef.current) return

    let isDisposed = false;
    let ws: WebSocket | null = null

    // Initialize xterm.js
    const term = new XTerm({
      cursorBlink: true,
      fontFamily: 'JetBrains Mono, monospace',
      fontSize: 13,
      theme: {
        background: '#0a0e1a', // var(--bg-base)
        foreground: '#f1f5f9', // var(--text-primary)
        cursor: '#3b82f6',     // var(--accent-primary)
        selectionBackground: 'rgba(59, 130, 246, 0.3)',
      }
    })
    
    const fitAddon = new FitAddon()
    term.loadAddon(fitAddon)
    
    xtermRef.current = term
    fitAddonRef.current = fitAddon

    // Defer open to prevent xterm React 18 Strict Mode crash
    const timeoutId = setTimeout(() => {
      if (isDisposed || !terminalRef.current) return
      term.open(terminalRef.current)
      
      // Use ResizeObserver to reliably fit when dimensions are available
      const resizeObserver = new ResizeObserver(() => {
        if (!isDisposed && terminalRef.current && terminalRef.current.offsetWidth > 0) {
          try {
            fitAddon.fit()
          } catch (e) {
            // Ignore fit errors during transitions
          }
        }
      })
      resizeObserver.observe(terminalRef.current)
      
      // Save observer to clean it up
      ;(term as any)._resizeObserver = resizeObserver

      // Connect WebSocket inside the timeout to avoid Strict Mode double-connections
      const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
      const host = window.location.host
      const wsUrl = `${protocol}//${host}/ws/terminal/${deviceId}/${credentialId}`
      
      ws = new WebSocket(wsUrl)
      wsRef.current = ws

      ws.onopen = () => {
        if (!isDisposed) term.focus()
      }

      ws.onmessage = (event) => {
        term.write(event.data)
      }

      ws.onerror = () => {
        setError('WebSocket connection error')
      }

      ws.onclose = () => {
        term.write('\r\n\x1b[31m[Connection Closed]\x1b[0m\r\n')
      }

      // Bridge user input to WebSocket
      term.onData((data) => {
        if (ws && ws.readyState === WebSocket.OPEN) {
          ws.send(JSON.stringify({ type: 'input', data }))
        }
      })
    }, 50)

    const handleResize = () => {
      if (fitAddonRef.current) {
        fitAddonRef.current.fit()
        if (ws && ws.readyState === WebSocket.OPEN) {
          ws.send(JSON.stringify({ type: 'resize', cols: term.cols, rows: term.rows }))
        }
      }
    }

    window.addEventListener('resize', handleResize)

    return () => {
      isDisposed = true
      clearTimeout(timeoutId)
      window.removeEventListener('resize', handleResize)
      if ((term as any)._resizeObserver) {
        (term as any)._resizeObserver.disconnect()
      }
      if (ws) {
        ws.close()
      }
      term.dispose()
    }
  }, [deviceId, credentialId])

  return (
    <div className="flex flex-col h-full bg-bg-base border border-border-subtle rounded-xl overflow-hidden shadow-2xl">
      <div className="flex items-center justify-between px-4 py-2 bg-bg-surface-raised border-b border-border-subtle">
        <div className="flex items-center gap-2">
          <div className="flex gap-1.5">
            <div className="w-3 h-3 rounded-full bg-accent-danger cursor-pointer hover:opacity-80" onClick={onClose} />
            <div className="w-3 h-3 rounded-full bg-accent-warning" />
            <div className="w-3 h-3 rounded-full bg-accent-success" />
          </div>
          <span className="text-xs font-mono text-text-secondary ml-2">Terminal Session</span>
        </div>
        {error && <span className="text-xs text-accent-danger">{error}</span>}
      </div>
      <div className="flex-1 p-2 overflow-hidden" ref={terminalRef} />
    </div>
  )
}
