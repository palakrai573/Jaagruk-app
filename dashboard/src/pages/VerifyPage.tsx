import { useState, type FormEvent } from 'react'

import {
  BandPill,
  Card,
  ChainStatusPill,
  HashChip,
  InfoNote,
  PageHeader,
  StatutoryPill,
  WarningNote,
} from '../components/Primitives'
import { epochToDate, millis, permilleToPercent } from '../lib/format'
import { useVerifyCertificate } from '../lib/queries'

export function VerifyPage() {
  const [qrText, setQrText] = useState('')
  const [candidateWorkerId, setCandidateWorkerId] = useState('')
  const verify = useVerifyCertificate()

  const onSubmit = (event: FormEvent) => {
    event.preventDefault()
    const trimmed = qrText.trim()
    if (!trimmed) return
    verify.mutate({ qrText: trimmed, candidateWorkerId: candidateWorkerId.trim() })
  }

  const result = verify.data

  return (
    <>
      <PageHeader
        title="Verify a certificate"
        subtitle="Paste the QR contents, or the verification link printed on a certificate card. The server runs exactly the checks the Android app runs offline: decode, Ed25519 signature against every key epoch the site has ever had, then chain linkage."
      />

      <div className="mb-4">
        <InfoNote>
          A DGMS inspector at a mine gate does not need this page. The Jaagruk Android app performs
          the same verification with no connectivity at all, which is the point of signing the
          certificate rather than looking it up. This page exists for desk checks and for pasting a
          code someone has emailed in.
        </InfoNote>
      </div>

      <div className="grid gap-4 lg:grid-cols-2">
        <Card title="Certificate to check">
          <form onSubmit={onSubmit} className="space-y-4">
            <div>
              <label className="label" htmlFor="qr-text">
                QR contents or verification link
              </label>
              <textarea
                id="qr-text"
                className="input min-h-[9rem] font-mono text-xs"
                placeholder="JGK1:… or https://jaagruk.jharkhand.gov.in/v/…"
                value={qrText}
                onChange={(event) => setQrText(event.target.value)}
                spellCheck={false}
                required
              />
            </div>

            <div>
              <label className="label" htmlFor="worker-id">
                Worker id from the physical card (optional)
              </label>
              <input
                id="worker-id"
                className="input font-mono text-xs"
                placeholder="JH-DHN-001-W00042"
                value={candidateWorkerId}
                onChange={(event) => setCandidateWorkerId(event.target.value)}
                spellCheck={false}
              />
              <p className="mt-1 text-xs text-slate-500">
                The QR carries a hash of the worker id, never the id itself, so a dropped card
                discloses no identity. Supplying the id here confirms the certificate belongs to the
                person holding it.
              </p>
            </div>

            <div className="flex gap-2">
              <button type="submit" className="btn-primary" disabled={verify.isPending}>
                {verify.isPending ? 'Verifying…' : 'Verify'}
              </button>
              <button
                type="button"
                className="btn-secondary"
                onClick={() => {
                  setQrText('')
                  setCandidateWorkerId('')
                  verify.reset()
                }}
              >
                Clear
              </button>
            </div>

            {verify.isError ? (
              <p role="alert" className="text-sm text-red-800">
                {verify.error.message}
              </p>
            ) : null}
          </form>
        </Card>

        <Card title="Result">
          {!result ? (
            <p className="text-sm text-slate-600">
              The verdict appears here. It is a status rather than a yes/no, because “signature valid
              but this server holds no chain copy for that site” is a legitimate partial result and
              reporting it as “invalid” would train inspectors to ignore the tool.
            </p>
          ) : (
            <div className="space-y-4">
              <div className="flex flex-wrap items-center gap-2">
                <ChainStatusPill status={result.status} />
                {result.trustworthy ? (
                  <span className="text-sm font-medium text-emerald-800">
                    Safe to treat as genuine
                  </span>
                ) : (
                  <span className="text-sm font-medium text-red-800">Do not accept</span>
                )}
              </div>

              {result.indicates_tampering ? (
                <WarningNote>
                  This result indicates deliberate interference, not missing data. Record the
                  certificate, the holder and the site, and raise it with the site officer.
                </WarningNote>
              ) : null}

              <ul className="space-y-1 text-xs text-slate-700">
                {result.reasons.map((reason) => (
                  <li key={reason} className="rounded bg-slate-50 px-2 py-1">
                    {reason}
                  </li>
                ))}
              </ul>

              {result.site_id ? (
                <dl className="space-y-2 border-t border-slate-200 pt-3 text-sm">
                  <div className="flex items-center justify-between gap-3">
                    <dt className="text-slate-600">Site and sequence</dt>
                    <dd className="tabular-nums">
                      {result.site_id} #{result.seq}
                    </dd>
                  </div>
                  <div className="flex items-center justify-between gap-3">
                    <dt className="text-slate-600">Module</dt>
                    <dd>{result.module_title_en ?? `code ${result.module_code}`}</dd>
                  </div>
                  <div className="flex items-center justify-between gap-3">
                    <dt className="text-slate-600">Score</dt>
                    <dd className="tabular-nums">{permilleToPercent(result.score_permille)}</dd>
                  </div>
                  <div className="flex items-center justify-between gap-3">
                    <dt className="text-slate-600">Median decision time</dt>
                    <dd className="tabular-nums">{millis(result.median_latency_ms)}</dd>
                  </div>
                  <div className="flex items-center justify-between gap-3">
                    <dt className="text-slate-600">Issued</dt>
                    <dd>{epochToDate(result.issued_at_sec)}</dd>
                  </div>
                  <div className="flex items-start justify-between gap-3">
                    <dt className="text-slate-600">Statutory validity</dt>
                    <dd className="text-right">
                      <StatutoryPill valid={result.statutory_valid} />
                      {result.statutory_expiry_sec ? (
                        <span className="mt-1 block text-xs text-slate-500">
                          expires {epochToDate(result.statutory_expiry_sec)}
                        </span>
                      ) : null}
                    </dd>
                  </div>
                  <div className="flex items-start justify-between gap-3">
                    <dt className="text-slate-600">Current readiness</dt>
                    <dd className="text-right">
                      {result.readiness_band ? (
                        <>
                          <BandPill band={result.readiness_band} />
                          <span className="mt-1 block text-xs tabular-nums text-slate-600">
                            {permilleToPercent(result.readiness_permille)}
                          </span>
                        </>
                      ) : (
                        <span className="text-xs text-slate-500">
                          No synced training record for this worker
                        </span>
                      )}
                    </dd>
                  </div>
                  <div className="flex items-start justify-between gap-3">
                    <dt className="text-slate-600">Conditions</dt>
                    <dd className="text-right text-xs">
                      {result.flag_names.length ? (
                        <ul>
                          {result.flag_names.map((flag) => (
                            <li key={flag}>{flag.replace(/_/g, ' ')}</li>
                          ))}
                        </ul>
                      ) : (
                        '—'
                      )}
                    </dd>
                  </div>
                  <div className="flex items-center justify-between gap-3">
                    <dt className="text-slate-600">Holder</dt>
                    <dd className="text-right">
                      {result.worker_full_name ?? (
                        <span className="text-xs text-slate-500">Not on this server's roster</span>
                      )}
                      {result.worker_id_matches === true ? (
                        <span className="block text-xs text-emerald-800">
                          ✓ Matches the id you entered
                        </span>
                      ) : result.worker_id_matches === false ? (
                        <span className="block text-xs font-medium text-red-800">
                          ✕ Does not match the id you entered
                        </span>
                      ) : null}
                    </dd>
                  </div>
                  <div className="flex items-start justify-between gap-3">
                    <dt className="text-slate-600">Record hash</dt>
                    <dd>
                      <HashChip hex={result.record_hash_hex} length={20} />
                    </dd>
                  </div>
                  <div className="flex items-start justify-between gap-3">
                    <dt className="text-slate-600">Links to</dt>
                    <dd>
                      <HashChip hex={result.prev_record_hash_hex} length={20} />
                    </dd>
                  </div>
                </dl>
              ) : null}
            </div>
          )}
        </Card>
      </div>

      <div className="mt-4">
        <Card title="What each verdict means">
          <dl className="space-y-3 text-sm">
            <div>
              <dt className="font-medium text-slate-800">Verified</dt>
              <dd className="text-slate-600">
                Signature valid and the record links correctly into the chain this server holds.
              </dd>
            </div>
            <div>
              <dt className="font-medium text-slate-800">
                Signature valid, chain not held locally
              </dt>
              <dd className="text-slate-600">
                The certificate is genuinely signed by that site's key, but this server has no copy
                of the site's chain to cross-check the linkage against. Normal on a first visit or
                for a certificate newer than the last sync. Treat as genuine.
              </dd>
            </div>
            <div>
              <dt className="font-medium text-slate-800">Sequence gap</dt>
              <dd className="text-slate-600">
                Signature valid and linkage consistent, but records are missing in between. Benign
                while handsets still hold unsynced certificates; suspicious once they do not.
              </dd>
            </div>
            <div>
              <dt className="font-medium text-slate-800">Broken chain link</dt>
              <dd className="text-slate-600">
                Correctly signed but pointing at the wrong predecessor. This is what inserting or
                reordering a record looks like.
              </dd>
            </div>
            <div>
              <dt className="font-medium text-slate-800">Invalid signature</dt>
              <dd className="text-slate-600">
                The payload was altered after signing, or it was not signed by that site at all.
              </dd>
            </div>
            <div>
              <dt className="font-medium text-slate-800">No key held for this site</dt>
              <dd className="text-slate-600">
                Nothing can be asserted either way. The site's supervisor handset has not registered
                with this server yet.
              </dd>
            </div>
            <div>
              <dt className="font-medium text-slate-800">Not a Jaagruk certificate</dt>
              <dd className="text-slate-600">
                The code could not be decoded — a damaged scan, a partial paste, or a QR from
                another application.
              </dd>
            </div>
          </dl>
        </Card>
      </div>
    </>
  )
}
