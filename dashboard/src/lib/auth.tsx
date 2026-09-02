import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react'

import { api, onSessionExpired, tokenStore } from './api'
import type { Me, Role, TokenResponse } from './types'

interface AuthState {
  me: Me | null
  status: 'loading' | 'authenticated' | 'anonymous'
  /** Set when a session ended unexpectedly, so the login screen can explain why. */
  expiryNotice: string | null
  login: (username: string, password: string) => Promise<void>
  logout: () => Promise<void>
  can: (permission: string) => boolean
  hasRole: (...roles: Role[]) => boolean
}

const AuthContext = createContext<AuthState | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [me, setMe] = useState<Me | null>(null)
  const [status, setStatus] = useState<AuthState['status']>(
    tokenStore.isAuthenticated ? 'loading' : 'anonymous',
  )
  const [expiryNotice, setExpiryNotice] = useState<string | null>(null)

  // A stored token survives a page reload, so the session is restored by asking the server who we
  // are rather than by trusting whatever role happened to be cached in the browser.
  useEffect(() => {
    let cancelled = false
    if (!tokenStore.isAuthenticated) {
      setStatus('anonymous')
      return
    }
    api
      .get<Me>('/auth/me')
      .then((profile) => {
        if (cancelled) return
        setMe(profile)
        setStatus('authenticated')
      })
      .catch(() => {
        if (cancelled) return
        tokenStore.clear()
        setMe(null)
        setStatus('anonymous')
      })
    return () => {
      cancelled = true
    }
  }, [])

  useEffect(
    () =>
      onSessionExpired(() => {
        setMe(null)
        setStatus('anonymous')
        setExpiryNotice('Your session ended. Sign in again to continue.')
      }),
    [],
  )

  const login = useCallback(async (username: string, password: string) => {
    const tokens = await api.postAnonymous<TokenResponse>('/auth/login', {
      username,
      password,
    })
    tokenStore.set(tokens)
    const profile = await api.get<Me>('/auth/me')
    setMe(profile)
    setStatus('authenticated')
    setExpiryNotice(null)
  }, [])

  const logout = useCallback(async () => {
    try {
      await api.post<void>('/auth/logout')
    } catch {
      // A failed revoke must not trap the user in a signed-in state. The local token is dropped
      // either way; it simply stays valid server-side until it expires.
    } finally {
      tokenStore.clear()
      setMe(null)
      setStatus('anonymous')
      setExpiryNotice(null)
    }
  }, [])

  const value = useMemo<AuthState>(
    () => ({
      me,
      status,
      expiryNotice,
      login,
      logout,
      can: (permission) => Boolean(me?.permissions.includes(permission)),
      hasRole: (...roles) => Boolean(me && roles.includes(me.role)),
    }),
    [me, status, expiryNotice, login, logout],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth(): AuthState {
  const context = useContext(AuthContext)
  if (!context) throw new Error('useAuth must be used inside an AuthProvider')
  return context
}
