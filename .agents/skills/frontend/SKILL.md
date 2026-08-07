---
name: Frontend Development
description: Guidelines and stack for developing the React SPA frontend for GreatNetworkManager
---
# Frontend Guidelines

## Tech Stack
- **Framework**: React 19 + Vite + TypeScript 5.x
- **Styling**: Tailwind CSS 4 (custom dark theme)
- **UI Components**: Shadcn/ui (headless Radix UI Primitives)
- **Routing**: TanStack Router (file-based routing)
- **State Management**: TanStack Query (server state), Zustand (client/WebSocket state)
- **Visualization**: Recharts (charts), Cytoscape.js (topology map)
- **Terminal**: xterm.js (with WebGL addon)

## Design System & Aesthetics
- **Dark Mode Only**: The app is a premium dark-mode dashboard. Use the custom color palette tokens: `--bg-base`, `--bg-surface`, `--text-primary`, `--accent-primary`, etc.
- **Micro-animations**: Use subtle animations (150ms ease-out) on hover states, elevated cards, and status indicators (e.g., green pulsing dot for online devices).
- **Glassmorphism**: Use sparingly (e.g., sidebar and floating panels).

## Real-Time Data Flow
1. **Initial Hydration**: Fetch data from Quarkus REST APIs using `TanStack Query`. Cache it.
2. **WebSocket Updates**: The app listens to `/ws/events`.
3. **Zustand integration**: WebSocket events update `Zustand` stores (e.g., `useDeviceStore`, `useTelemetryStore`).
4. **No Polling**: Avoid periodic HTTP polling; rely on WebSockets for live deltas.

## Best Practices
- Place API calls in `src/lib/hooks/` wrapped in TanStack Query hooks.
- Use `lucide-react` for icons.
- Ensure all components are accessible (Shadcn handles most of this out of the box).
- Format dates via `date-fns`.
