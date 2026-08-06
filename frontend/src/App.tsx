import {
  createRootRoute,
  createRoute,
  createRouter,
  RouterProvider,
  Outlet
} from '@tanstack/react-router'
import { AuthProvider, useAuth } from './lib/auth/auth-context'
import { VaultProvider } from './lib/vault/vault-context'
import { Layout } from './components/Layout'
import { Login } from './pages/Login'
import { Dashboard } from './pages/Dashboard'
import { Devices } from './pages/Devices'
import { DeviceDetail } from './pages/DeviceDetail'
import { Topology } from './pages/Topology'
import { Vault } from './pages/Vault'
import { Alerts } from './pages/Alerts'

// 1. Root Router Guard Component
const RootComponent = () => {
  const { isAuthenticated } = useAuth()

  if (!isAuthenticated) {
    return <Login />
  }

  return (
    <VaultProvider>
      <Layout>
        <Outlet />
      </Layout>
    </VaultProvider>
  )
}

// 2. Route Configuration
const rootRoute = createRootRoute({
  component: RootComponent
})

const dashboardRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/',
  component: Dashboard
})

const devicesRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/devices',
  component: Devices
})

const deviceDetailRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/devices/$id',
  component: DeviceDetail
})

const topologyRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/topology',
  component: Topology
})

const vaultRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/vault',
  component: Vault
})

const loginRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/login',
  component: Login
})

const alertsRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/alerts',
  component: Alerts
})

const routeTree = rootRoute.addChildren([
  dashboardRoute,
  devicesRoute,
  deviceDetailRoute,
  topologyRoute,
  vaultRoute,
  alertsRoute,
  loginRoute
])

const router = createRouter({ routeTree })

// Register the router instance for type safety
declare module '@tanstack/react-router' {
  interface Register {
    router: typeof router
  }
}

function App() {
  return (
    <AuthProvider>
      <RouterProvider router={router} />
    </AuthProvider>
  )
}

export default App
