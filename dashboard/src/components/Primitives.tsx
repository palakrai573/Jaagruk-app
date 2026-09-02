/**
 * Shared presentational primitives.
 *
 * Two conventions run through all of them:
 *
 * - **Never colour alone.** Every state pill carries a glyph and a text label as well as a colour,
 *   so the table is readable by a colour-blind safety officer.
 * - **Empty is not an error.** A site with no workers yet, or a period with no hazards, gets an
 *   explanatory empty state. A blank panel reads as a broken tool.
 */

import type { ReactNode } from 'react'

import { bandGlyph, bandLabel, chainStatusLabel } from '../lib/format'
import type { ChainStatus, HazardSeverity, HazardStatus, ReadinessBand } from '../lib/types'

// ---------------------------------------------------------------------------
// Layout
// ---------------------------------------------------------------------------

export function PageHeader({
  title,
  subtitle,
  actions,
}: {
  title: string
  subtitle?: ReactNode
  actions?: ReactNode
}) {
  return (
    <header className="mb-5 flex flex-wrap items-start justify-between gap-3">
      <div>
        <h1 className="text-xl font-semibold tracking-tight text-slate-900">{title}</h1>
        {subtitle ? <p className="mt-1 max-w-3xl text-sm text-slate-600">{subtitle}</p> : null}
      </div>
      {actions ? <div className="flex flex-wrap items-center gap-2">{actions}</div> : null}
    </header>
  )
}

export function Card({
  title,
  actions,
  children,
  footnote,
}: {
  title?: string
  actions?: ReactNode
  children: ReactNode
  footnote?: ReactNode
}) {
  return (
    <section className="card">
      {title ? (
        <div className="card-header">
          <h2 className="card-title">{title}</h2>
          {actions}
        </div>
      ) : null}
      <div className="p-4">{children}</div>
      {footnote ? (
        <p className="border-t border-slate-200 px-4 py-2 text-xs text-slate-500">{footnote}</p>
      ) : null}
    </section>
  )
}

// ---------------------------------------------------------------------------
// Data states
// ---------------------------------------------------------------------------

export function Spinner({ label = 'Loading' }: { label?: string }) {
  return (
    <div className="flex items-center gap-2 py-8 text-sm text-slate-500" role="status">
      <span
        aria-hidden="true"
        className="h-4 w-4 animate-spin rounded-full border-2 border-slate-300 border-t-sky-700"
      />
      <span>{label}…</span>
    </div>
  )
}

export function ErrorState({
  error,
  onRetry,
}: {
  error: unknown
  onRetry?: () => void
}) {
  const message =
    error instanceof Error ? error.message : 'The request failed for an unknown reason.'
  return (
    <div
      role="alert"
      className="rounded-md border border-red-200 bg-red-50 p-4 text-sm text-red-800"
    >
      <p className="font-semibold">Could not load this data</p>
      {/* The backend's messages are written to be shown to a person; keep them. */}
      <p className="mt-1">{message}</p>
      {onRetry ? (
        <button type="button" onClick={onRetry} className="btn-secondary mt-3">
          Try again
        </button>
      ) : null}
    </div>
  )
}

export function EmptyState({ title, hint }: { title: string; hint?: ReactNode }) {
  return (
    <div className="rounded-md border border-dashed border-slate-300 bg-slate-50 p-6 text-center">
      <p className="text-sm font-medium text-slate-700">{title}</p>
      {hint ? <p className="mt-1 text-xs text-slate-500">{hint}</p> : null}
    </div>
  )
}

/** Resolves a query into exactly one of loading, error, empty or content. */
export function QueryState<T>({
  query,
  children,
  emptyWhen,
  emptyTitle,
  emptyHint,
  loadingLabel,
}: {
  query: { data: T | undefined; isPending: boolean; isError: boolean; error: unknown; refetch: () => void }
  children: (data: T) => ReactNode
  emptyWhen?: (data: T) => boolean
  emptyTitle?: string
  emptyHint?: ReactNode
  loadingLabel?: string
}) {
  if (query.isPending) return <Spinner label={loadingLabel} />
  if (query.isError) return <ErrorState error={query.error} onRetry={query.refetch} />
  if (query.data === undefined) return <EmptyState title="No data available" />
  if (emptyWhen?.(query.data)) {
    return <EmptyState title={emptyTitle ?? 'Nothing to show yet'} hint={emptyHint} />
  }
  return <>{children(query.data)}</>
}

