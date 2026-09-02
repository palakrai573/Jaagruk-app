import { useState, type FormEvent } from 'react'
import { Navigate, useNavigate } from 'react-router-dom'

import { ApiError } from '../lib/api'
import { useAuth } from '../lib/auth'
import { Spinner } from '../components/Primitives'

export function LoginPage() {
  const { login, status, expiryNotice } = useAuth()
  const navigate = useNavigate()

  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [retryAfter, setRetryAfter] = useState<number | null>(null)
  const [busy, setBusy] = useState(false)

  if (status === 'loading') {
    return (
      <div className="flex min-h-screen items-center justify-center">
        <Spinner label="Restoring your session" />
      </div>
    )
  }
  if (status === 'authenticated') return <Navigate to="/" replace />

  const onSubmit = async (event: FormEvent) => {
    event.preventDefault()
    setError(null)
    setRetryAfter(null)
    setBusy(true)
    try {
      await login(username.trim(), password)
      navigate('/', { replace: true })
    } catch (caught) {
      if (caught instanceof ApiError) {
        setError(caught.message)
        if (caught.isRateLimited && caught.retryAfterSeconds) {
          setRetryAfter(caught.retryAfterSeconds)
        }
      } else {
        setError('Could not reach the server. Check that the backend is running.')
      }
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="flex min-h-screen items-center justify-center bg-slate-100 px-4 py-10">
      <div className="w-full max-w-md">
        <div className="mb-6 text-center">
          <h1 className="text-2xl font-semibold tracking-tight text-slate-900">Jaagruk</h1>
          <p className="mt-1 text-sm text-slate-600">
            Safety compliance dashboard · Government of Jharkhand
          </p>
        </div>

        <form onSubmit={onSubmit} className="card space-y-4 p-6" noValidate>
          <h2 className="text-base font-semibold text-slate-800">Sign in</h2>

          {expiryNotice ? (
            <p
              role="status"
              className="rounded-md border border-amber-300 bg-amber-50 p-3 text-sm text-amber-900"
            >
              {expiryNotice}
            </p>
          ) : null}

          {error ? (
            <div
              role="alert"
              className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800"
            >
              <p>{error}</p>
              {retryAfter ? (
                <p className="mt-1 text-xs">
                  Repeated failures lock the account temporarily. This is deliberate: it is what
                  stops a password from being guessed.
                </p>
              ) : null}
            </div>
          ) : null}

          <div>
            <label className="label" htmlFor="username">
              Username
            </label>
            <input
              id="username"
              name="username"
              className="input"
              autoComplete="username"
              autoCapitalize="none"
              spellCheck={false}
              required
              value={username}
              onChange={(event) => setUsername(event.target.value)}
            />
          </div>

          <div>
            <label className="label" htmlFor="password">
              Password
            </label>
            <input
              id="password"
              name="password"
              type="password"
              className="input"
              autoComplete="current-password"
              required
              minLength={8}
              value={password}
              onChange={(event) => setPassword(event.target.value)}
            />
          </div>

          <button type="submit" className="btn-primary w-full" disabled={busy}>
            {busy ? 'Signing in…' : 'Sign in'}
          </button>

          <p className="text-xs leading-relaxed text-slate-500">
            Worker training and certificate verification happen in the Jaagruk Android app and need
            no connectivity. This dashboard is for site safety officers, company administrators and
            DGMS inspectors.
          </p>
        </form>

        <details className="mt-4 rounded-md border border-slate-200 bg-white p-3 text-xs text-slate-600">
          <summary className="cursor-pointer font-medium text-slate-700">
            Demo accounts (after running the seed script)
          </summary>
          <ul className="mt-2 space-y-1">
            <li>
              <code className="mono">inspector.dgms</code> — DGMS inspector, reads every company
            </li>
            <li>
              <code className="mono">admin.coal</code> — company administrator
            </li>
            <li>
              <code className="mono">officer.dhanbad</code> — site officer, one site only
            </li>
          </ul>
          <p className="mt-2">
            Password for all demo accounts: <code className="mono">JaagrukDemo2026!</code>
          </p>
          <p className="mt-2">
            Seed with: <code className="mono">cd backend &amp;&amp; python -m app.seed --reset</code>
          </p>
        </details>
      </div>
    </div>
  )
}
