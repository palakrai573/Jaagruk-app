// Motion primitives.
//
// WHY THERE IS NO ANIMATION LIBRARY HERE
// Framer Motion would add roughly 40 KB gzipped for capability this product does
// not need, on a device that may be a four-year-old Android on a 2G link. More
// importantly, a JS-driven animation library bypasses the `prefers-reduced-motion`
// CSS overrides in index.css unless every single call site opts in — so the
// accessibility guarantee would depend on nobody ever forgetting. CSS transitions
// plus one IntersectionObserver gets scroll reveal, stagger, counters and
// micro-interactions, and reduced motion is handled centrally.
//
// THE CONTRACT EVERY HOOK HERE KEEPS
// Under reduced motion, content is rendered COMPLETE AND STATIC — never hidden,
// never mid-transition, never at zero. A reveal that starts at opacity 0 and
// depends on JS to finish is a blank page for anyone who asked the OS to stop
// moving things.

import { useState, useEffect, useRef, useCallback } from 'react'

/**
 * Whether the user asked the OS to reduce motion.
 *
 * Lifted out of Charts.jsx, where it was module-private and would have been
 * duplicated by every component that needed it.
 */
export function usePrefersReducedMotion() {
  const [reduced, setReduced] = useState(() => {
    try {
      return window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false
    } catch {
      return false
    }
  })

  useEffect(() => {
    let media
    try {
      media = window.matchMedia?.('(prefers-reduced-motion: reduce)')
    } catch {
      return undefined
    }
    if (!media) return undefined

    const handler = (e) => setReduced(e.matches)
    if (media.addEventListener) media.addEventListener('change', handler)
    else media.addListener?.(handler)

    return () => {
      if (media.removeEventListener) media.removeEventListener('change', handler)
      else media.removeListener?.(handler)
    }
  }, [])

  return reduced
}

/** Generic media query hook, for layout decisions React has to make in JS. */
export function useMediaQuery(query) {
  const [matches, setMatches] = useState(() => {
    try {
      return window.matchMedia?.(query).matches ?? false
    } catch {
      return false
    }
  })

  useEffect(() => {
    let media
    try {
      media = window.matchMedia?.(query)
    } catch {
      return undefined
    }
    if (!media) return undefined

    setMatches(media.matches)
    const handler = (e) => setMatches(e.matches)
    if (media.addEventListener) media.addEventListener('change', handler)
    else media.addListener?.(handler)

    return () => {
      if (media.removeEventListener) media.removeEventListener('change', handler)
      else media.removeListener?.(handler)
    }
  }, [query])

  return matches
}

/**
 * Reveal an element when it scrolls into view.
 *
 * Returns `[ref, visible]`. Attach the ref, then apply the `reveal` class and
 * `is-visible` when true — the transition itself lives in CSS so reduced motion
 * can switch it off without this hook knowing.
 *
 * One-shot by default: re-animating on every scroll past is the thing that makes
 * scroll animation feel cheap. It also unobserves once revealed, so a long page
 * does not keep dozens of observers alive.
 */
export function useReveal({ threshold = 0.12, rootMargin = '0px 0px -8% 0px', once = true } = {}) {
  const reduced = usePrefersReducedMotion()
  const ref = useRef(null)
  const [visible, setVisible] = useState(reduced)

  useEffect(() => {
    // Reduced motion, or a browser with no observer: show it, immediately and
    // permanently. Never leave content depending on an animation that will not run.
    if (reduced || typeof IntersectionObserver === 'undefined') {
      setVisible(true)
      return undefined
    }

    const el = ref.current
    if (!el) return undefined

    const observer = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          if (entry.isIntersecting) {
            setVisible(true)
            if (once) observer.unobserve(entry.target)
          } else if (!once) {
            setVisible(false)
          }
        }
      },
      { threshold, rootMargin }
    )

    observer.observe(el)
    return () => observer.disconnect()
  }, [reduced, threshold, rootMargin, once])

  return [ref, visible]
}

/**
 * Count a number up when it becomes visible.
 *
 * `easeOutExpo` rather than linear because a linear counter reads as a loading
 * spinner; decelerating into the final value reads as a measurement settling.
 */
