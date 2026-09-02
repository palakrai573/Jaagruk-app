/**
 * React Query hooks, one per endpoint.
 *
 * Kept in a single file so the mapping from screen to route is auditable in one place. Every
 * backend route has at least one caller here or in `api.ts`; `docs/API.md` lists the pairing, and
 * an orphan route on either side is treated as a defect.
 */

import {
  useMutation,
  useQuery,
  useQueryClient,
  type UseMutationResult,
  type UseQueryResult,
} from '@tanstack/react-query'

import { api, buildQuery, downloadCsv } from './api'
import type {
  Certificate,
  ChainAudit,
  ChainHead,
  ComplianceOverview,
  DeviceRecord,
  Hazard,
  HazardStatus,
  HesitationRisk,
  Module,
  Page,
  ReadinessTrend,
  Site,
  SiteCompliance,
  SitePublicKeys,
  VerifyResult,
  Worker,
  WorkerDetail,
} from './types'

export const queryKeys = {
  overview: ['compliance', 'overview'] as const,
  bySite: ['compliance', 'by-site'] as const,
  hesitation: (page: number, siteId: string | null) =>
    ['compliance', 'hesitation-risk', page, siteId] as const,
  trend: (days: number, siteId: string | null) =>
    ['compliance', 'readiness-trend', days, siteId] as const,
  sites: ['sites'] as const,
  site: (siteId: string) => ['sites', siteId] as const,
  siteKeys: (siteId: string) => ['sites', siteId, 'public-keys'] as const,
  devices: ['devices'] as const,
  modules: ['modules'] as const,
  workers: (params: WorkerQueryParams) => ['workers', params] as const,
  worker: (workerId: string) => ['workers', workerId] as const,
  certificates: (params: CertificateQueryParams) => ['certificates', params] as const,
  chainHead: (siteId: string) => ['chains', siteId] as const,
  hazards: (params: HazardQueryParams) => ['hazards', params] as const,
  hazardsNoCoordinates: (siteId: string | null) =>
    ['hazards', 'without-coordinates', siteId] as const,
  hazard: (hazardId: string) => ['hazards', hazardId] as const,
}

// ---------------------------------------------------------------------------
// Compliance
// ---------------------------------------------------------------------------

export function useOverview(): UseQueryResult<ComplianceOverview> {
  return useQuery({
    queryKey: queryKeys.overview,
    queryFn: () => api.get<ComplianceOverview>('/compliance/overview'),
    // Readiness decays continuously, so a stale tile is misleading rather than merely old.
    staleTime: 30_000,
  })
}

export function useSiteCompliance(): UseQueryResult<SiteCompliance[]> {
  return useQuery({
    queryKey: queryKeys.bySite,
    queryFn: () => api.get<SiteCompliance[]>('/compliance/by-site'),
    staleTime: 30_000,
  })
}

export function useHesitationRisk(
  page: number,
  siteId: string | null,
): UseQueryResult<Page<HesitationRisk>> {
  return useQuery({
    queryKey: queryKeys.hesitation(page, siteId),
    queryFn: () =>
      api.get<Page<HesitationRisk>>(
        `/compliance/hesitation-risk${buildQuery({ page, site_id: siteId })}`,
      ),
  })
}

export function useReadinessTrend(
  days: number,
  siteId: string | null,
): UseQueryResult<ReadinessTrend> {
  return useQuery({
    queryKey: queryKeys.trend(days, siteId),
    queryFn: () =>
      api.get<ReadinessTrend>(
        `/compliance/readiness-trend${buildQuery({ days, site_id: siteId })}`,
      ),
    staleTime: 60_000,
  })
}

// ---------------------------------------------------------------------------
// Catalog
// ---------------------------------------------------------------------------

export function useSites(): UseQueryResult<Site[]> {
  return useQuery({
    queryKey: queryKeys.sites,
    queryFn: () => api.get<Site[]>('/sites'),
    staleTime: 5 * 60_000,
  })
}

export function useSite(siteId: string | undefined): UseQueryResult<Site> {
  return useQuery({
    queryKey: queryKeys.site(siteId ?? ''),
    queryFn: () => api.get<Site>(`/sites/${siteId}`),
    enabled: Boolean(siteId),
  })
}

export function useSiteKeys(siteId: string | null): UseQueryResult<SitePublicKeys> {
  return useQuery({
    queryKey: queryKeys.siteKeys(siteId ?? ''),
    queryFn: () => api.get<SitePublicKeys>(`/sites/${siteId}/public-keys`),
    enabled: Boolean(siteId),
    staleTime: 5 * 60_000,
  })
}

export function useDevices(): UseQueryResult<DeviceRecord[]> {
  return useQuery({
    queryKey: queryKeys.devices,
    queryFn: () => api.get<DeviceRecord[]>('/devices'),
    staleTime: 60_000,
  })
}

export function useModules(): UseQueryResult<Module[]> {
  return useQuery({
    queryKey: queryKeys.modules,
    queryFn: () => api.get<Module[]>('/modules'),
    staleTime: 10 * 60_000,
  })
}

// ---------------------------------------------------------------------------
// Workers
// ---------------------------------------------------------------------------