// ---------------------------------------------------------------------------
// KPI tiles
// ---------------------------------------------------------------------------

export function KpiCard({
  label,
  value,
  hint,
  tone = 'neutral',
}: {
  label: string
  value: ReactNode
  hint?: ReactNode
  tone?: 'neutral' | 'good' | 'warn' | 'bad'
}) {
  const tones: Record<string, string> = {
    neutral: 'border-slate-200 bg-white',
    good: 'border-emerald-200 bg-emerald-50',
    warn: 'border-amber-200 bg-amber-50',
    bad: 'border-red-200 bg-red-50',
  }
  return (
    <div className={`rounded-lg border p-4 shadow-sm ${tones[tone]}`}>
      <p className="text-xs font-semibold uppercase tracking-wide text-slate-600">{label}</p>
      <p className="mt-1 text-2xl font-semibold tabular-nums text-slate-900">{value}</p>
      {hint ? <p className="mt-1 text-xs leading-snug text-slate-600">{hint}</p> : null}
    </div>
  )
}

// ---------------------------------------------------------------------------
// State pills
// ---------------------------------------------------------------------------

export function BandPill({ band }: { band: ReadinessBand | null | undefined }) {
  const styles: Record<string, string> = {
    ready: 'border-emerald-300 bg-emerald-50 text-emerald-800',
    due: 'border-amber-300 bg-amber-50 text-amber-800',
    stale: 'border-orange-300 bg-orange-50 text-orange-800',
    expired: 'border-red-300 bg-red-50 text-red-800',
  }
  const className = band ? styles[band] : 'border-slate-300 bg-slate-50 text-slate-700'
  return (
    <span className={`pill ${className}`}>
      <span aria-hidden="true">{bandGlyph(band)}</span>
      {bandLabel(band)}
    </span>
  )
}

export function StatutoryPill({ valid }: { valid: boolean | null | undefined }) {
  if (valid === null || valid === undefined) {
    return <span className="pill border-slate-300 bg-slate-50 text-slate-700">Unknown</span>
  }
  return valid ? (
    <span className="pill border-emerald-300 bg-emerald-50 text-emerald-800">
      <span aria-hidden="true">✓</span> Statutory: current
    </span>
  ) : (
    <span className="pill border-red-300 bg-red-50 text-red-800">
      <span aria-hidden="true">✕</span> Statutory: lapsed
    </span>
  )
}

export function ChainStatusPill({ status }: { status: ChainStatus }) {
  const styles: Record<ChainStatus, string> = {
    verified: 'border-emerald-300 bg-emerald-50 text-emerald-800',
    signature_valid_chain_unknown: 'border-sky-300 bg-sky-50 text-sky-800',
    sequence_gap: 'border-amber-300 bg-amber-50 text-amber-800',
    broken_link: 'border-red-300 bg-red-50 text-red-800',
    bad_signature: 'border-red-300 bg-red-50 text-red-800',
    unknown_site_key: 'border-slate-300 bg-slate-50 text-slate-700',
    malformed: 'border-slate-300 bg-slate-50 text-slate-700',
  }
  const glyphs: Record<ChainStatus, string> = {
    verified: '✓',
    signature_valid_chain_unknown: '◐',
    sequence_gap: '⋯',
    broken_link: '⚠',
    bad_signature: '⚠',
    unknown_site_key: '?',
    malformed: '×',
  }
  return (
    <span className={`pill ${styles[status]}`}>
      <span aria-hidden="true">{glyphs[status]}</span>
      {chainStatusLabel(status)}
    </span>
  )
}

export function SeverityPill({ severity }: { severity: HazardSeverity }) {
  const styles: Record<HazardSeverity, string> = {
    low: 'border-slate-300 bg-slate-50 text-slate-700',
    medium: 'border-amber-300 bg-amber-50 text-amber-800',
    high: 'border-orange-300 bg-orange-50 text-orange-800',
    critical: 'border-red-300 bg-red-50 text-red-800',
  }
  const glyphs: Record<HazardSeverity, string> = {
    low: '·',
    medium: '▪',
    high: '▲',
    critical: '⬤',
  }
  return (
    <span className={`pill ${styles[severity]}`}>
      <span aria-hidden="true">{glyphs[severity]}</span>
      {severity.charAt(0).toUpperCase() + severity.slice(1)}
    </span>
  )
}

