import { Card, InfoNote, PageHeader, QueryState } from '../components/Primitives'
import { useModules } from '../lib/queries'

export function ModulesPage() {
  const modules = useModules()

  return (
    <>
      <PageHeader
        title="Module catalog"
        subtitle="The five safety domains the platform certifies. Scenario content is compiled into the Android app so it works offline from first launch; this list exists so reports can cite the statutory hook and an operator can disable a module without shipping a build."
      />

      <div className="mb-4">
        <InfoNote>
          Two modules — Fire &amp; Explosion Response and Gas Leak &amp; Confined Space — ship as
          complete AR experiences with bespoke scenes, refreshers and (for the gas module) a real
          two-device buddy drill. The other three are complete as assessable content and run through
          the same scoring engine; their bespoke AR scenes are the documented next increment. That
          distinction is stated here rather than implied.
        </InfoNote>
      </div>

      <Card title="Modules">
        <QueryState
          query={modules}
          loadingLabel="Loading catalog"
          emptyWhen={(list) => list.length === 0}
          emptyTitle="No modules registered on this server"
          emptyHint="Run the backend seed script, or let a device bootstrap publish the catalog."
        >
          {(list) => (
            <div className="overflow-x-auto">
              <table>
                <caption className="sr-only">Safety modules</caption>
                <thead>
                  <tr>
                    <th scope="col">Code</th>
                    <th scope="col">Module</th>
                    <th scope="col">Statutory reference</th>
                    <th scope="col" className="text-right">
                      Duration
                    </th>
                    <th scope="col">Buddy drill</th>
                    <th scope="col">AR scenes</th>
                    <th scope="col">Enabled</th>
                  </tr>
                </thead>
                <tbody>
                  {list.map((module) => (
                    <tr key={module.id}>
                      <td className="tabular-nums">{module.module_code}</td>
                      <td>
                        <span className="font-medium">{module.title_en}</span>
                        <span className="mono block text-slate-500">{module.id}</span>
                      </td>
                      <td className="text-xs">{module.statutory_reference}</td>
                      <td className="text-right tabular-nums">~{module.estimated_minutes} min</td>
                      <td>
                        {module.supports_buddy_drill ? (
                          <span
                            className="pill border-sky-300 bg-sky-50 text-sky-800"
                            title="Runs across two real handsets over Nearby Connections, with no internet or cell signal. The buddy system is two humans coordinating; an NPC buddy would train none of it."
                          >
                            Two devices
                          </span>
                        ) : (
                          <span className="text-xs text-slate-500">Single device</span>
                        )}
                      </td>
                      <td>
                        {module.fully_implemented ? (
                          <span className="pill border-emerald-300 bg-emerald-50 text-emerald-800">
                            ✓ Complete
                          </span>
                        ) : (
                          <span
                            className="pill border-slate-300 bg-slate-50 text-slate-600"
                            title="Assessable content is complete and scored by the same engine; the bespoke AR scene is the next increment."
                          >
                            Content only
                          </span>
                        )}
                      </td>
                      <td>
                        {module.enabled ? (
                          <span className="text-xs text-emerald-700">Enabled</span>
                        ) : (
                          <span className="text-xs text-slate-500">Disabled</span>
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

      <div className="mt-4">
        <Card title="How a module is scored">
          <dl className="space-y-3 text-sm">
            <div>
              <dt className="font-medium text-slate-800">Accuracy dominates, speed matters</dt>
              <dd className="text-slate-600">
                Each step scores <span className="mono">accuracy × (0.70 + 0.30 × speed)</span>. A
                correct answer never scores below 70%, however slow; a wrong answer scores zero,
                however fast.
              </dd>
            </div>
            <div>
              <dt className="font-medium text-slate-800">No partial credit</dt>
              <dd className="text-slate-600">
                Selecting four of the five required PPE items for a confined-space entry is not 80%
                safe — it is an entry that should not happen. Sequence steps are order-sensitive,
                because energising before lockout is its own accident.
              </dd>
            </div>
            <div>
              <dt className="font-medium text-slate-800">Three conditions to pass</dt>
              <dd className="text-slate-600">
                Score at or above 70%, every critical step correct, and hesitation on no more than a
                third of correct answers. A worker who averages 92% but chose a water extinguisher
                for an electrical fire fails, and so does one who is entirely correct but slow on
                most decisions.
              </dd>
            </div>
            <div>
              <dt className="font-medium text-slate-800">Readiness decays</dt>
              <dd className="text-slate-600">
                Certification is not a one-time stamp. Readiness halves roughly every 45 days and the
                half-life lengthens with each completed refresher, so a worker's current figure
                reflects retention rather than the date of a past exam.
              </dd>
            </div>
          </dl>
        </Card>
      </div>
    </>
  )
}
