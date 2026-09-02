import 'leaflet/dist/leaflet.css'

import { useMemo, useState } from 'react'
import { CircleMarker, MapContainer, Popup, TileLayer, Tooltip } from 'react-leaflet'

import {
  Card,
  HazardStatusPill,
  InfoNote,
  PageHeader,
  Pagination,
  QueryState,
  SeverityPill,
  SiteFilter,
} from '../components/Primitives'
import { useAuth } from '../lib/auth'
import { count, epochToDateTime, humaniseSlug, isoToDateTime } from '../lib/format'
import {
  useHazards,
  useHazardsWithoutCoordinates,
  useSites,
  useTriageHazard,
} from '../lib/queries'
import type { Hazard, HazardStatus } from '../lib/types'

/** Jharkhand, roughly centred, for the initial view before any pin is known. */
const JHARKHAND_CENTRE: [number, number] = [23.6102, 85.2799]

const SEVERITY_COLOURS: Record<string, string> = {
  low: '#64748b',
  medium: '#d97706',
  high: '#ea580c',
  critical: '#dc2626',
}

const SEVERITY_RADII: Record<string, number> = {
  low: 6,
  medium: 8,
  high: 10,
  critical: 13,
}

function HazardDetail({
  hazard,
  canTriage,
}: {
  hazard: Hazard
  canTriage: boolean
}) {
  const triage = useTriageHazard()
  const [note, setNote] = useState('')
  const [conflict, setConflict] = useState<string | null>(null)

  const act = (status: HazardStatus) => {
    setConflict(null)
    triage.mutate(
      {
        hazardId: hazard.id,
        status,
        note,
        expectedUpdatedAtIso: hazard.updated_at_iso,
      },
      {
        onError: (error) => setConflict(error.message),
      },
    )
  }

  return (
    <div className="min-w-[16rem] space-y-2 text-sm">
      <div className="flex flex-wrap items-center gap-2">
        <SeverityPill severity={hazard.severity} />
        <HazardStatusPill status={hazard.status} />
      </div>
      <p className="font-medium">{humaniseSlug(hazard.category)}</p>
      {hazard.note ? <p className="text-slate-700">{hazard.note}</p> : null}

      <dl className="space-y-1 text-xs text-slate-600">
        <div className="flex justify-between gap-2">
          <dt>Site</dt>
          <dd>{hazard.site_id}</dd>
        </div>
        <div className="flex justify-between gap-2">
          <dt>Reported by</dt>
          <dd>{hazard.reporter_label}</dd>
        </div>
        <div className="flex justify-between gap-2">
          <dt>Reported</dt>
          <dd>{epochToDateTime(hazard.reported_at_sec)}</dd>
        </div>
        {hazard.zone_label ? (
          <div className="flex justify-between gap-2">
            <dt>Zone</dt>
            <dd>{hazard.zone_label}</dd>
          </div>
        ) : null}
        {hazard.duplicate_count > 0 ? (
          <div className="flex justify-between gap-2">
            <dt>Corroborating reports</dt>
            <dd title="Other workers independently flagged the same thing. Corroboration raises the recorded severity.">
              {count(hazard.duplicate_count)}
            </dd>
          </div>
        ) : null}
        {hazard.resolution_note ? (
          <div>
            <dt className="font-medium">Resolution</dt>
            <dd>{hazard.resolution_note}</dd>
          </div>
        ) : null}
      </dl>

      {canTriage && hazard.allowed_next_statuses.length > 0 ? (
        <div className="space-y-2 border-t border-slate-200 pt-2">
          <label className="label" htmlFor={`note-${hazard.id}`}>
            Note (optional)
          </label>
          <input
            id={`note-${hazard.id}`}
            className="input"
            value={note}
            onChange={(event) => setNote(event.target.value)}
            placeholder="What was done, or why this is not valid"
          />
          <div className="flex flex-wrap gap-2">
            {hazard.allowed_next_statuses.map((status) => (
              <button
                key={status}
                type="button"
                className={status === 'invalid' ? 'btn-secondary' : 'btn-primary'}
                disabled={triage.isPending}
                onClick={() => act(status)}
              >
                {status === 'invalid' ? 'Mark not valid' : `Set ${humaniseSlug(status)}`}
              </button>
            ))}
          </div>
          {conflict ? (
            <p role="alert" className="text-xs text-red-800">
              {conflict}
            </p>
          ) : null}
        </div>
      ) : hazard.allowed_next_statuses.length === 0 ? (
        <p className="border-t border-slate-200 pt-2 text-xs text-slate-500">
          This hazard is closed. Reopening is deliberately not possible — file a fresh report so the
          original stays on the record.
        </p>
      ) : (
        <p className="border-t border-slate-200 pt-2 text-xs text-slate-500">
          Triage requires a site officer or company administrator account.
        </p>
      )}
    </div>
  )
}

