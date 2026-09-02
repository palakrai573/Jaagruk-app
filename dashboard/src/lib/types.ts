/**
 * Wire types, mirroring `backend/app/schemas.py`.
 *
 * Hand-written rather than generated from the OpenAPI document. The schema is small and stable, and
 * a hand-written file lets each field carry the note explaining *why* it exists — particularly the
 * statutory-versus-readiness distinction, which is the thing most likely to be misread by whoever
 * next touches this UI.
 */

export type Role = 'dgms_inspector' | 'company_admin' | 'site_officer' | 'supervisor'

export type ReadinessBand = 'ready' | 'due' | 'stale' | 'expired'

export type RequiredAction =
  | 'none'
  | 'refresher_due'
  | 'full_rerun_required'
  | 'never_certified'

export type ChainStatus =
  | 'verified'
  | 'signature_valid_chain_unknown'
  | 'broken_link'
  | 'sequence_gap'
  | 'bad_signature'
  | 'unknown_site_key'
  | 'malformed'

export type HazardStatus = 'open' | 'acknowledged' | 'in_progress' | 'resolved' | 'invalid'

export type HazardSeverity = 'low' | 'medium' | 'high' | 'critical'

export type CertificateStatus = 'verified' | 'quarantined' | 'superseded'

export interface Page<T> {
  items: T[]
  total: number
  page: number
  page_size: number
}

export interface TokenResponse {
  access_token: string
  refresh_token: string
  token_type: 'bearer'
  expires_in_seconds: number
  role: Role
  company_id: string | null
  site_id: string | null
  full_name: string
}

export interface Me {
  user_id: string
  username: string
  full_name: string
  role: Role
  company_id: string | null
  site_id: string | null
  permissions: string[]
}

export interface Site {
  id: string
  company_id: string
  name: string
  district: string
  sector: string
  ar_scanned: boolean
  ar_anchor_count: number
  latitude: number | null
  longitude: number | null
  active: boolean
}

export interface SiteKey {
  epoch: number
  public_key_hex: string
  active: boolean
  registered_at_iso: string
  revoked_at_iso: string | null
  revocation_reason: string | null
}

export interface SitePublicKeys {
  site_id: string
  keys: SiteKey[]
}

export interface Module {
  id: string
  module_code: number
  catalog_version: number
  title_key: string
  title_en: string
  description_key: string
  statutory_reference: string
  estimated_minutes: number
  supports_buddy_drill: boolean
  fully_implemented: boolean
  enabled: boolean
  sectors: string[]
}

export interface DeviceRecord {
  id: string
  site_id: string
  model: string | null
  android_release: string | null
  app_version: string | null
  active: boolean
  last_seen_at_iso: string | null
  last_sync_at_iso: string | null
}

export interface ModuleReadiness {
  module_id: string
  module_code: number
  module_title_en: string
  attempts: number
  best_score_permille: number
  base_score_permille: number
  /** Decaying operational retention. Separate from `statutory_valid`. */
  readiness_permille: number
  readiness_band: ReadinessBand
  /** Date arithmetic only: certified within the last 365 days. Never affected by decay. */
  statutory_valid: boolean
  days_until_statutory_expiry: number
  required_action: RequiredAction
  refresher_due: boolean
  next_due_at_sec: number
  last_pass_at_sec: number
  certified_at_sec: number
  hesitation_flagged: boolean
}

export interface Worker {
  id: string
  site_id: string
  full_name: string
  preferred_language: string
  pictogram_mode: boolean
  active: boolean
  provisional: boolean
  overall_readiness_permille: number
  modules_certified: number
  modules_due: number
  hesitation_flagged: boolean
}

export interface WorkerDetail extends Worker {
  phone_number: string | null
  employment_type: string | null
  joined_at_iso: string | null
  modules: ModuleReadiness[]
  certificate_count: number
  hazard_reports_filed: number
}

export interface ComplianceOverview {
  site_count: number
  worker_count: number
  certificate_count: number
  quarantined_certificate_count: number
  certified_worker_percent: number
  mean_readiness_permille: number
  workers_ready: number
  workers_due: number
  workers_stale: number
  workers_expired: number
  workers_never_certified: number
  /**
   * Statutorily valid but operationally stale: legally clear to work, practically unprepared.
   * The cohort a site officer should act on first.
   */
  statutorily_valid_but_stale: number
  hesitation_risk_count: number
  open_hazard_count: number
  critical_hazard_count: number
  refreshers_due_count: number
  generated_at_sec: number
}