export interface WorkerQueryParams {
  page: number
  siteId: string | null
  search: string
  readinessBelow: number | null
}

export function useWorkers(params: WorkerQueryParams): UseQueryResult<Page<Worker>> {
  return useQuery({
    queryKey: queryKeys.workers(params),
    queryFn: () =>
      api.get<Page<Worker>>(
        `/workers${buildQuery({
          page: params.page,
          site_id: params.siteId,
          q: params.search,
          readiness_below: params.readinessBelow,
        })}`,
      ),
  })
}

export function useWorker(workerId: string | undefined): UseQueryResult<WorkerDetail> {
  return useQuery({
    queryKey: queryKeys.worker(workerId ?? ''),
    queryFn: () => api.get<WorkerDetail>(`/workers/${workerId}`),
    enabled: Boolean(workerId),
  })
}

// ---------------------------------------------------------------------------
// Certificates and chains
// ---------------------------------------------------------------------------

export interface CertificateQueryParams {
  page: number
  siteId: string | null
  workerId: string | null
  onlyQuarantined: boolean
}

export function useCertificates(
  params: CertificateQueryParams,
): UseQueryResult<Page<Certificate>> {
  return useQuery({
    queryKey: queryKeys.certificates(params),
    queryFn: () =>
      api.get<Page<Certificate>>(
        `/certificates${buildQuery({
          page: params.page,
          site_id: params.siteId,
          worker_id: params.workerId,
          only_quarantined: params.onlyQuarantined || null,
        })}`,
      ),
  })
}

export function useChainHead(siteId: string | null): UseQueryResult<ChainHead> {
  return useQuery({
    queryKey: queryKeys.chainHead(siteId ?? ''),
    queryFn: () => api.get<ChainHead>(`/chains/${siteId}`),
    enabled: Boolean(siteId),
  })
}

export function useVerifyCertificate(): UseMutationResult<
  VerifyResult,
  Error,
  { qrText: string; candidateWorkerId?: string }
> {
  return useMutation({
    mutationFn: ({ qrText, candidateWorkerId }) =>
      api.post<VerifyResult>('/certificates/verify', {
        qr_text: qrText,
        candidate_worker_id: candidateWorkerId || null,
      }),
  })
}

export function useAuditChain(): UseMutationResult<ChainAudit, Error, string> {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (siteId: string) => api.post<ChainAudit>(`/chains/${siteId}/verify`),
    onSuccess: (_result, siteId) => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.chainHead(siteId) })
    },
  })
}

// ---------------------------------------------------------------------------
// Hazards
// ---------------------------------------------------------------------------

export interface HazardQueryParams {
  page: number
  siteId: string | null
  status: HazardStatus | null
  severity: string | null
  bbox: string | null
}

export function useHazards(params: HazardQueryParams): UseQueryResult<Page<Hazard>> {
  return useQuery({
    queryKey: queryKeys.hazards(params),
    queryFn: () =>
      api.get<Page<Hazard>>(
        `/hazards${buildQuery({
          page: params.page,
          site_id: params.siteId,
          status: params.status,
          severity: params.severity,
          bbox: params.bbox,
        })}`,
      ),
  })
}

/**
 * Reports with no GPS fix.
 *
 * Fetched separately and shown alongside the map rather than dropped. Underground reports routinely
 * have no coordinates, and a hazard that cannot be plotted still has to be visible.
 */
export function useHazardsWithoutCoordinates(
  siteId: string | null,
): UseQueryResult<Page<Hazard>> {
  return useQuery({
    queryKey: queryKeys.hazardsNoCoordinates(siteId),
    queryFn: () =>
      api.get<Page<Hazard>>(
        `/hazards/without-coordinates${buildQuery({ site_id: siteId, page_size: 100 })}`,
      ),
  })
}

export function useTriageHazard(): UseMutationResult<
  Hazard,
  Error,
  { hazardId: string; status: HazardStatus; note?: string; expectedUpdatedAtIso: string }
> {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ hazardId, status, note, expectedUpdatedAtIso }) =>
      api.patch<Hazard>(`/hazards/${hazardId}`, {
        status,
        resolution_note: note || null,
        // Optimistic concurrency: if another officer changed this hazard since it was loaded, the
        // server returns 409 rather than letting one edit silently overwrite the other.
        expected_updated_at_iso: expectedUpdatedAtIso,
      }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['hazards'] })
      void queryClient.invalidateQueries({ queryKey: queryKeys.overview })
      void queryClient.invalidateQueries({ queryKey: queryKeys.bySite })
    },
  })
}

// ---------------------------------------------------------------------------
// Exports
// ---------------------------------------------------------------------------

export const exports = {
  statutory: (siteId: string | null, days: number, act: string) =>
    downloadCsv(
      `/reports/statutory.csv${buildQuery({ site_id: siteId, days, act })}`,
      'jaagruk-certification.csv',
    ),
  hazards: (siteId: string | null, days: number) =>
    downloadCsv(
      `/reports/hazards.csv${buildQuery({ site_id: siteId, days })}`,
      'jaagruk-hazards.csv',
    ),
  chain: (siteId: string) =>
    downloadCsv(`/reports/chain/${siteId}.csv`, `jaagruk-ledger-${siteId}.csv`),
}