export function HazardStatusPill({ status }: { status: HazardStatus }) {
  const styles: Record<HazardStatus, string> = {
    open: 'border-red-300 bg-red-50 text-red-800',
    acknowledged: 'border-amber-300 bg-amber-50 text-amber-800',
    in_progress: 'border-sky-300 bg-sky-50 text-sky-800',
    resolved: 'border-emerald-300 bg-emerald-50 text-emerald-800',
    invalid: 'border-slate-300 bg-slate-50 text-slate-600',
  }
  const labels: Record<HazardStatus, string> = {
    open: 'Open',
    acknowledged: 'Acknowledged',
    in_progress: 'In progress',
    resolved: 'Resolved',
    invalid: 'Not valid',
  }
  return <span className={`pill ${styles[status]}`}>{labels[status]}</span>
}

export function HesitationPill({ flagged }: { flagged: boolean }) {
  if (!flagged) return <span className="text-xs text-slate-400">—</span>
  return (
    <span
      className="pill border-orange-300 bg-orange-50 text-orange-800"
      title="Answers correctly but slowly. Knows the material; may hesitate under real pressure."
    >
      <span aria-hidden="true">◔</span> Hesitation
    </span>
  )
}

// ---------------------------------------------------------------------------
// Pagination
// ---------------------------------------------------------------------------

export function Pagination({
  page,
  pageSize,
  total,
  onChange,
}: {
  page: number
  pageSize: number
  total: number
  onChange: (page: number) => void
}) {
  const lastPage = Math.max(1, Math.ceil(total / pageSize))
  if (total === 0) return null

  const first = (page - 1) * pageSize + 1
  const last = Math.min(page * pageSize, total)

  return (
    <nav className="mt-3 flex items-center justify-between gap-3" aria-label="Pagination">
      <p className="text-xs text-slate-600">
        Showing <span className="tabular-nums">{first}</span>–
        <span className="tabular-nums">{last}</span> of{' '}
        <span className="tabular-nums">{total}</span>
      </p>
      <div className="flex items-center gap-2">
        <button
          type="button"
          className="btn-secondary"
          onClick={() => onChange(page - 1)}
          disabled={page <= 1}
        >
          Previous
        </button>
        <span className="text-xs tabular-nums text-slate-600">
          Page {page} of {lastPage}
        </span>
        <button
          type="button"
          className="btn-secondary"
          onClick={() => onChange(page + 1)}
          disabled={page >= lastPage}
        >
          Next
        </button>
      </div>
    </nav>
  )
}

// ---------------------------------------------------------------------------
// Misc
// ---------------------------------------------------------------------------

export function SiteFilter({
  sites,
  value,
  onChange,
  allLabel = 'All sites in scope',
}: {
  sites: { id: string; name: string }[]
  value: string | null
  onChange: (siteId: string | null) => void
  allLabel?: string
}) {
  return (
    <label className="flex items-center gap-2 text-sm">
      <span className="text-slate-600">Site</span>
      <select
        className="input max-w-xs"
        value={value ?? ''}
        onChange={(event) => onChange(event.target.value || null)}
      >
        <option value="">{allLabel}</option>
        {sites.map((site) => (
          <option key={site.id} value={site.id}>
            {site.id} — {site.name}
          </option>
        ))}
      </select>
    </label>
  )
}

/** Monospace hash with the full value available on hover and via the title attribute. */
export function HashChip({ hex, length = 12 }: { hex: string | null | undefined; length?: number }) {
  if (!hex) return <span className="text-slate-400">—</span>
  return (
    <code className="mono rounded bg-slate-100 px-1.5 py-0.5 text-slate-700" title={hex}>
      {hex.length <= length ? hex : `${hex.slice(0, length)}…`}
    </code>
  )
}

export function InfoNote({ children }: { children: ReactNode }) {
  return (
    <p className="rounded-md border border-sky-200 bg-sky-50 p-3 text-xs leading-relaxed text-sky-900">
      {children}
    </p>
  )
}

export function WarningNote({ children }: { children: ReactNode }) {
  return (
    <p
      role="alert"
      className="rounded-md border border-amber-300 bg-amber-50 p-3 text-xs leading-relaxed text-amber-900"
    >
      {children}
    </p>
  )
}