export interface SiteCompliance {
  site_id: string
  site_name: string
  district: string
  sector: string
  ar_scanned: boolean
  worker_count: number
  certified_worker_percent: number
  mean_readiness_permille: number
  hesitation_risk_count: number
  open_hazard_count: number
  quarantined_certificate_count: number
  refreshers_due_count: number
}

export interface HesitationRisk {
  worker_id: string
  worker_full_name: string
  site_id: string
  site_name: string
  module_id: string
  module_title_en: string
  score_permille: number
  median_latency_ms: number
  /** Median decision time as a multiple of the expert baseline. 1.0 is on pace. */
  pace_multiple: number
  hesitant_step_count: number
  total_step_count: number
  last_attempt_at_sec: number
  readiness_permille: number
  statutory_valid: boolean
}

export interface ReadinessTrendPoint {
  day_epoch_sec: number
  mean_readiness_permille: number
  certificates_issued: number
  assessments_run: number
  hesitation_flagged: number
}

export interface ReadinessTrend {
  site_id: string | null
  from_epoch_sec: number
  to_epoch_sec: number
  points: ReadinessTrendPoint[]
}

export interface Certificate {
  id: string
  site_id: string
  seq: number
  key_epoch: number
  worker_id: string | null
  worker_full_name: string | null
  module_code: number
  module_title_en: string | null
  score_permille: number
  median_latency_ms: number
  outcome_flags: number
  flag_names: string[]
  issued_at_sec: number
  status: CertificateStatus
  quarantine_reason: string | null
  clock_skew_flagged: boolean
  record_hash_hex: string
  prev_record_hash_hex: string
  qr_text: string
  device_id: string | null
}

export interface VerifyResult {
  status: ChainStatus
  trustworthy: boolean
  indicates_tampering: boolean
  reasons: string[]
  site_id: string | null
  seq: number | null
  module_code: number | null
  module_title_en: string | null
  score_permille: number | null
  median_latency_ms: number | null
  outcome_flags: number | null
  flag_names: string[]
  issued_at_sec: number | null
  statutory_valid: boolean | null
  statutory_expiry_sec: number | null
  readiness_permille: number | null
  readiness_band: ReadinessBand | null
  worker_id_matches: boolean | null
  worker_full_name: string | null
  record_hash_hex: string | null
  prev_record_hash_hex: string | null
}

export interface ChainHead {
  site_id: string
  last_seq: number
  last_record_hash_hex: string
  certificate_count: number
  quarantined_count: number
  updated_at_iso: string | null
  /** Absent sequence numbers below the head: benign while syncing, evidence of deletion if not. */
  missing_sequences: number[]
}

export interface ChainAudit {
  site_id: string
  records_checked: number
  status: ChainStatus
  clean: boolean
  first_problem_seq: number | null
  reasons: string[]
  quarantined_seqs: number[]
}

export interface Hazard {
  id: string
  site_id: string
  site_name: string | null
  reporter_worker_id: string | null
  reporter_label: string
  category: string
  severity: HazardSeverity
  note: string | null
  latitude: number | null
  longitude: number | null
  zone_label: string | null
  ar_anchor_id: string | null
  photo_media_id: string | null
  voice_media_id: string | null
  status: HazardStatus
  duplicate_of_id: string | null
  duplicate_count: number
  reported_at_sec: number
  created_at_iso: string
  updated_at_iso: string
  resolved_at_iso: string | null
  resolution_note: string | null
  allowed_next_statuses: HazardStatus[]
}

export type LiveEventType =
  | 'connected'
  | 'heartbeat'
  | 'cert.issued'
  | 'cert.quarantined'
  | 'hazard.created'
  | 'hazard.updated'
  | 'sync.batch'
  | 'chain.break'

export interface LiveEvent {
  type: LiveEventType
  site_id: string | null
  at_epoch_sec: number
  payload: Record<string, unknown>
}
