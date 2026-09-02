import { Link, useParams } from 'react-router-dom'

import {
  BandPill,
  Card,
  HashChip,
  HesitationPill,
  InfoNote,
  PageHeader,
  QueryState,
  StatutoryPill,
  WarningNote,
} from '../components/Primitives'
import {
  actionLabel,
  count,
  epochToDate,
  languageLabel,
  millis,
  permilleToPercent,
  relativeFromEpoch,
} from '../lib/format'
import { useCertificates, useWorker } from '../lib/queries'
import { bandForPermille } from '../lib/readiness'

export function WorkerDetailPage() {
  const { workerId } = useParams<{ workerId: string }>()
  const worker = useWorker(workerId)
  const certificates = useCertificates({
    page: 1,
    siteId: null,
    workerId: workerId ?? null,
    onlyQuarantined: false,
  })

  return (
    <>
      <PageHeader
        title={worker.data?.full_name ?? 'Worker'}
        subtitle={
          <>
            <span className="mono">{workerId}</span>
            {worker.data ? (
              <>
                {' · '}
                {worker.data.site_id} · {languageLabel(worker.data.preferred_language)}
                {worker.data.pictogram_mode ? ' · pictogram mode' : ''}
              </>
            ) : null}
          </>
        }
        actions={
          <Link className="btn-secondary" to="/workers">
            Back to roster
          </Link>
        }
      />

      <QueryState query={worker} loadingLabel="Loading worker">
        {(data) => (
          <>
            <div className="grid gap-4 lg:grid-cols-3">
              <Card title="Summary">
                <dl className="space-y-2 text-sm">
                  <div className="flex items-center justify-between gap-3">
                    <dt className="text-slate-600">Overall readiness</dt>
                    <dd className="flex items-center gap-2">
                      <BandPill band={bandForPermille(data.overall_readiness_permille)} />
                      <span className="tabular-nums">
                        {permilleToPercent(data.overall_readiness_permille)}
                      </span>
                    </dd>
                  </div>
                  <div className="flex items-center justify-between gap-3">
                    <dt className="text-slate-600">Modules certified</dt>
                    <dd className="tabular-nums">
                      {count(data.modules_certified)} of {count(data.modules.length)}
                    </dd>
                  </div>
                  <div className="flex items-center justify-between gap-3">
                    <dt className="text-slate-600">Refreshers due</dt>
                    <dd className="tabular-nums">{count(data.modules_due)}</dd>
                  </div>
                  <div className="flex items-center justify-between gap-3">
                    <dt className="text-slate-600">Certificates issued</dt>
                    <dd className="tabular-nums">{count(data.certificate_count)}</dd>
                  </div>
                  <div className="flex items-center justify-between gap-3">
                    <dt className="text-slate-600">Hazards reported</dt>
                    <dd className="tabular-nums">{count(data.hazard_reports_filed)}</dd>
                  </div>
                  <div className="flex items-center justify-between gap-3">
                    <dt className="text-slate-600">Employment</dt>
                    <dd className="capitalize">{data.employment_type ?? '—'}</dd>
                  </div>
                  <div className="flex items-center justify-between gap-3">
                    <dt className="text-slate-600">Hesitation</dt>
                    <dd>
                      <HesitationPill flagged={data.hesitation_flagged} />
                    </dd>
                  </div>
                </dl>
              </Card>

              <div className="lg:col-span-2">
                <Card
                  title="Per-module status"
                  footnote="Statutory validity is date arithmetic under the Mines Act 1952 and Factories Act 1948. Readiness is a decaying retention measure. Both are shown because a worker can be current on one and not the other."
                >
                  {data.modules.length === 0 ? (
                    <InfoNote>
                      This worker has not attempted any module yet. Training is delivered on the
                      Android app and syncs when the device next has connectivity.
                    </InfoNote>
                  ) : (
                    <div className="overflow-x-auto">
                      <table>
                        <caption className="sr-only">Module readiness for this worker</caption>
                        <thead>
                          <tr>
                            <th scope="col">Module</th>
                            <th scope="col">Statutory</th>
                            <th scope="col">Readiness</th>
                            <th scope="col">Required action</th>
                            <th scope="col">Certified</th>
                            <th scope="col">Next refresher</th>
                            <th scope="col" className="text-right">
                              Best score
                            </th>
                          </tr>
                        </thead>
                        <tbody>
                          {data.modules.map((module) => {
                            const stale =
                              module.statutory_valid &&
                              (module.readiness_band === 'stale' ||
                                module.readiness_band === 'expired')
                            return (
                              <tr key={module.module_id} className={stale ? 'bg-amber-50' : ''}>
                                <td>
                                  <span className="font-medium">{module.module_title_en}</span>
                                  <span className="block text-xs text-slate-500">
                                    code {module.module_code} · {count(module.attempts)} attempt
                                    {module.attempts === 1 ? '' : 's'}
                                  </span>
                                </td>
                                <td>
                                  <StatutoryPill valid={module.statutory_valid} />
                                  {module.statutory_valid ? (
                                    <span className="mt-1 block text-xs text-slate-500">
                                      {count(module.days_until_statutory_expiry)} days left
                                    </span>
                                  ) : null}
                                </td>
                                <td>
                                  <div className="flex items-center gap-2">
                                    <BandPill band={module.readiness_band} />
                                    <span className="tabular-nums text-slate-700">
                                      {permilleToPercent(module.readiness_permille)}
                                    </span>
                                  </div>
                                </td>
                                <td>
                                  <span
                                    className={
                                      module.required_action === 'none'
                                        ? 'text-xs text-slate-600'
                                        : 'text-xs font-medium text-orange-800'
                                    }
                                  >
                                    {actionLabel(module.required_action)}
                                  </span>
                                  {module.hesitation_flagged ? (
                                    <span className="mt-1 block">
                                      <HesitationPill flagged />
                                    </span>
                                  ) : null}
                                </td>
                                <td className="text-xs">{epochToDate(module.certified_at_sec)}</td>
                                <td className="text-xs">
                                  {epochToDate(module.next_due_at_sec)}
                                  <span className="block text-slate-500">
                                    {relativeFromEpoch(module.next_due_at_sec)}
                                  </span>
                                </td>
                                <td className="text-right tabular-nums">
                                  {permilleToPercent(module.best_score_permille)}
                                </td>
                              </tr>
                            )
                          })}
                        </tbody>
                      </table>
                    </div>
                  )}

                  {data.modules.some(
                    (module) =>
                      module.statutory_valid &&
                      (module.readiness_band === 'stale' || module.readiness_band === 'expired'),
                  ) ? (
                    <div className="mt-3">
                      <WarningNote>
                        Highlighted rows are statutorily current but operationally stale. This
                        worker would pass a paperwork audit and may not react correctly in a real
                        incident.
                      </WarningNote>
                    </div>
                  ) : null}
                </Card>
              </div>
            </div>

            <div className="mt-4">
              <Card
                title="Certificates"
                footnote="Each certificate is Ed25519-signed and linked into its site's chain. The record hash is what an inspector can re-verify independently from the ledger export."
              >
                <QueryState
                  query={certificates}
                  loadingLabel="Loading certificates"
                  emptyWhen={(page) => page.items.length === 0}
                  emptyTitle="No certificates issued to this worker yet"
                  emptyHint="A certificate is minted on the device the moment a module is passed, and uploads when connectivity allows."
                >
                  {(page) => (
                    <div className="overflow-x-auto">
                      <table>
                        <caption className="sr-only">Certificates for this worker</caption>
                        <thead>
                          <tr>
                            <th scope="col">Site / seq</th>
                            <th scope="col">Module</th>
                            <th scope="col" className="text-right">
                              Score
                            </th>
                            <th scope="col" className="text-right">
                              Median decision
                            </th>
                            <th scope="col">Conditions</th>
                            <th scope="col">Issued</th>
                            <th scope="col">Record hash</th>
                          </tr>
                        </thead>
                        <tbody>
                          {page.items.map((certificate) => (
                            <tr
                              key={certificate.id}
                              className={
                                certificate.status === 'quarantined' ? 'bg-red-50' : undefined
                              }
                            >
                              <td className="tabular-nums">
                                {certificate.site_id} #{certificate.seq}
                                {certificate.status === 'quarantined' ? (
                                  <span className="mt-1 block text-xs font-medium text-red-800">
                                    Quarantined
                                  </span>
                                ) : null}
                              </td>
                              <td>{certificate.module_title_en ?? `code ${certificate.module_code}`}</td>
                              <td className="text-right tabular-nums">
                                {permilleToPercent(certificate.score_permille)}
                              </td>
                              <td className="text-right tabular-nums">
                                {millis(certificate.median_latency_ms)}
                              </td>
                              <td className="text-xs">
                                {certificate.flag_names.length ? (
                                  <ul className="space-y-0.5">
                                    {certificate.flag_names.map((flag) => (
                                      <li key={flag}>{flag.replace(/_/g, ' ')}</li>
                                    ))}
                                  </ul>
                                ) : (
                                  '—'
                                )}
                              </td>
                              <td className="text-xs">{epochToDate(certificate.issued_at_sec)}</td>
                              <td>
                                <HashChip hex={certificate.record_hash_hex} />
                              </td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  )}
                </QueryState>
              </Card>
            </div>
          </>
        )}
      </QueryState>
    </>
  )
}
