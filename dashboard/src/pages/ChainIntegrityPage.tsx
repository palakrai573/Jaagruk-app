import { useEffect, useState } from 'react'
import { useSearchParams } from 'react-router-dom'

import {
  Card,
  ChainStatusPill,
  HashChip,
  InfoNote,
  KpiCard,
  PageHeader,
  Pagination,
  QueryState,
  SiteFilter,
  WarningNote,
} from '../components/Primitives'
import { count, epochToDateTime, isoToDateTime, permilleToPercent } from '../lib/format'
import { exports, useAuditChain, useCertificates, useChainHead, useSites } from '../lib/queries'

export function ChainIntegrityPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const [siteId, setSiteId] = useState<string | null>(searchParams.get('site') ?? null)
  const [page, setPage] = useState(1)
  const [onlyQuarantined, setOnlyQuarantined] = useState(false)
  const [downloadError, setDownloadError] = useState<string | null>(null)

  const sites = useSites()
  const head = useChainHead(siteId)
  const audit = useAuditChain()
  const certificates = useCertificates({
    page,
    siteId,
    workerId: null,
    onlyQuarantined,
  })

  useEffect(() => {
    const next = new URLSearchParams()
    if (siteId) next.set('site', siteId)
    setSearchParams(next, { replace: true })
  }, [siteId, setSearchParams])

  // A site defaults in so the page is never an empty shell on first visit.
  useEffect(() => {
    if (!siteId && sites.data && sites.data.length > 0) {
      setSiteId(sites.data[0]?.id ?? null)
    }
  }, [siteId, sites.data])

  return (
    <>
      <PageHeader
        title="Chain integrity"
        subtitle="Each site's certificates form a SHA-256 hash chain: every record commits to the hash of the one before it, signature included. Deleting, inserting or altering a record breaks the linkage and becomes visible here."
        actions={
          <QueryState query={sites} emptyWhen={(list) => list.length === 0} emptyTitle="No sites">
            {(list) => (
              <SiteFilter
                sites={list}
                value={siteId}
                onChange={(value) => {
                  setSiteId(value)
                  setPage(1)
                  audit.reset()
                }}
                allLabel="Select a site"
              />
            )}
          </QueryState>
        }
      />

      <div className="mb-4">
        <InfoNote>
          <strong>What this does and does not promise.</strong> This is a tamper-<em>evident</em>{' '}
          ledger, not a blockchain — there is no consensus and no distributed ledger, just chained
          hashing and Ed25519 signatures. It detects interference; it cannot prevent it. That is the
          honest guarantee, and it is enough to answer the thing the problem statement complains
          about: a physical certificate with no way to check whether it means anything.
        </InfoNote>
      </div>

      {!siteId ? (
        <Card>
          <p className="text-sm text-slate-600">Select a site to inspect its ledger.</p>
        </Card>
      ) : (
        <>
          <QueryState query={head} loadingLabel="Loading chain head">
            {(data) => (
              <>
                <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
                  <KpiCard
                    label="Chain length"
                    value={count(data.last_seq)}
                    hint="highest verified sequence"
                  />
                  <KpiCard
                    label="Certificates held"
                    value={count(data.certificate_count)}
                    hint={`updated ${isoToDateTime(data.updated_at_iso)}`}
                  />
                  <KpiCard
                    label="Quarantined"
                    value={count(data.quarantined_count)}
                    hint="failed verification on ingest; retained as evidence"
                    tone={data.quarantined_count > 0 ? 'bad' : 'good'}
                  />
                  <KpiCard
                    label="Missing sequences"
                    value={count(data.missing_sequences.length)}
                    hint="benign while devices are still syncing"
                    tone={data.missing_sequences.length > 0 ? 'warn' : 'good'}
                  />
                </div>

                <div className="mt-4 grid gap-4 lg:grid-cols-2">
                  <Card title="Chain head">
                    <dl className="space-y-2 text-sm">
                      <div className="flex items-center justify-between gap-3">
                        <dt className="text-slate-600">Site</dt>
                        <dd className="font-medium">{data.site_id}</dd>
                      </div>
                      <div className="flex items-center justify-between gap-3">
                        <dt className="text-slate-600">Tip sequence</dt>
                        <dd className="tabular-nums">{count(data.last_seq)}</dd>
                      </div>
                      <div className="flex items-start justify-between gap-3">
                        <dt className="text-slate-600">Tip record hash</dt>
                        <dd>
                          <HashChip hex={data.last_record_hash_hex} length={24} />
                        </dd>
                      </div>
                    </dl>

                    {data.missing_sequences.length > 0 ? (
                      <div className="mt-3">
                        <WarningNote>
                          Sequence{data.missing_sequences.length === 1 ? '' : 's'}{' '}
                          <span className="mono">
                            {data.missing_sequences.slice(0, 24).join(', ')}
                            {data.missing_sequences.length > 24 ? ' …' : ''}
                          </span>{' '}
                          {data.missing_sequences.length === 1 ? 'is' : 'are'} absent below the tip.
                          This is expected while a handset still holds unsynced certificates. If
                          every device for this site has synced, it is evidence that records were
                          removed.
                        </WarningNote>
                      </div>
                    ) : null}

                    <div className="mt-3 flex flex-wrap gap-2">
                      <button
                        type="button"
                        className="btn-primary"
                        disabled={audit.isPending}
                        onClick={() => audit.mutate(siteId)}
                      >
                        {audit.isPending ? 'Walking the ledger…' : 'Verify the whole ledger'}
                      </button>
                      <button
                        type="button"
                        className="btn-secondary"
                        onClick={() => {
                          setDownloadError(null)
                          exports.chain(siteId).catch((error: unknown) =>
                            setDownloadError(
                              error instanceof Error ? error.message : 'Export failed.',
                            ),
                          )
                        }}
                      >
                        Export ledger (CSV)
                      </button>
                    </div>
                    {downloadError ? (
                      <p role="alert" className="mt-2 text-xs text-red-800">
                        {downloadError}
                      </p>
                    ) : null}
                    <p className="mt-2 text-xs text-slate-500">
                      The export carries the verification recipe in its header, so an auditor can
                      re-check the chain with nothing but that file and the site public key.
                    </p>
                  </Card>

                  <Card title="Audit result">
                    {audit.isPending ? (
                      <p className="text-sm text-slate-600">
                        Verifying every signature and link in sequence…
                      </p>
                    ) : audit.isError ? (
                      <p role="alert" className="text-sm text-red-800">
                        {audit.error.message}
                      </p>
                    ) : audit.data ? (
                      <div className="space-y-3">
                        <div className="flex flex-wrap items-center gap-2">
                          <ChainStatusPill status={audit.data.status} />
                          <span className="text-sm text-slate-600">
                            {count(audit.data.records_checked)} record
                            {audit.data.records_checked === 1 ? '' : 's'} checked
                          </span>
                        </div>

                        {audit.data.clean ? (
                          <p className="text-sm text-emerald-800">
                            The ledger verifies end to end. Every signature is valid and every record
                            links to its predecessor.
                          </p>
                        ) : (
                          <WarningNote>
                            First problem at sequence{' '}
                            <span className="mono">{audit.data.first_problem_seq ?? '—'}</span>. The
                            walk stops there deliberately: everything after an unexplained break is
                            untrustworthy anyway, so continuing would only produce noise around one
                            root cause.
                          </WarningNote>
                        )}

                        <ul className="space-y-1 text-xs text-slate-700">
                          {audit.data.reasons.map((reason) => (
                            <li key={reason} className="rounded bg-slate-50 px-2 py-1">
                              {reason}
                            </li>
                          ))}
                        </ul>

                        {audit.data.quarantined_seqs.length > 0 ? (
                          <p className="text-xs text-slate-600">
                            Quarantined sequences:{' '}
                            <span className="mono">{audit.data.quarantined_seqs.join(', ')}</span>
                          </p>
                        ) : null}
                      </div>
                    ) : (
                      <p className="text-sm text-slate-600">
                        Run a verification to walk every record for this site and check each
                        signature and link.
                      </p>
                    )}
                  </Card>
                </div>
              </>
            )}
          </QueryState>

          <div className="mt-4">
            <Card
              title="Ledger"
              actions={
                <label className="flex items-center gap-2 text-sm">
                  <input
                    type="checkbox"
                    className="h-4 w-4"
                    checked={onlyQuarantined}
                    onChange={(event) => {
                      setOnlyQuarantined(event.target.checked)
                      setPage(1)
                    }}
                  />
                  <span>Quarantined only</span>
                </label>
              }
            >
              <QueryState
                query={certificates}
                loadingLabel="Loading ledger"
                emptyWhen={(data) => data.items.length === 0}
                emptyTitle={
                  onlyQuarantined
                    ? 'No quarantined certificates for this site'
                    : 'This site has issued no certificates yet'
                }
                emptyHint={
                  onlyQuarantined
                    ? 'Nothing has failed verification on ingest.'
                    : 'Certificates are minted on the device when a module is passed and upload on the next sync.'
                }
              >
                {(data) => (
                  <>
                    <div className="overflow-x-auto">
                      <table>
                        <caption className="sr-only">Certificate ledger, newest first</caption>
                        <thead>
                          <tr>
                            <th scope="col">Seq</th>
                            <th scope="col">Worker</th>
                            <th scope="col">Module</th>
                            <th scope="col" className="text-right">
                              Score
                            </th>
                            <th scope="col">Issued</th>
                            <th scope="col">Previous hash</th>
                            <th scope="col">Record hash</th>
                            <th scope="col">Status</th>
                          </tr>
                        </thead>
                        <tbody>
                          {data.items.map((certificate) => (
                            <tr
                              key={certificate.id}
                              className={
                                certificate.status === 'quarantined' ? 'bg-red-50' : undefined
                              }
                            >
                              <td className="tabular-nums">{certificate.seq}</td>
                              <td>
                                {certificate.worker_full_name ?? (
                                  <span
                                    className="text-slate-500"
                                    title="The certificate arrived before this worker's roster entry. It links by worker-id hash at the next bootstrap."
                                  >
                                    Unresolved
                                  </span>
                                )}
                                <span className="mono block text-slate-500">
                                  {certificate.worker_id ?? '—'}
                                </span>
                              </td>
                              <td>
                                {certificate.module_title_en ?? `code ${certificate.module_code}`}
                              </td>
                              <td className="text-right tabular-nums">
                                {permilleToPercent(certificate.score_permille)}
                              </td>
                              <td className="text-xs">
                                {epochToDateTime(certificate.issued_at_sec)}
                                {certificate.clock_skew_flagged ? (
                                  <span
                                    className="block text-amber-800"
                                    title="Issued slightly ahead of server time — a handset with a fast clock. Accepted inside the tolerance and flagged."
                                  >
                                    clock skew
                                  </span>
                                ) : null}
                              </td>
                              <td>
                                <HashChip hex={certificate.prev_record_hash_hex} length={10} />
                              </td>
                              <td>
                                <HashChip hex={certificate.record_hash_hex} length={10} />
                              </td>
                              <td>
                                {certificate.status === 'quarantined' ? (
                                  <span
                                    className="pill border-red-300 bg-red-50 text-red-800"
                                    title={certificate.quarantine_reason ?? undefined}
                                  >
                                    ⚠ Quarantined
                                  </span>
                                ) : (
                                  <span className="pill border-emerald-300 bg-emerald-50 text-emerald-800">
                                    ✓ Verified
                                  </span>
                                )}
                              </td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                    <Pagination
                      page={data.page}
                      pageSize={data.page_size}
                      total={data.total}
                      onChange={setPage}
                    />
                  </>
                )}
              </QueryState>
            </Card>
          </div>
        </>
      )}
    </>
  )
}
