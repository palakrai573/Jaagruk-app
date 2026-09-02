import { useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'

import {
  Card,
  HashChip,
  InfoNote,
  PageHeader,
  QueryState,
  SiteFilter,
} from '../components/Primitives'
import { count, isoToDateTime, percent, permilleToPercent } from '../lib/format'
import { useDevices, useSiteCompliance, useSiteKeys, useSites } from '../lib/queries'

export function SitesPage() {
  const [searchParams] = useSearchParams()
  const [selectedSite, setSelectedSite] = useState<string | null>(
    searchParams.get('site') ?? null,
  )

  const sites = useSites()
  const compliance = useSiteCompliance()
  const devices = useDevices()
  const keys = useSiteKeys(selectedSite)

  return (
    <>
      <PageHeader
        title="Sites"
        subtitle="A site is the unit that holds a signing key, a supervisor and a certificate chain — which is why it is also the unit an inspector audits."
        actions={
          <QueryState query={sites} emptyWhen={(list) => list.length === 0} emptyTitle="No sites">
            {(list) => (
              <SiteFilter
                sites={list}
                value={selectedSite}
                onChange={setSelectedSite}
                allLabel="Select a site for key detail"
              />
            )}
          </QueryState>
        }
      />

      <Card title="Registered sites">
        <QueryState
          query={sites}
          loadingLabel="Loading sites"
          emptyWhen={(list) => list.length === 0}
          emptyTitle="No sites in your scope"
          emptyHint="A company administrator creates sites; supervisors then register their handsets against them."
        >
          {(list) => (
            <div className="overflow-x-auto">
              <table>
                <caption className="sr-only">Sites visible to you</caption>
                <thead>
                  <tr>
                    <th scope="col">Site</th>
                    <th scope="col">District</th>
                    <th scope="col">Sector</th>
                    <th scope="col">AR site scan</th>
                    <th scope="col" className="text-right">
                      Workers
                    </th>
                    <th scope="col" className="text-right">
                      Certified
                    </th>
                    <th scope="col" className="text-right">
                      Readiness
                    </th>
                    <th scope="col">Ledger</th>
                  </tr>
                </thead>
                <tbody>
                  {list.map((site) => {
                    const row = compliance.data?.find((entry) => entry.site_id === site.id)
                    return (
                      <tr key={site.id}>
                        <td>
                          <button
                            type="button"
                            className="font-medium text-sky-800 underline"
                            onClick={() => setSelectedSite(site.id)}
                          >
                            {site.id}
                          </button>
                          <span className="block text-xs text-slate-500">{site.name}</span>
                        </td>
                        <td>{site.district}</td>
                        <td className="capitalize">{site.sector.replace(/_/g, ' ')}</td>
                        <td>
                          {site.ar_scanned ? (
                            <span className="text-xs text-emerald-700">
                              ✓ {count(site.ar_anchor_count)} anchors
                            </span>
                          ) : (
                            <span className="text-xs text-slate-500">
                              ○ Generic room template
                            </span>
                          )}
                        </td>
                        <td className="text-right tabular-nums">{count(row?.worker_count ?? 0)}</td>
                        <td className="text-right tabular-nums">
                          {percent(row?.certified_worker_percent ?? 0)}
                        </td>
                        <td className="text-right tabular-nums">
                          {permilleToPercent(row?.mean_readiness_permille ?? 0)}
                        </td>
                        <td>
                          <Link
                            className="text-xs text-sky-800 underline"
                            to={`/chain?site=${site.id}`}
                          >
                            Chain integrity
                          </Link>
                        </td>
                      </tr>
                    )
                  })}
                </tbody>
              </table>
            </div>
          )}
        </QueryState>
      </Card>

      {!selectedSite ? (
        <div className="mt-4">
          <InfoNote>
            Select a site above to see its signing-key epochs. Every epoch a site has ever used is
            retained, because a certificate issued under a previous key must stay verifiable
            forever — losing a supervisor's handset is not a reason to invalidate the certificates
            already earned.
          </InfoNote>
        </div>
      ) : (
        <div className="mt-4">
          <Card
            title={`Signing keys — ${selectedSite}`}
            footnote="A verifier needs the public key only. Private keys never leave the supervisor handset, which is what lets certificates be issued with no connectivity at all."
          >
            <QueryState
              query={keys}
              loadingLabel="Loading key epochs"
              emptyWhen={(data) => data.keys.length === 0}
              emptyTitle="No signing key registered for this site"
              emptyHint="A supervisor registers their handset from the Android app, which publishes the site public key."
            >
              {(data) => (
                <div className="overflow-x-auto">
                  <table>
                    <caption className="sr-only">Ed25519 key epochs</caption>
                    <thead>
                      <tr>
                        <th scope="col">Epoch</th>
                        <th scope="col">Status</th>
                        <th scope="col">Ed25519 public key</th>
                        <th scope="col">Registered</th>
                        <th scope="col">Revoked</th>
                      </tr>
                    </thead>
                    <tbody>
                      {data.keys.map((key) => (
                        <tr key={key.epoch}>
                          <td className="tabular-nums">{key.epoch}</td>
                          <td>
                            {key.active ? (
                              <span className="pill border-emerald-300 bg-emerald-50 text-emerald-800">
                                ✓ Active
                              </span>
                            ) : (
                              <span className="pill border-slate-300 bg-slate-50 text-slate-600">
                                Archived
                              </span>
                            )}
                          </td>
                          <td>
                            <HashChip hex={key.public_key_hex} length={20} />
                          </td>
                          <td className="text-xs">{isoToDateTime(key.registered_at_iso)}</td>
                          <td className="text-xs">
                            {key.revoked_at_iso ? (
                              <>
                                {isoToDateTime(key.revoked_at_iso)}
                                {key.revocation_reason ? (
                                  <span className="block text-slate-500">
                                    {key.revocation_reason}
                                  </span>
                                ) : null}
                              </>
                            ) : (
                              '—'
                            )}
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
      )}

      <div className="mt-4">
        <Card
          title="Registered handsets"
          footnote="Device trust is separate from login credentials: a device-bound Keystore key signs sync uploads, so a leaked password alone cannot forge certificates."
        >
          <QueryState
            query={devices}
            loadingLabel="Loading devices"
            emptyWhen={(list) => list.length === 0}
            emptyTitle="No handsets registered yet"
            emptyHint="A supervisor registers a device from the Android app before it can upload."
          >
            {(list) => (
              <div className="overflow-x-auto">
                <table>
                  <caption className="sr-only">Registered devices</caption>
                  <thead>
                    <tr>
                      <th scope="col">Device</th>
                      <th scope="col">Site</th>
                      <th scope="col">Model</th>
                      <th scope="col">Android</th>
                      <th scope="col">App</th>
                      <th scope="col">Last sync</th>
                    </tr>
                  </thead>
                  <tbody>
                    {list
                      .filter((device) => !selectedSite || device.site_id === selectedSite)
                      .map((device) => (
                        <tr key={device.id}>
                          <td>
                            <HashChip hex={device.id} length={18} />
                          </td>
                          <td>{device.site_id}</td>
                          <td>{device.model ?? '—'}</td>
                          <td>{device.android_release ?? '—'}</td>
                          <td>{device.app_version ?? '—'}</td>
                          <td className="text-xs">
                            {device.last_sync_at_iso ? (
                              isoToDateTime(device.last_sync_at_iso)
                            ) : (
                              <span
                                className="text-slate-500"
                                title="Normal for a handset that has been working offline. Records are held on the device and upload when connectivity returns."
                              >
                                Never synced
                              </span>
                            )}
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
  )
}
