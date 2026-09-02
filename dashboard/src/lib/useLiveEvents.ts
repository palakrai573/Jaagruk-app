import { useQueryClient } from '@tanstack/react-query'
import { useCallback, useEffect, useRef, useState } from 'react'

import { liveSocketUrl } from './api'
import { queryKeys } from './queries'
import type { LiveEvent } from './types'

/** Application-level close code mirroring HTTP 401: refresh and retry rather than back off. */
const CLOSE_UNAUTHORISED = 4401
const MAX_LOG_ENTRIES = 50
const BASE_RECONNECT_MS = 1_000
const MAX_RECONNECT_MS = 30_000

export type LiveStatus = 'connecting' | 'live' | 'reconnecting' | 'offline'

interface LiveEventsState {
  status: LiveStatus
  /** Most recent first. Bounded, because a dashboard left open for a shift would grow forever. */
  recent: LiveEvent[]
  lastEventAt: number | null
  clear: () => void
}

/**
 * Subscribes to `/ws/live` and invalidates the affected React Query caches.
 *
 * Pushed events invalidate rather than patch the cache. A patch would have to reproduce the
 * server's aggregation logic in the browser — readiness decay, hazard clustering, chain-head
 * recomputation — and any drift between the two would show up as a dashboard that disagrees with
 * its own refresh button.
 *
 * Reconnects with exponential backoff and jitter. Fifty dashboards reconnecting after a network
 * blip should not arrive in lockstep.
 */
export function useLiveEvents(enabled: boolean): LiveEventsState {
  const queryClient = useQueryClient()
  const [status, setStatus] = useState<LiveStatus>(enabled ? 'connecting' : 'offline')
  const [recent, setRecent] = useState<LiveEvent[]>([])
  const [lastEventAt, setLastEventAt] = useState<number | null>(null)

  const socketRef = useRef<WebSocket | null>(null)
  const retryRef = useRef(0)
  const timerRef = useRef<number | null>(null)
  const disposedRef = useRef(false)

  const handleEvent = useCallback(
    (event: LiveEvent) => {
      if (event.type === 'heartbeat') return
      if (event.type !== 'connected') {
        setRecent((previous) => [event, ...previous].slice(0, MAX_LOG_ENTRIES))
        setLastEventAt(Date.now())
      }

      switch (event.type) {
        case 'cert.issued':
        case 'cert.quarantined':
        case 'chain.break':
          void queryClient.invalidateQueries({ queryKey: queryKeys.overview })
          void queryClient.invalidateQueries({ queryKey: queryKeys.bySite })
          void queryClient.invalidateQueries({ queryKey: ['certificates'] })
          if (event.site_id) {
            void queryClient.invalidateQueries({
              queryKey: queryKeys.chainHead(event.site_id),
            })
          }
          break
        case 'hazard.created':
        case 'hazard.updated':
          void queryClient.invalidateQueries({ queryKey: ['hazards'] })
          void queryClient.invalidateQueries({ queryKey: queryKeys.overview })
          void queryClient.invalidateQueries({ queryKey: queryKeys.bySite })
          break
        case 'sync.batch':
          void queryClient.invalidateQueries({ queryKey: queryKeys.overview })
          void queryClient.invalidateQueries({ queryKey: ['workers'] })
          void queryClient.invalidateQueries({ queryKey: queryKeys.devices })
          break
        // 'heartbeat' returned early above, so it is already excluded from the narrowed union here.
        case 'connected':
          break
        default:
          break
      }
    },
    [queryClient],
  )

  useEffect(() => {
    disposedRef.current = false

    if (!enabled) {
      setStatus('offline')
      return
    }

    const connect = () => {
      if (disposedRef.current) return
      const url = liveSocketUrl()
      if (!url) {
        setStatus('offline')
        return
      }

      setStatus(retryRef.current === 0 ? 'connecting' : 'reconnecting')

      let socket: WebSocket
      try {
        socket = new WebSocket(url)
      } catch {
        scheduleReconnect()
        return
      }
      socketRef.current = socket

      socket.onopen = () => {
        retryRef.current = 0
        setStatus('live')
      }

      socket.onmessage = (message) => {
        try {
          handleEvent(JSON.parse(message.data as string) as LiveEvent)
        } catch {
          // A frame we cannot parse is dropped. Live updates are an enhancement; every page also
          // refetches on its own schedule, so a bad frame must never break the dashboard.
        }
      }

      socket.onerror = () => {
        // Always followed by onclose, which is where the retry is scheduled.
      }

      socket.onclose = (closeEvent) => {
        socketRef.current = null
        if (disposedRef.current) return

        if (closeEvent.code === CLOSE_UNAUTHORISED) {
          // The token expired. An API call will refresh it; reconnect shortly with the new one
          // rather than backing off as though the server were down.
          retryRef.current = 1
        }
        scheduleReconnect()
      }
    }

    const scheduleReconnect = () => {
      if (disposedRef.current) return
      setStatus('reconnecting')
      const attempt = retryRef.current++
      const backoff = Math.min(BASE_RECONNECT_MS * 2 ** attempt, MAX_RECONNECT_MS)
      const jittered = backoff * (0.75 + Math.random() * 0.5)
      timerRef.current = window.setTimeout(connect, jittered)
    }

    connect()

    return () => {
      disposedRef.current = true
      if (timerRef.current !== null) window.clearTimeout(timerRef.current)
      const socket = socketRef.current
      socketRef.current = null
      if (socket && socket.readyState <= WebSocket.OPEN) socket.close(1000, 'navigating away')
    }
  }, [enabled, handleEvent])

  return {
    status,
    recent,
    lastEventAt,
    clear: useCallback(() => setRecent([]), []),
  }
}
