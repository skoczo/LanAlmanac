import React, { useEffect, useRef, useState } from 'react'
import { Terminal as XTerm } from 'xterm'
import { FitAddon } from 'xterm-addon-fit'
import { WebglAddon } from 'xterm-addon-webgl'
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
    
    term.open(terminalRef.current)
    
    try {
      const webglAddon = new WebglAddon()
      webglAddon.onContextLoss(e => {
        webglAddon.dispose()
      })
      term.loadAddon(webglAddon)
    } catch (e) {
      console.warn('WebGL addon could not be loaded, falling back to canvas', e)
    }
    
    fitAddon.fit()

    xtermRef.current = term
    fitAddonRef.current = fitAddon

    // Connect WebSocket
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
    const host = window.location.host // In dev, we might need proxy or direct
    const wsUrl = `${protocol}//${host}/ws/terminal/${deviceId}/${credentialId}`
    
    const ws = new WebSocket(wsUrl)
    wsRef.current = ws

    ws.onopen = () => {
      term.focus()
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
      if (ws.readyState === WebSocket.OPEN) {
        ws.send(data)
      }
    })

    const handleResize = () => {
      if (fitAddonRef.current) {
        fitAddonRef.current.fit()
        // In a full implementation, we'd also send the resize event to the SSH server
        // e.g., ws.send(JSON.stringify({ type: 'resize', cols: term.cols, rows: term.rows }))
      }
    }

    window.addEventListener('resize', handleResize)

    return () => {
      window.removeEventListener('resize', handleResize)
      ws.close()
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
