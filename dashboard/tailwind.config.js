/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        // Readiness bands. Every use is paired with a shape and a label in the UI, because a
        // colour-blind safety officer must be able to read the same information.
        ready: { bg: '#ecfdf5', fg: '#065f46', ring: '#6ee7b7' },
        due: { bg: '#fffbeb', fg: '#92400e', ring: '#fcd34d' },
        stale: { bg: '#fff7ed', fg: '#9a3412', ring: '#fdba74' },
        expired: { bg: '#fef2f2', fg: '#991b1b', ring: '#fca5a5' },
        neutral2: { bg: '#f8fafc', fg: '#334155', ring: '#cbd5e1' },
      },
      fontFamily: {
        sans: ['Inter', 'Segoe UI', 'system-ui', 'sans-serif'],
        mono: ['ui-monospace', 'SFMono-Regular', 'Consolas', 'monospace'],
      },
    },
  },
  plugins: [],
}