export function useCountUp(target, { duration = 900, decimals = 0, start = false } = {}) {
  const reduced = usePrefersReducedMotion()
  const safeTarget = Number.isFinite(Number(target)) ? Number(target) : 0
  const [value, setValue] = useState(reduced ? safeTarget : 0)
  const frameRef = useRef(null)
  const fromRef = useRef(0)

  useEffect(() => {
    if (reduced) {
      setValue(safeTarget)
      return undefined
    }
    if (!start) return undefined

    const from = fromRef.current
    const startedAt = performance.now()

    const tick = (now) => {
      const elapsed = now - startedAt
      const p = Math.min(1, elapsed / duration)
      const eased = p === 1 ? 1 : 1 - Math.pow(2, -10 * p)
      const next = from + (safeTarget - from) * eased
      setValue(decimals > 0 ? Number(next.toFixed(decimals)) : Math.round(next))

      if (p < 1) frameRef.current = requestAnimationFrame(tick)
      else fromRef.current = safeTarget
    }

    frameRef.current = requestAnimationFrame(tick)
    return () => {
      if (frameRef.current !== null) cancelAnimationFrame(frameRef.current)
      frameRef.current = null
    }
  }, [safeTarget, duration, decimals, start, reduced])

  return value
}

/**
 * Trap focus inside a container while it is active, and restore focus to
 * whatever was focused before on close.
 *
 * Required for the Dialog to be usable by keyboard and by a screen reader. A
 * modal that lets Tab escape into the page behind it is worse than no modal,
 * because the user cannot tell what they are operating.
 */
export function useFocusTrap(active) {
  const containerRef = useRef(null)
  const previousRef = useRef(null)

  useEffect(() => {
    if (!active) return undefined

    previousRef.current = document.activeElement
    const container = containerRef.current
    if (!container) return undefined

    const selector =
      'a[href],button:not([disabled]),textarea:not([disabled]),input:not([disabled]),select:not([disabled]),[tabindex]:not([tabindex="-1"])'

    const focusables = () => Array.from(container.querySelectorAll(selector)).filter((el) => el.offsetParent !== null)

    // Move focus in, preferring the first control over the container itself so a
    // screen reader announces something actionable.
    const first = focusables()[0]
    if (first) first.focus()
    else container.focus?.()

    const onKeyDown = (e) => {
      if (e.key !== 'Tab') return
      const items = focusables()
      if (items.length === 0) {
        e.preventDefault()
        return
      }
      const firstItem = items[0]
      const lastItem = items[items.length - 1]

      if (e.shiftKey && document.activeElement === firstItem) {
        e.preventDefault()
        lastItem.focus()
      } else if (!e.shiftKey && document.activeElement === lastItem) {
        e.preventDefault()
        firstItem.focus()
      }
    }

    container.addEventListener('keydown', onKeyDown)
    return () => {
      container.removeEventListener('keydown', onKeyDown)
      previousRef.current?.focus?.()
    }
  }, [active])

  return containerRef
}

/** Lock body scroll while a sheet or dialog is open, without layout shift. */
export function useScrollLock(active) {
  useEffect(() => {
    if (!active) return undefined
    const { body } = document
    const previousOverflow = body.style.overflow
    const previousPadding = body.style.paddingInlineEnd

    // Compensate for the scrollbar disappearing, or the page jumps sideways.
    const gap = window.innerWidth - document.documentElement.clientWidth
    body.style.overflow = 'hidden'
    if (gap > 0) body.style.paddingInlineEnd = `${gap}px`

    return () => {
      body.style.overflow = previousOverflow
      body.style.paddingInlineEnd = previousPadding
    }
  }, [active])
}

/** Escape-to-close, shared by Dialog and the mobile nav sheet. */
export function useEscape(active, onEscape) {
  const handler = useCallback(
    (e) => {
      if (e.key === 'Escape') onEscape?.()
    },
    [onEscape]
  )

  useEffect(() => {
    if (!active) return undefined
    document.addEventListener('keydown', handler)
    return () => document.removeEventListener('keydown', handler)
  }, [active, handler])
}

/**
 * Stagger helper. Returns an inline style delaying a child's transition by its
 * index, capped so a long list does not leave the last item waiting seconds.
 */
export function stagger(index, step = 60, max = 480) {
  return { transitionDelay: `${Math.min(index * step, max)}ms` }
}
