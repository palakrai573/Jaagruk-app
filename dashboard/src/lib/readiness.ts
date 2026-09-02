import type { ReadinessBand } from './types'

/**
 * Band thresholds, mirroring `ReadinessCalculator` in `:core` and `app/services/readiness.py`.
 *
 * The server sends the band with every per-module figure, so this is only used where the API returns
 * a bare aggregate — the roster's "overall readiness" column. The thresholds are duplicated here
 * rather than inferred so the mapping is explicit and greppable; the constants are asserted on both
 * of the other two sides.
 */
export const READY_THRESHOLD = 700
export const DUE_THRESHOLD = 500
export const STALE_THRESHOLD = 300

export function bandForPermille(permille: number): ReadinessBand {
  if (permille >= READY_THRESHOLD) return 'ready'
  if (permille >= DUE_THRESHOLD) return 'due'
  if (permille >= STALE_THRESHOLD) return 'stale'
  return 'expired'
}
