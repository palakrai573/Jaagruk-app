import { NavLink, Outlet, useNavigate } from 'react-router-dom'

import { useAuth } from '../lib/auth'
import { useLiveEvents, type LiveStatus } from '../lib/useLiveEvents'

interface NavItem {
  to: string
  label: string
  /** Roles that may see the link. Omitted means every authenticated role. */
  roles?: string[]
}

const NAV_ITEMS: NavItem[] = [
  { to: '/', label: 'Overview' },
  { to: '/sites', label: 'Sites' },
  { to: '/workers', label: 'Workers' },
  { to: '/hesitation-risk', label: 'Hesitation risk' },
  { to: '/hazards', label: 'Hazard map' },
  { to: '/chain', label: 'Chain integrity' },
  { to: '/verify', label: 'Verify certificate' },
  { to: '/modules', label: 'Modules' },
  { to: '/reports', label: 'Reports', roles: ['dgms_inspector', 'company_admin', 'site_officer'] },
]

function LiveIndicator({ status }: { status: LiveStatus }) {
  const config: Record<LiveStatus, { label: string; className: string; glyph: string }> = {
    live: { label: 'Live', className: 'text-emerald-700', glyph: '●' },
    connecting: { label: 'Connecting', className: 'text-slate-500', glyph: '◌' },
    reconnecting: { label: 'Reconnecting', className: 'text-amber-700', glyph: '◌' },
    offline: { label: 'Not live', className: 'text-slate-500', glyph: '○' },
  }
  const { label, className, glyph } = config[status]
  return (
    <span
      className={`inline-flex items-center gap-1.5 text-xs font-medium ${className}`}
      title={
        status === 'live'
          ? 'Receiving certificate, hazard and sync events as they happen.'
          : 'Live updates are unavailable. Pages still refresh on their own schedule.'
      }
    >
      <span aria-hidden="true">{glyph}</span>
      {label}
    </span>
  )
}

export function Layout() {
  const { me, logout, hasRole } = useAuth()
  const navigate = useNavigate()
  const live = useLiveEvents(Boolean(me))

  const visibleItems = NAV_ITEMS.filter(
    (item) => !item.roles || hasRole(...(item.roles as never[])),
  )

  const scopeLabel = me?.site_id
    ? `Site ${me.site_id}`
    : me?.company_id
      ? 'Company-wide'
      : 'All companies'

  return (
    <div className="min-h-screen">
      <a
        href="#main"
        className="sr-only focus:not-sr-only focus:absolute focus:left-2 focus:top-2 focus:z-50
          focus:rounded focus:bg-white focus:px-3 focus:py-2 focus:text-sm focus:shadow"
      >
        Skip to main content
      </a>

      <header className="border-b border-slate-200 bg-white">
        <div className="mx-auto flex max-w-7xl flex-wrap items-center justify-between gap-3 px-4 py-3">
          <div className="flex items-baseline gap-3">
            <span className="text-lg font-semibold tracking-tight text-slate-900">Jaagruk</span>
            <span className="hidden text-xs text-slate-500 sm:inline">
              Safety compliance · Government of Jharkhand
            </span>
          </div>

          <div className="flex flex-wrap items-center gap-4">
            <LiveIndicator status={live.status} />
            {me ? (
              <div className="text-right">
                <p className="text-sm font-medium text-slate-800">{me.full_name}</p>
                <p className="text-xs text-slate-500">
                  {me.role.replace(/_/g, ' ')} · {scopeLabel}
                </p>
              </div>
            ) : null}
            <button
              type="button"
              className="btn-secondary"
              onClick={() => {
                void logout().then(() => navigate('/login', { replace: true }))
              }}
            >
              Sign out
            </button>
          </div>
        </div>

        <nav aria-label="Main" className="mx-auto max-w-7xl px-2">
          <ul className="flex flex-wrap gap-1 pb-1">
            {visibleItems.map((item) => (
              <li key={item.to}>
                <NavLink
                  to={item.to}
                  end={item.to === '/'}
                  className={({ isActive }) =>
                    `inline-block rounded-t-md px-3 py-2 text-sm font-medium transition ${
                      isActive
                        ? 'border-b-2 border-sky-700 text-sky-800'
                        : 'border-b-2 border-transparent text-slate-600 hover:text-slate-900'
                    }`
                  }
                >
                  {item.label}
                </NavLink>
              </li>
            ))}
          </ul>
        </nav>
      </header>

      <main id="main" className="mx-auto max-w-7xl px-4 py-6">
        <Outlet />
      </main>

      <footer className="mx-auto max-w-7xl px-4 pb-8 pt-2">
        <p className="text-xs leading-relaxed text-slate-500">
          Certificates are Ed25519-signed and linked into a per-site SHA-256 hash chain. This is a
          tamper-<em>evident</em> ledger, not a blockchain: there is no consensus and no distributed
          ledger. Verification also works entirely offline in the Jaagruk Android app.
        </p>
      </footer>
    </div>
  )
}
