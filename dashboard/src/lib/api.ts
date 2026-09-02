/**
 * HTTP client.
 *
 * Two behaviours worth knowing:
 *
 * 1. **Single-flight token refresh.** A dashboard page fires several requests at once. If each one
 *    refreshed independently on a 401, they would race and, because the backend rotates refresh
 *    tokens on use, all but one would burn a token that had already been replaced — logging the
 *    user out mid-session. One shared in-flight refresh promise avoids that.
 * 2. **Errors carry the server's message.** The backend's failures are written to be shown to a
 *    person ("Retry in 43 seconds", "Allowed from 'open': acknowledged, invalid"). Replacing them
 *    with a generic "Something went wrong" would throw away the useful half.
 */

import type { TokenResponse } from './types'

const API_PREFIX = '/api/v1'
const ACCESS_KEY = 'jaagruk.access'
const REFRESH_KEY = 'jaagruk.refresh'

export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
    readonly code?: string,
    readonly hint?: string,
    readonly retryAfterSeconds?: number,
  ) {
    super(message)
    this.name = 'ApiError'
  }

  /** True when re-authenticating is the correct response. */
  get isAuthFailure(): boolean {
    return this.status === 401
  }

  get isForbidden(): boolean {
    return this.status === 403
  }

  get isNotFound(): boolean {
    return this.status === 404
  }

  get isConflict(): boolean {
    return this.status === 409
  }

  get isRateLimited(): boolean {
    return this.status === 429
  }
}

export const tokenStore = {
  access(): string | null {
    return localStorage.getItem(ACCESS_KEY)
  },
  refresh(): string | null {
    return localStorage.getItem(REFRESH_KEY)
  },
  set(tokens: Pick<TokenResponse, 'access_token' | 'refresh_token'>): void {
    localStorage.setItem(ACCESS_KEY, tokens.access_token)
    localStorage.setItem(REFRESH_KEY, tokens.refresh_token)
  },
  clear(): void {
    localStorage.removeItem(ACCESS_KEY)
    localStorage.removeItem(REFRESH_KEY)
  },
  get isAuthenticated(): boolean {
    return Boolean(localStorage.getItem(ACCESS_KEY))
  },
}

type SessionExpiredListener = () => void
const sessionExpiredListeners = new Set<SessionExpiredListener>()

export function onSessionExpired(listener: SessionExpiredListener): () => void {
  sessionExpiredListeners.add(listener)
  return () => sessionExpiredListeners.delete(listener)
}

function announceSessionExpired(): void {
  tokenStore.clear()
  sessionExpiredListeners.forEach((listener) => listener())
}

async function readError(response: Response): Promise<ApiError> {
  let detail = `${response.status} ${response.statusText}`
  let code: string | undefined
  let hint: string | undefined

  try {
    const body = (await response.json()) as {
      detail?: unknown
      code?: string
      hint?: string
    }
    if (typeof body.detail === 'string' && body.detail.trim()) {
      detail = body.detail
    } else if (Array.isArray(body.detail)) {
      // FastAPI's raw validation shape, in case a route bypasses our handler.
      detail = body.detail
        .map((entry) => {
          const item = entry as { loc?: unknown[]; msg?: string }
          const location = Array.isArray(item.loc) ? item.loc.join('.') : 'request'
          return `${location}: ${item.msg ?? 'invalid'}`
        })
        .join('; ')
    }
    code = body.code
    hint = body.hint
  } catch {
    // A non-JSON body (an HTML error page from a proxy, say). Keep the status line.
  }

  const retryAfter = response.headers.get('Retry-After')
  return new ApiError(
    response.status,
    detail,
    code,
    hint,
    retryAfter ? Number(retryAfter) : undefined,
  )
}

let refreshInFlight: Promise<boolean> | null = null

async function attemptRefresh(): Promise<boolean> {
  if (refreshInFlight) return refreshInFlight

  refreshInFlight = (async () => {
    const refreshToken = tokenStore.refresh()
    if (!refreshToken) return false
    try {
      const response = await fetch(`${API_PREFIX}/auth/refresh`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ refresh_token: refreshToken }),
      })
      if (!response.ok) return false
      tokenStore.set((await response.json()) as TokenResponse)
      return true
    } catch {
      return false
    } finally {
      // Cleared in a microtask so concurrent callers awaiting this promise all observe the result
      // before a new attempt can start.
      queueMicrotask(() => {
        refreshInFlight = null
      })
    }
  })()

  return refreshInFlight
}

interface RequestOptions {
  method?: 'GET' | 'POST' | 'PATCH' | 'DELETE'
  body?: unknown
  /** Skip the bearer header and the refresh retry. Used by login itself. */
  anonymous?: boolean
  signal?: AbortSignal
}

async function send(path: string, options: RequestOptions, retrying = false): Promise<Response> {
  const headers: Record<string, string> = {}
  if (options.body !== undefined) headers['Content-Type'] = 'application/json'

  if (!options.anonymous) {
    const token = tokenStore.access()
    if (token) headers.Authorization = `Bearer ${token}`
  }

  const response = await fetch(`${API_PREFIX}${path}`, {
    method: options.method ?? 'GET',
    headers,
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
    signal: options.signal,
  })

  if (response.status === 401 && !options.anonymous && !retrying) {
    if (await attemptRefresh()) {
      return send(path, options, true)
    }
    announceSessionExpired()
  }
  return response
}

export async function apiRequest<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const response = await send(path, options)
  if (!response.ok) throw await readError(response)
  if (response.status === 204) return undefined as T
  return (await response.json()) as T
}

export const api = {
  get: <T>(path: string, signal?: AbortSignal) => apiRequest<T>(path, { signal }),
  post: <T>(path: string, body?: unknown) => apiRequest<T>(path, { method: 'POST', body }),
  patch: <T>(path: string, body?: unknown) => apiRequest<T>(path, { method: 'PATCH', body }),
  postAnonymous: <T>(path: string, body?: unknown) =>
    apiRequest<T>(path, { method: 'POST', body, anonymous: true }),
}

/**
 * Download a CSV export.
 *
 * Goes through the same auth path as everything else rather than a bare `window.open`, because the
 * bearer token cannot be attached to a plain navigation and the export endpoints are RBAC-scoped.
 */
export async function downloadCsv(path: string, fallbackFilename: string): Promise<void> {
  const response = await send(path, {})
  if (!response.ok) throw await readError(response)

  const disposition = response.headers.get('Content-Disposition') ?? ''
  const match = /filename="?([^";]+)"?/i.exec(disposition)
  const filename = match?.[1] ?? fallbackFilename

  const blob = await response.blob()
  const url = URL.createObjectURL(blob)
  try {
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = filename
    document.body.appendChild(anchor)
    anchor.click()
    anchor.remove()
  } finally {
    // Revoked on the next tick: revoking synchronously can cancel the download in some browsers.
    setTimeout(() => URL.revokeObjectURL(url), 1_000)
  }
}

export function buildQuery(params: Record<string, string | number | boolean | null | undefined>) {
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value === null || value === undefined || value === '') continue
    search.set(key, String(value))
  }
  const query = search.toString()
  return query ? `?${query}` : ''
}

export function liveSocketUrl(): string | null {
  const token = tokenStore.access()
  if (!token) return null
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  return `${protocol}//${window.location.host}${API_PREFIX}/ws/live?token=${encodeURIComponent(token)}`
}
