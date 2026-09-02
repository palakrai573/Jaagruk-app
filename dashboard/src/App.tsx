import { Navigate, Route, Routes } from 'react-router-dom'

import { Layout } from './components/Layout'
import { EmptyState, Spinner } from './components/Primitives'
import { useAuth } from './lib/auth'
import { ChainIntegrityPage } from './pages/ChainIntegrityPage'
import { HazardMapPage } from './pages/HazardMapPage'
import { HesitationRiskPage } from './pages/HesitationRiskPage'
import { LoginPage } from './pages/LoginPage'
import { ModulesPage } from './pages/ModulesPage'
import { OverviewPage } from './pages/OverviewPage'
import { ReportsPage } from './pages/ReportsPage'
import { SitesPage } from './pages/SitesPage'
import { VerifyPage } from './pages/VerifyPage'
import { WorkerDetailPage } from './pages/WorkerDetailPage'
import { WorkersPage } from './pages/WorkersPage'

function RequireAuth({ children }: { children: React.ReactNode }) {
  const { status } = useAuth()
  if (status === 'loading') {
    return (
      <div className="flex min-h-screen items-center justify-center">
        <Spinner label="Restoring your session" />
      </div>
    )
  }
  if (status === 'anonymous') return <Navigate to="/login" replace />
  return <>{children}</>
}

/** Roles that may reach the reports pages. Mirrors the backend's `require_roles` guard. */
function RequireRole({
  roles,
  children,
}: {
  roles: string[]
  children: React.ReactNode
}) {
  const { me } = useAuth()
  if (me && !roles.includes(me.role)) {
    // Hiding the nav link is not enough: a bookmarked URL has to fail closed too. The backend
    // would refuse the request anyway; this makes the refusal legible instead of a bare error.
    return (
      <EmptyState
        title="Not available for your role"
        hint={`This page is limited to: ${roles.map((role) => role.replace(/_/g, ' ')).join(', ')}.`}
      />
    )
  }
  return <>{children}</>
}

export function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />

      <Route
        element={
          <RequireAuth>
            <Layout />
          </RequireAuth>
        }
      >
        <Route index element={<OverviewPage />} />
        <Route path="sites" element={<SitesPage />} />
        <Route path="workers" element={<WorkersPage />} />
        <Route path="workers/:workerId" element={<WorkerDetailPage />} />
        <Route path="hesitation-risk" element={<HesitationRiskPage />} />
        <Route path="hazards" element={<HazardMapPage />} />
        <Route path="chain" element={<ChainIntegrityPage />} />
        <Route path="verify" element={<VerifyPage />} />
        <Route path="modules" element={<ModulesPage />} />
        <Route
          path="reports"
          element={
            <RequireRole roles={['dgms_inspector', 'company_admin', 'site_officer']}>
              <ReportsPage />
            </RequireRole>
          }
        />
        <Route
          path="*"
          element={
            <EmptyState
              title="Page not found"
              hint="Use the navigation above. Every page here is reachable from it."
            />
          }
        />
      </Route>
    </Routes>
  )
}
