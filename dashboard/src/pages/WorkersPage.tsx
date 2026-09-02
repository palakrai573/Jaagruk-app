import { useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'

import {
  BandPill,
  Card,
  HesitationPill,
  InfoNote,
  PageHeader,
  Pagination,
  QueryState,
  SiteFilter,
} from '../components/Primitives'
import { count, languageLabel, permilleToPercent } from '../lib/format'
import { useSites, useWorkers } from '../lib/queries'
import { bandForPermille } from '../lib/readiness'

const READINESS_FILTERS = [
  { value: null, label: 'All workers' },
  { value: 700, label: 'Below 70% — not ready' },
  { value: 500, label: 'Below 50% — stale or worse' },
  { value: 300, label: 'Below 30% — expired' },
] as const

export function WorkersPage() {
  const [searchParams, setSearchParams] = useSearchParams()

  const [page, setPage] = useState(1)
  const [siteId, setSiteId] = useState<string | null>(searchParams.get('site') ?? null)
  const [searchInput, setSearchInput] = useState('')
  const [search, setSearch] = useState('')
  const [readinessBelow, setReadinessBelow] = useState<number | null>(() => {
    const raw = searchParams.get('readiness_below')
    return raw ? Number(raw) : null
  })

  // Debounced so typing a name does not fire a request per keystroke.
  useEffect(() => {
    const timer = window.setTimeout(() => {
      setSearch(searchInput.trim())
      setPage(1)
    }, 300)
    return () => window.clearTimeout(timer)
  }, [searchInput])

  // Filters live in the URL so a site officer can bookmark or share "everyone below 50%".
  useEffect(() => {
    const next = new URLSearchParams()
    if (siteId) next.set('site', siteId)
    if (readinessBelow) next.set('readiness_below', String(readinessBelow))
    setSearchParams(next, { replace: true })
  }, [siteId, readinessBelow, setSearchParams])

  const sites = useSites()
  const workers = useWorkers({ page, siteId, search, readinessBelow })

  return (
    <>
      <PageHeader
        title="Workers"
        subtitle="Readiness is a decaying measure of current retention. It is deliberately not the same as statutory certification, which is pure date arithmetic."
      />

      <Card
        title="Filters"
        actions={
          <QueryState query={sites} emptyWhen={(list) => list.length === 0} emptyTitle="No sites">
            {(list) => (
              <SiteFilter
                sites={list}
                value={siteId}
                onChange={(value) => {
                  setSiteId(value)
                  setPage(1)
                }}
              />
            )}
          </QueryState>
        }
      >
        <div className="flex flex-wrap items-end gap-4">
          <div className="min-w-[16rem] flex-1">
            <label className="label" htmlFor="worker-search">
              Search by name or worker id
            </label>
            <input
              id="worker-search"
              className="input"
              placeholder="e.g. Birsa, or JH-DHN-001-W00042"
              value={searchInput}
              onChange={(event) => setSearchInput(event.target.value)}
            />
          </div>
          <div>
            <label className="label" htmlFor="readiness-filter">
              Readiness
            </label>
            <select
              id="readiness-filter"
              className="input w-64"
              value={readinessBelow ?? ''}
              onChange={(event) => {
                setReadinessBelow(event.target.value ? Number(event.target.value) : null)
                setPage(1)
              }}
            >
              {READINESS_FILTERS.map((option) => (
                <option key={option.label} value={option.value ?? ''}>
                  {option.label}
                </option>
              ))}
            </select>
          </div>
        </div>
        {readinessBelow ? (
          <div className="mt-3">
            <InfoNote>
              Readiness decays continuously, so it cannot be a database filter. The server evaluates
              it per worker and pages the filtered result, which is why the total below reflects
              exactly the workers shown rather than the unfiltered roster.
            </InfoNote>
          </div>
        ) : null}
      </Card>

      <div className="mt-4">
        <Card title="Roster">
          <QueryState
            query={workers}
            loadingLabel="Loading workers"
            emptyWhen={(data) => data.items.length === 0}
            emptyTitle="No workers match these filters"
            emptyHint="Clear the search, or widen the readiness filter."
          >
            {(data) => (
              <>
                <div className="overflow-x-auto">
                  <table>
                    <caption className="sr-only">Worker roster with readiness</caption>
                    <thead>
                      <tr>
                        <th scope="col">Worker</th>
                        <th scope="col">Site</th>
                        <th scope="col">Language</th>
                        <th scope="col">Readiness</th>
                        <th scope="col" className="text-right">
                          Modules certified
                        </th>
                        <th scope="col" className="text-right">
                          Refreshers due
                        </th>
                        <th scope="col">Flags</th>
                      </tr>
                    </thead>
                    <tbody>
                      {data.items.map((worker) => (
                        <tr key={worker.id}>
                          <td>
                            <Link
                              className="font-medium text-sky-800 underline"
                              to={`/workers/${encodeURIComponent(worker.id)}`}
                            >
                              {worker.full_name}
                            </Link>
                            <span className="mono block text-slate-500">{worker.id}</span>
                            {worker.provisional ? (
                              <span
                                className="pill mt-1 border-amber-300 bg-amber-50 text-amber-800"
                                title="Created by a sync from a device that registered this worker offline. Registration completes the record."
                              >
                                Provisional
                              </span>
                            ) : null}
                          </td>
                          <td>{worker.site_id}</td>
                          <td>
                            {languageLabel(worker.preferred_language)}
                            {worker.pictogram_mode ? (
                              <span
                                className="block text-xs text-slate-500"
                                title="Instructions render as ISO 7010 pictograms with audio narration, with no reading required."
                              >
                                Pictogram mode
                              </span>
                            ) : null}
                          </td>
                          <td>
                            <div className="flex items-center gap-2">
                              <BandPill
                                band={bandForPermille(worker.overall_readiness_permille)}
                              />
                              <span className="tabular-nums text-slate-700">
                                {permilleToPercent(worker.overall_readiness_permille)}
                              </span>
                            </div>
                          </td>
                          <td className="text-right tabular-nums">
                            {count(worker.modules_certified)}
                          </td>
                          <td className="text-right tabular-nums">{count(worker.modules_due)}</td>
                          <td>
                            <HesitationPill flagged={worker.hesitation_flagged} />
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
  )
}
