import { fileURLToPath, URL } from 'node:url'

import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// The dev server proxies /api and /ws to the backend so the browser sees a single origin.
// That keeps CORS out of the development loop entirely and means the production build works
// unchanged behind a reverse proxy.
const BACKEND = process.env.JAAGRUK_API_ORIGIN ?? 'http://127.0.0.1:8000'

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      '/api': { target: BACKEND, changeOrigin: true },
      '/health': { target: BACKEND, changeOrigin: true },
      '/readyz': { target: BACKEND, changeOrigin: true },
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: true,
    // Leaflet and Recharts are both large and neither is needed on the login screen.
    // Splitting them keeps the first paint small on the kind of connection a site office has.
    rollupOptions: {
      output: {
        manualChunks: {
          charts: ['recharts'],
          maps: ['leaflet', 'react-leaflet'],
        },
      },
    },
  },
})
