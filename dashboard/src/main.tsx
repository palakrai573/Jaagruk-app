import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'

import { App } from './App'
import './index.css'
import { ApiError } from './lib/api'
import { AuthProvider } from './lib/auth'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      // Retry transient failures only. A 401, 403 or 404 will not fix itself, and retrying a 403
      // three times just delays the message the user needs to read.
      retry: (failureCount, error) => {
        if (error instanceof ApiError) {
          if (error.isAuthFailure || error.isForbidden || error.isNotFound) return false
          if (error.status >= 400 && error.status < 500 && !error.isRateLimited) return false
        }
        return failureCount < 2
      },
      retryDelay: (attempt) => Math.min(1_000 * 2 ** attempt, 8_000),
      staleTime: 15_000,
      // A safety officer leaving the tab open for a shift should see current figures on return.
      refetchOnWindowFocus: true,
      refetchOnReconnect: true,
    },
    mutations: {
      // Mutations are never retried automatically. A hazard transition or a chain audit is an
      // action with a side effect and a person behind it; retrying silently is the wrong default.
      retry: false,
    },
  },
})

const container = document.getElementById('root')
if (!container) {
  throw new Error('Root element #root is missing from index.html')
}

createRoot(container).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <AuthProvider>
          <App />
        </AuthProvider>
      </BrowserRouter>
    </QueryClientProvider>
  </StrictMode>,
)
