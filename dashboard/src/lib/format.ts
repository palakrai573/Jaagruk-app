/**
 * Display formatting.
 *
 * Every formatter names its locale explicitly. Relying on the browser default would make a report
 * read differently on a machine configured for `hi-IN` than on one configured for `en-GB`, and a
 * compliance figure that changes with the viewer's locale settings is not a compliance figure.
 */

import type { ReadinessBand, RequiredAction } from './types'

const LOCALE = 'en-IN'

export function permilleToPercent(permille: number | null | undefined): string {
  if (permille === null || permille === undefined) return '—'
  return `${(permille / 10).toFixed(1)}%`
}

export function percent(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) return '—'
  return `${value.toFixed(1)}%`
}

export function count(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—'
  return new Intl.NumberFormat(LOCALE).format(value)
}

export function millis(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—'
  if (value < 1_000) return `${value} ms`
  return `${(value / 1_000).toFixed(1)} s`
}

export function epochToDate(epochSec: number | null | undefined): string {
  if (!epochSec) return '—'
  return new Intl.DateTimeFormat(LOCALE, {
    year: 'numeric',
    month: 'short',
    day: '2-digit',
    timeZone: 'Asia/Kolkata',
  }).format(new Date(epochSec * 1_000))
}

export function epochToDateTime(epochSec: number | null | undefined): string {
  if (!epochSec) return '—'
  return new Intl.DateTimeFormat(LOCALE, {
    year: 'numeric',
    month: 'short',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    timeZone: 'Asia/Kolkata',
  }).format(new Date(epochSec * 1_000))
}

export function isoToDateTime(iso: string | null | undefined): string {
  if (!iso) return '—'
  const parsed = new Date(iso)
  if (Number.isNaN(parsed.getTime())) return '—'
  return epochToDateTime(Math.floor(parsed.getTime() / 1_000))
}

export function relativeFromEpoch(epochSec: number | null | undefined): string {
  if (!epochSec) return '—'
  const deltaSeconds = Math.floor(Date.now() / 1_000) - epochSec
  const absolute = Math.abs(deltaSeconds)
  const suffix = deltaSeconds >= 0 ? 'ago' : 'from now'

  if (absolute < 60) return `moments ${suffix}`
  if (absolute < 3_600) return `${Math.floor(absolute / 60)} min ${suffix}`
  if (absolute < 86_400) return `${Math.floor(absolute / 3_600)} h ${suffix}`
  const days = Math.floor(absolute / 86_400)
  if (days < 60) return `${days} day${days === 1 ? '' : 's'} ${suffix}`
  return `${Math.floor(days / 30)} month${Math.floor(days / 30) === 1 ? '' : 's'} ${suffix}`
}

/** Abbreviated hash, for display. The full value is always available on hover or in the export. */
export function shortHash(hex: string | null | undefined, length = 12): string {
  if (!hex) return '—'
  return hex.length <= length ? hex : `${hex.slice(0, length)}…`
}

export function bandLabel(band: ReadinessBand | null | undefined): string {
  switch (band) {
    case 'ready':
      return 'Ready'
    case 'due':
      return 'Refresher due'
    case 'stale':
      return 'Stale'
    case 'expired':
      return 'Expired'
    default:
      return 'Not certified'
  }
}

/**
 * A shape per band, so readiness is never communicated by colour alone.
 * A colour-blind safety officer has to be able to read the same table.
 */
export function bandGlyph(band: ReadinessBand | null | undefined): string {
  switch (band) {
    case 'ready':
      return '●'
    case 'due':
      return '◐'
    case 'stale':
      return '◔'
    case 'expired':
      return '○'
    default:
      return '×'
  }
}

export function actionLabel(action: RequiredAction | null | undefined): string {
  switch (action) {
    case 'none':
      return 'No action'
    case 'refresher_due':
      return 'Refresher due'
    case 'full_rerun_required':
      return 'Full module re-run'
    case 'never_certified':
      return 'Never certified'
    default:
      return '—'
  }
}

export function severityLabel(severity: string): string {
  return severity.charAt(0).toUpperCase() + severity.slice(1)
}

export function humaniseSlug(slug: string): string {
  return slug
    .split(/[-_]/)
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(' ')
}

export function languageLabel(tag: string): string {
  switch (tag) {
    case 'hi':
      return 'Hindi'
    case 'sat':
      return 'Santali'
    case 'en':
      return 'English'
    default:
      return tag
  }
}

/** Human-readable chain verdict. Never collapse these into "valid"/"invalid". */
export function chainStatusLabel(status: string): string {
  switch (status) {
    case 'verified':
      return 'Verified'
    case 'signature_valid_chain_unknown':
      return 'Signature valid, chain not held locally'
    case 'broken_link':
      return 'Broken chain link'
    case 'sequence_gap':
      return 'Sequence gap'
    case 'bad_signature':
      return 'Invalid signature'
    case 'unknown_site_key':
      return 'No key held for this site'
    case 'malformed':
      return 'Not a Jaagruk certificate'
    default:
      return humaniseSlug(status)
  }
}
