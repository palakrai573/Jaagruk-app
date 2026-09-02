import { useState } from 'react'
import { Link } from 'react-router-dom'
import {
  Bar,
  BarChart,
  CartesianGrid,
  Legend,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'

import {
  Card,
  EmptyState,
  KpiCard,
  PageHeader,
  QueryState,
  SiteFilter,
  WarningNote,
} from '../components/Primitives'
import { count, epochToDate, percent, permilleToPercent } from '../lib/format'
import { useOverview, useReadinessTrend, useSiteCompliance, useSites } from '../lib/queries'

const TREND_OPTIONS = [14, 30, 90, 180] as const

export function OverviewPage() {
  const [siteId, setSiteId] = useState<string | null>(null)
  const [days, setDays] = useState<number>(30)

  const overview = useOverview()
  const bySite = useSiteCompliance()
  const trend = useReadinessTrend(days, siteId)
  const sites = useSites()

  return (
    <>
      <PageHeader
        title="Compliance overview"
        subtitle={
          <>
            Statutory certification and operational readiness are shown as two separate figures and
            are never blended. A certificate can be legally current while the worker's retention has
            decayed — that combination is the cohort most at risk, and merging the numbers would
            hide it.
          </>
        }
      />

      <QueryState query={overview} loadingLabel="Loading compliance figures">
        {(data) => (
          <>
            <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
              <KpiCard
                label="Workers"
                value={count(data.worker_count)}
                hint={`across ${count(data.site_count)} site${data.site_count === 1 ? '' : 's'}`}
              />
              <KpiCard
                label="Statutorily certified"
                value={percent(data.certified_worker_percent)}
                hint="at least one certificate issued within the last 365 days"
                tone={data.certified_worker_percent >= 80 ? 'good' : 'warn'}
              />
              <KpiCard
                label="Mean readiness"
                value={permilleToPercent(data.mean_readiness_permille)}
                hint="current retention, after decay"
                tone={
                  data.mean_readiness_permille >= 700
                    ? 'good'
                    : data.mean_readiness_permille >= 500
                      ? 'warn'
                      : 'bad'
                }
              />
              <KpiCard
                label="Certificates issued"
                value={count(data.certificate_count)}
                hint={
                  data.quarantined_certificate_count > 0
                    ? `${count(data.quarantined_certificate_count)} quarantined`
                    : 'none quarantined'
                }
                tone={data.quarantined_certificate_count > 0 ? 'bad' : 'neutral'}
              />
            </div>

            {data.statutorily_valid_but_stale > 0 ? (
              <div className="mt-4">
                <WarningNote>
                  <strong>{count(data.statutorily_valid_but_stale)}</strong> worker
                  {data.statutorily_valid_but_stale === 1 ? ' is' : 's are'} statutorily certified
                  but operationally stale — legally clear to work, and unlikely to react correctly
                  under pressure. This is the group to schedule refreshers for first.{' '}
                  <Link className="underline" to="/workers?readiness_below=500">
                    Review them
                  </Link>
                  .
                </WarningNote>
              </div>
            ) : null}

            {data.quarantined_certificate_count > 0 ? (
              <div className="mt-3">
                <WarningNote>
                  <strong>{count(data.quarantined_certificate_count)}</strong> certificate
                  {data.quarantined_certificate_count === 1 ? '' : 's'} failed chain verification on
                  ingest and {data.quarantined_certificate_count === 1 ? 'was' : 'were'} quarantined
                  rather than discarded — the evidence is retained deliberately.{' '}
                  <Link className="underline" to="/chain">
                    Inspect chain integrity
                  </Link>
                  .
                </WarningNote>
              </div>
            ) : null}

            <div className="mt-5 grid gap-4 lg:grid-cols-2">
              <Card
                title="Readiness distribution"
                footnote="Bands: Ready ≥ 70%, Refresher due 50–69%, Stale 30–49%, Expired < 30%. A worker's band is their worst module."
              >
                <ResponsiveContainer width="100%" height={240}>
                  <BarChart
                    data={[
                      { band: 'Ready', workers: data.workers_ready, fill: '#059669' },
                      { band: 'Due', workers: data.workers_due, fill: '#d97706' },
                      { band: 'Stale', workers: data.workers_stale, fill: '#ea580c' },
                      { band: 'Expired', workers: data.workers_expired, fill: '#dc2626' },
                    ]}
                    margin={{ top: 8, right: 8, bottom: 0, left: -20 }}
                  >
                    <CartesianGrid strokeDasharray="3 3" stroke="#e2e8f0" />
                    <XAxis dataKey="band" tick={{ fontSize: 12 }} />
                    <YAxis allowDecimals={false} tick={{ fontSize: 12 }} />
                    <Tooltip formatter={(value) => [`${value} workers`, '']} />
                    <Bar dataKey="workers" name="Workers" radius={[4, 4, 0, 0]} fill="#0369a1" />
                  </BarChart>
                </ResponsiveContainer>
                <dl className="mt-3 grid grid-cols-2 gap-2 text-xs text-slate-600">
                  <div className="flex justify-between">
                    <dt>Never certified</dt>
                    <dd className="font-medium tabular-nums">
                      {count(data.workers_never_certified)}
                    </dd>
                  </div>
                  <div className="flex justify-between">
                    <dt>Refreshers due now</dt>
                    <dd className="font-medium tabular-nums">{count(data.refreshers_due_count)}</dd>
                  </div>
                </dl>
              </Card>

              <Card
                title="Action queue"
                footnote="Each figure links to the page where it can be acted on."
              >
                <ul className="divide-y divide-slate-200 text-sm">
                  <li className="flex items-center justify-between py-2">
                    <span>Hesitation-risk assessments</span>
                    <Link className="font-medium tabular-nums text-sky-800 underline" to="/hesitation-risk">
                      {count(data.hesitation_risk_count)}
                    </Link>
                  </li>
                  <li className="flex items-center justify-between py-2">
                    <span>Open hazard reports</span>
                    <Link className="font-medium tabular-nums text-sky-800 underline" to="/hazards">
                      {count(data.open_hazard_count)}
                    </Link>
                  </li>
                  <li className="flex items-center justify-between py-2">
                    <span>Critical hazards outstanding</span>
                    <Link
                      className="font-medium tabular-nums text-sky-800 underline"
                      to="/hazards?severity=critical"
                    >
                      {count(data.critical_hazard_count)}
                    </Link>
                  </li>
                  <li className="flex items-center justify-between py-2">
                    <span>Quarantined certificates</span>
                    <Link className="font-medium tabular-nums text-sky-800 underline" to="/chain">
                      {count(data.quarantined_certificate_count)}
                    </Link>
                  </li>
                </ul>
                <p className="mt-3 text-xs text-slate-500">
                  Figures computed {epochToDate(data.generated_at_sec)}.
                </p>
              </Card>
            </div>
          </>
        )}
      </QueryState>

      <div className="mt-4">
        <Card
          title="Readiness over time"
          actions={
            <div className="flex flex-wrap items-center gap-3">
              <QueryState query={sites} emptyWhen={(list) => list.length === 0} emptyTitle="No sites">
                {(list) => <SiteFilter sites={list} value={siteId} onChange={setSiteId} />}
              </QueryState>
              <label className="flex items-center gap-2 text-sm">
                <span className="text-slate-600">Window</span>
                <select
                  className="input w-28"
                  value={days}
                  onChange={(event) => setDays(Number(event.target.value))}
                >
                  {TREND_OPTIONS.map((option) => (
                    <option key={option} value={option}>
                      {option} days
                    </option>
                  ))}
                </select>
              </label>
            </div>
          }
          footnote="Readiness is evaluated as of each day, not back-projected from today, so a genuine decline is visible rather than smoothed away."
        >
          <QueryState
            query={trend}
            loadingLabel="Loading trend"
            emptyWhen={(data) => data.points.every((point) => point.mean_readiness_permille === 0)}
            emptyTitle="No certified workers in this window yet"
            emptyHint="The line appears once certificates have been issued and synced."
          >
            {(data) => (
              <ResponsiveContainer width="100%" height={300}>
                <LineChart
                  data={data.points.map((point) => ({
                    date: epochToDate(point.day_epoch_sec),
                    readiness: point.mean_readiness_permille / 10,
                    certificates: point.certificates_issued,
                    hesitant: point.hesitation_flagged,
                  }))}
                  margin={{ top: 8, right: 16, bottom: 0, left: -20 }}
                >
                  <CartesianGrid strokeDasharray="3 3" stroke="#e2e8f0" />
                  <XAxis dataKey="date" tick={{ fontSize: 11 }} minTickGap={24} />
                  <YAxis yAxisId="left" domain={[0, 100]} tick={{ fontSize: 11 }} unit="%" />
                  <YAxis
                    yAxisId="right"
                    orientation="right"
                    allowDecimals={false}
                    tick={{ fontSize: 11 }}
                  />
                  <Tooltip />
                  <Legend wrapperStyle={{ fontSize: 12 }} />
                  <Line
                    yAxisId="left"
                    type="monotone"
                    dataKey="readiness"
                    name="Mean readiness (%)"
                    stroke="#0369a1"
                    strokeWidth={2}
                    dot={false}
                  />
                  <Line
                    yAxisId="right"
                    type="monotone"
                    dataKey="certificates"
                    name="Certificates issued"
                    stroke="#059669"
                    strokeWidth={1.5}
                    dot={false}
                  />
                  <Line
                    yAxisId="right"
                    type="monotone"
                    dataKey="hesitant"
                    name="Hesitation flagged"
                    stroke="#ea580c"
                    strokeWidth={1.5}
                    strokeDasharray="4 3"
                    dot={false}
                  />
                </LineChart>
              </ResponsiveContainer>
            )}
          </QueryState>
        </Card>
      </div>

      <div className="mt-4">
        <Card title="Sites at a glance">
          <QueryState
            query={bySite}
            loadingLabel="Loading sites"
            emptyWhen={(rows) => rows.length === 0}
            emptyTitle="No sites in your scope"
            emptyHint="A company administrator creates sites before any training can be recorded."
          >
            {(rows) => (
              <div className="overflow-x-auto">
                <table>
                  <caption className="sr-only">Per-site compliance summary</caption>
                  <thead>
                    <tr>
                      <th scope="col">Site</th>
                      <th scope="col">District</th>
                      <th scope="col">AR scan</th>
                      <th scope="col" className="text-right">
                        Workers
                      </th>
                      <th scope="col" className="text-right">
                        Certified
                      </th>
                      <th scope="col" className="text-right">
                        Readiness
                      </th>
                      <th scope="col" className="text-right">
                        Refreshers due
                      </th>
                      <th scope="col" className="text-right">
                        Hesitation
                      </th>
                      <th scope="col" className="text-right">
                        Open hazards
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    {rows.map((row) => (
                      <tr key={row.site_id}>
                        <td>
                          <Link
                            className="font-medium text-sky-800 underline"
                            to={`/sites?site=${row.site_id}`}
                          >
                            {row.site_id}
                          </Link>
                          <span className="block text-xs text-slate-500">{row.site_name}</span>
                        </td>
                        <td>{row.district}</td>
                        <td>
                          {row.ar_scanned ? (
                            <span className="text-xs text-emerald-700">✓ Scanned</span>
                          ) : (
                            <span
                              className="text-xs text-slate-500"
                              title="Drills fall back to a generic room template until a supervisor scans this site."
                            >
                              ○ Generic
                            </span>
                          )}
                        </td>
                        <td className="text-right tabular-nums">{count(row.worker_count)}</td>
                        <td className="text-right tabular-nums">
                          {percent(row.certified_worker_percent)}
                        </td>
                        <td className="text-right tabular-nums">
                          {permilleToPercent(row.mean_readiness_permille)}
                        </td>
                        <td className="text-right tabular-nums">
                          {count(row.refreshers_due_count)}
                        </td>
                        <td className="text-right tabular-nums">
                          {count(row.hesitation_risk_count)}
                        </td>
                        <td className="text-right tabular-nums">{count(row.open_hazard_count)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </QueryState>
        </Card>
      </div>

      {overview.data && overview.data.worker_count === 0 ? (
        <div className="mt-4">
          <EmptyState
            title="No workers registered yet"
            hint="Register workers from the Jaagruk Android app, or run the backend seed script to load a demo dataset."
          />
        </div>
      ) : null}
    </>
  )
}
