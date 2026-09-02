import { useState } from 'react'
import { Link } from 'react-router-dom'

import {
  BandPill,
  Card,
  InfoNote,
  PageHeader,
  Pagination,
  QueryState,
  SiteFilter,
  StatutoryPill,
} from '../components/Primitives'
import { count, epochToDate, millis, permilleToPercent } from '../lib/format'
import { useHesitationRisk, useSites } from '../lib/queries'
import { bandForPermille } from '../lib/readiness'

export function HesitationRiskPage() {
  const [page, setPage] = useState(1)
  const [siteId, setSiteId] = useState<string | null>(null)

  const sites = useSites()
  const cohort = useHesitationRisk(page, siteId)

  return (
    <>
      <PageHeader
        title="Hesitation risk"
        subtitle="Workers who answer correctly but slowly. A conventional quiz records them as competent; their decision latency says they may freeze when it counts."
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
      />

      <div className="mb-4">
        <InfoNote>
          <strong>Why this list exists.</strong> Every assessment records not just which answer a
          worker chose but how long they took to choose it, measured against a calibrated expert
          baseline. Freezing during an evacuation is a documented failure mode independent of
          knowledge, so a correct-but-slow answer is scored as <em>correct with hesitation</em>{' '}
          rather than simply correct. Everyone here passed. The intervention is repeated drilling
          under time pressure, not re-teaching the material.
        </InfoNote>
      </div>

      <Card title="Flagged assessments">
        <QueryState
          query={cohort}
          loadingLabel="Loading cohort"
          emptyWhen={(data) => data.items.length === 0}
          emptyTitle="No hesitation flags in scope"
          emptyHint="Either decision times are within the expected window, or no assessments have synced yet."
        >
          {(data) => (
            <>
              <div className="overflow-x-auto">
                <table>
                  <caption className="sr-only">
                    Workers flagged for slow decision-making, most recent attempt first
                  </caption>
                  <thead>
                    <tr>
                      <th scope="col">Worker</th>
                      <th scope="col">Site</th>
                      <th scope="col">Module</th>
                      <th scope="col" className="text-right">
                        Score
                      </th>
                      <th scope="col" className="text-right">
                        Median decision
                      </th>
                      <th scope="col" className="text-right">
                        Pace
                      </th>
                      <th scope="col" className="text-right">
                        Hesitant steps
                      </th>
                      <th scope="col">Current status</th>
                      <th scope="col">Last attempt</th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.items.map((entry) => (
                      <tr key={`${entry.worker_id}:${entry.module_id}`}>
                        <td>
                          <Link
                            className="font-medium text-sky-800 underline"
                            to={`/workers/${encodeURIComponent(entry.worker_id)}`}
                          >
                            {entry.worker_full_name}
                          </Link>
                          <span className="mono block text-slate-500">{entry.worker_id}</span>
                        </td>
                        <td>
                          {entry.site_id}
                          <span className="block text-xs text-slate-500">{entry.site_name}</span>
                        </td>
                        <td>{entry.module_title_en}</td>
                        <td className="text-right tabular-nums">
                          {permilleToPercent(entry.score_permille)}
                        </td>
                        <td className="text-right tabular-nums">
                          {millis(entry.median_latency_ms)}
                        </td>
                        <td className="text-right tabular-nums">
                          <span
                            className={
                              entry.pace_multiple >= 2
                                ? 'font-semibold text-orange-800'
                                : 'text-slate-700'
                            }
                            title="Median decision time as a multiple of the expert baseline for this scenario. 1.0 is on pace."
                          >
                            {entry.pace_multiple > 0 ? `${entry.pace_multiple.toFixed(2)}×` : '—'}
                          </span>
                        </td>
                        <td className="text-right tabular-nums">
                          {count(entry.hesitant_step_count)} / {count(entry.total_step_count)}
                        </td>
                        <td>
                          <div className="flex flex-col gap-1">
                            <BandPill band={bandForPermille(entry.readiness_permille)} />
                            <StatutoryPill valid={entry.statutory_valid} />
                          </div>
                        </td>
                        <td className="text-xs">{epochToDate(entry.last_attempt_at_sec)}</td>
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
    </>
  )
}