export function HazardMapPage() {
  const { can } = useAuth()
  const canTriage = can('triage_hazards')

  const [page, setPage] = useState(1)
  const [siteId, setSiteId] = useState<string | null>(null)
  const [status, setStatus] = useState<HazardStatus | null>(null)
  const [severity, setSeverity] = useState<string | null>(null)

  const sites = useSites()
  const hazards = useHazards({ page, siteId, status, severity, bbox: null })
  const unplotted = useHazardsWithoutCoordinates(siteId)

  const plotted = useMemo(
    () =>
      (hazards.data?.items ?? []).filter(
        (hazard) => hazard.latitude !== null && hazard.longitude !== null,
      ),
    [hazards.data],
  )

  const centre = useMemo<[number, number]>(() => {
    const first = plotted[0]
    if (first?.latitude != null && first.longitude != null) {
      return [first.latitude, first.longitude]
    }
    const site = sites.data?.find((entry) => entry.latitude != null && entry.longitude != null)
    if (site?.latitude != null && site.longitude != null) return [site.latitude, site.longitude]
    return JHARKHAND_CENTRE
  }, [plotted, sites.data])

  return (
    <>
      <PageHeader
        title="Hazard map"
        subtitle="Near-miss and unsafe-condition reports filed by workers from the Android app. This is ground-level reporting from the workforce, rather than after-the-fact accident investigation."
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

      <Card title="Filters">
        <div className="flex flex-wrap items-end gap-4">
          <div>
            <label className="label" htmlFor="hazard-status">
              Status
            </label>
            <select
              id="hazard-status"
              className="input w-48"
              value={status ?? ''}
              onChange={(event) => {
                setStatus((event.target.value || null) as HazardStatus | null)
                setPage(1)
              }}
            >
              <option value="">Any status</option>
              <option value="open">Open</option>
              <option value="acknowledged">Acknowledged</option>
              <option value="in_progress">In progress</option>
              <option value="resolved">Resolved</option>
              <option value="invalid">Not valid</option>
            </select>
          </div>
          <div>
            <label className="label" htmlFor="hazard-severity">
              Severity
            </label>
            <select
              id="hazard-severity"
              className="input w-48"
              value={severity ?? ''}
              onChange={(event) => {
                setSeverity(event.target.value || null)
                setPage(1)
              }}
            >
              <option value="">Any severity</option>
              <option value="critical">Critical</option>
              <option value="high">High</option>
              <option value="medium">Medium</option>
              <option value="low">Low</option>
            </select>
          </div>
        </div>
      </Card>

      <div className="mt-4 grid gap-4 lg:grid-cols-3">
        <div className="lg:col-span-2">
          <Card
            title="Located reports"
            footnote="Marker size and colour both encode severity, and every popup states it in words as well."
          >
            <div className="h-[26rem] overflow-hidden rounded-md border border-slate-200">
              <MapContainer center={centre} zoom={11} scrollWheelZoom>
                <TileLayer
                  attribution='&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
                  url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"
                  maxZoom={19}
                />
                {plotted.map((hazard) => (
                  <CircleMarker
                    key={hazard.id}
                    center={[hazard.latitude as number, hazard.longitude as number]}
                    radius={SEVERITY_RADII[hazard.severity] ?? 8}
                    pathOptions={{
                      color: SEVERITY_COLOURS[hazard.severity] ?? '#64748b',
                      fillColor: SEVERITY_COLOURS[hazard.severity] ?? '#64748b',
                      fillOpacity: hazard.status === 'resolved' ? 0.25 : 0.7,
                      weight: 2,
                    }}
                  >
                    <Tooltip>
                      {humaniseSlug(hazard.category)} — {hazard.severity}
                    </Tooltip>
                    <Popup>
                      <HazardDetail hazard={hazard} canTriage={canTriage} />
                    </Popup>
                  </CircleMarker>
                ))}
              </MapContainer>
            </div>
            {plotted.length === 0 && !hazards.isPending ? (
              <div className="mt-3">
                <InfoNote>
                  No reports in this filter have coordinates. Underground reports usually do not —
                  see the list beside the map.
                </InfoNote>
              </div>
            ) : null}
          </Card>
        </div>

        <Card
          title="Reported without coordinates"
          footnote="GPS is unreliable underground, so a supervisor-defined zone label carries the location instead. These reports are listed rather than dropped: a hazard that cannot be plotted still has to be visible."
        >
          <QueryState
            query={unplotted}
            loadingLabel="Loading"
            emptyWhen={(data) => data.items.length === 0}
            emptyTitle="None"
            emptyHint="Every current report has a coordinate fix."
          >
            {(data) => (
              <ul className="max-h-[24rem] space-y-3 overflow-y-auto pr-1">
                {data.items.map((hazard) => (
                  <li key={hazard.id} className="rounded-md border border-slate-200 p-3">
                    <div className="flex flex-wrap items-center gap-2">
                      <SeverityPill severity={hazard.severity} />
                      <HazardStatusPill status={hazard.status} />
                    </div>
                    <p className="mt-1 text-sm font-medium">{humaniseSlug(hazard.category)}</p>
                    <p className="text-xs text-slate-600">
                      {hazard.zone_label ?? 'Zone not recorded'} · {hazard.site_id}
                    </p>
                    {hazard.note ? (
                      <p className="mt-1 text-xs text-slate-700">{hazard.note}</p>
                    ) : null}
                    <p className="mt-1 text-xs text-slate-500">
                      {hazard.reporter_label} · {epochToDateTime(hazard.reported_at_sec)}
                    </p>
                    {canTriage ? (
                      <details className="mt-2">
                        <summary className="cursor-pointer text-xs font-medium text-sky-800">
                          Triage
                        </summary>
                        <div className="mt-2">
                          <HazardDetail hazard={hazard} canTriage={canTriage} />
                        </div>
                      </details>
                    ) : null}
                  </li>
                ))}
              </ul>
            )}
          </QueryState>
        </Card>
      </div>

      <div className="mt-4">
        <Card title="All reports">
          <QueryState
            query={hazards}
            loadingLabel="Loading hazards"
            emptyWhen={(data) => data.items.length === 0}
            emptyTitle="No hazard reports match these filters"
            emptyHint="Workers file reports from the Android app; they upload with the next sync."
          >
            {(data) => (
              <>
                <div className="overflow-x-auto">
                  <table>
                    <caption className="sr-only">Hazard reports</caption>
                    <thead>
                      <tr>
                        <th scope="col">Category</th>
                        <th scope="col">Severity</th>
                        <th scope="col">Status</th>
                        <th scope="col">Site</th>
                        <th scope="col">Location</th>
                        <th scope="col">Reported by</th>
                        <th scope="col">Reported</th>
                        <th scope="col">Last change</th>
                      </tr>
                    </thead>
                    <tbody>
                      {data.items.map((hazard) => (
                        <tr key={hazard.id}>
                          <td>
                            {humaniseSlug(hazard.category)}
                            {hazard.duplicate_count > 0 ? (
                              <span
                                className="ml-1 text-xs text-slate-500"
                                title="Independently reported by more than one worker."
                              >
                                +{hazard.duplicate_count}
                              </span>
                            ) : null}
                          </td>
                          <td>
                            <SeverityPill severity={hazard.severity} />
                          </td>
                          <td>
                            <HazardStatusPill status={hazard.status} />
                          </td>
                          <td>{hazard.site_id}</td>
                          <td className="text-xs">
                            {hazard.latitude != null && hazard.longitude != null
                              ? `${hazard.latitude.toFixed(4)}, ${hazard.longitude.toFixed(4)}`
                              : (hazard.zone_label ?? 'Not recorded')}
                          </td>
                          <td className="text-xs">{hazard.reporter_label}</td>
                          <td className="text-xs">{epochToDateTime(hazard.reported_at_sec)}</td>
                          <td className="text-xs">{isoToDateTime(hazard.updated_at_iso)}</td>
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
