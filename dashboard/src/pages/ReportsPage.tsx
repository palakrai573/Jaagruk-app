import { useState } from 'react'

import { Card, InfoNote, PageHeader, QueryState, SiteFilter } from '../components/Primitives'
import { exports, useSites } from '../lib/queries'

type Act = 'both' | 'mines' | 'factories'

const WINDOWS = [30, 90, 180, 365, 730] as const

export function ReportsPage() {
  const sites = useSites()

  const [siteId, setSiteId] = useState<string | null>(null)
  const [days, setDays] = useState<number>(365)
  const [act, setAct] = useState<Act>('both')
  const [hazardDays, setHazardDays] = useState<number>(90)
  const [busy, setBusy] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  const run = async (key: string, task: () => Promise<void>) => {
    setBusy(key)
    setError(null)
    try {
      await task()
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : 'The export failed.')
    } finally {
      setBusy(null)
    }
  }

  return (
    <>
      <PageHeader
        title="Reports"
        subtitle="CSV exports formatted for a statutory audit under the Mines Act 1952 and Factories Act 1948."
        actions={
          <QueryState query={sites} emptyWhen={(list) => list.length === 0} emptyTitle="No sites">
            {(list) => <SiteFilter sites={list} value={siteId} onChange={setSiteId} />}
          </QueryState>
        }
      />

      <div className="mb-4">
        <InfoNote>
          Every certification row carries the record-hash prefix of the certificate behind it, so an
          inspector can tie a compliance line back to a specific ledger entry and re-verify it
          independently. A compliance report that cannot be traced to its evidence is only a claim.
          Exports are streamed and row-capped, and a truncated file says so in its own header.
        </InfoNote>
      </div>

      {error ? (
        <div
          role="alert"
          className="mb-4 rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800"
        >
          {error}
        </div>
      ) : null}

      <div className="grid gap-4 lg:grid-cols-3">
        <Card
          title="Certification status"
          footnote="One row per worker per module: certification date, statutory expiry, current readiness, required action, and the certificate it traces to."
        >
          <div className="space-y-3">
            <div>
              <label className="label" htmlFor="report-act">
                Statutory scope
              </label>
              <select
                id="report-act"
                className="input"
                value={act}
                onChange={(event) => setAct(event.target.value as Act)}
              >
                <option value="both">Both Acts</option>
                <option value="mines">Mines Act 1952 — coal mines only</option>
                <option value="factories">Factories Act 1948 — plants and processing units</option>
              </select>
            </div>
            <div>
              <label className="label" htmlFor="report-window">
                Certified within
              </label>
              <select
                id="report-window"
                className="input"
                value={days}
                onChange={(event) => setDays(Number(event.target.value))}
              >
                {WINDOWS.map((option) => (
                  <option key={option} value={option}>
                    the last {option} days
                  </option>
                ))}
              </select>
            </div>
            <button
              type="button"
              className="btn-primary w-full"
              disabled={busy !== null}
              onClick={() =>
                run('statutory', () => exports.statutory(siteId, days, act))
              }
            >
              {busy === 'statutory' ? 'Preparing…' : 'Download certification CSV'}
            </button>
          </div>
        </Card>

        <Card
          title="Hazard and near-miss log"
          footnote="Ground-level reporting from the workforce, which is the visibility DGMS currently has no source for outside accident investigations."
        >
          <div className="space-y-3">
            <div>
              <label className="label" htmlFor="hazard-window">
                Reported within
              </label>
              <select
                id="hazard-window"
                className="input"
                value={hazardDays}
                onChange={(event) => setHazardDays(Number(event.target.value))}
              >
                {WINDOWS.map((option) => (
                  <option key={option} value={option}>
                    the last {option} days
                  </option>
                ))}
              </select>
            </div>
            <button
              type="button"
              className="btn-primary w-full"
              disabled={busy !== null}
              onClick={() => run('hazards', () => exports.hazards(siteId, hazardDays))}
            >
              {busy === 'hazards' ? 'Preparing…' : 'Download hazard CSV'}
            </button>
            <p className="text-xs text-slate-500">
              Corroborated reports are exported once, with a count of the independent reports behind
              them, rather than as duplicate rows.
            </p>
          </div>
        </Card>

        <Card
          title="Certificate ledger"
          footnote="The raw chain for one site, with the verification recipe in the file header so it can be re-checked without our software."
        >
          <div className="space-y-3">
            <p className="text-sm text-slate-600">
              Requires a specific site. Select one above.
            </p>
            <button
              type="button"
              className="btn-primary w-full"
              disabled={busy !== null || !siteId}
              onClick={() => {
                if (siteId) void run('chain', () => exports.chain(siteId))
              }}
            >
              {busy === 'chain'
                ? 'Preparing…'
                : siteId
                  ? `Download ledger for ${siteId}`
                  : 'Select a site first'}
            </button>
            <p className="text-xs text-slate-500">
              Contains every field that was signed, plus the signature and both hashes, so an auditor
              can recompute the chain from the file and the site public key alone.
            </p>
          </div>
        </Card>
      </div>

      <div className="mt-4">
        <Card title="What auditors usually ask for">
          <ol className="list-decimal space-y-2 pl-5 text-sm text-slate-700">
            <li>
              <strong>Was every worker certified within the last twelve months?</strong> The
              certification CSV answers this per worker per module, with a yes/no statutory column
              and the exact expiry date.
            </li>
            <li>
              <strong>Can the records be trusted?</strong> The ledger export plus the site public key
              lets the chain be recomputed independently. Any deletion or alteration shows up as a
              broken link.
            </li>
            <li>
              <strong>Who is at risk right now?</strong> Statutory validity does not answer that, so
              the certification CSV also carries current readiness and the required action —
              refresher or full re-run — for each worker.
            </li>
            <li>
              <strong>What hazards were reported and what happened to them?</strong> The hazard CSV
              carries each report, its triage outcome and the resolution note.
            </li>
          </ol>
        </Card>
      </div>
    </>
  )
}
