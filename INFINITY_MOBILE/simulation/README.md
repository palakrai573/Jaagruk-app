# Offline scene package

The vendor scene, motion helper and action definitions are a snapshot of the user's
`sih-safety-sim/src` implementation, taken 2026-09-30. The mobile adaptation adds pause support.
React Three Fiber / Three.js render the same procedural workers and hazards as the web app.
These are illustrative environments, not photorealistic physics or camera AR.

Run `npm ci` then `npm run build` here after changing scene source. Commit the lockfile and
generated Android assets together. The APK uses bundled assets without CDN requests.
Android allows only the local scene asset origin and never exposes a JavascriptInterface.
