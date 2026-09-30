# Recorded validation: 1 October 2026

This report describes the local source build before publication. Raw device logs and
camera captures remain local to avoid publishing personal information or surroundings.
No new device run was performed merely to publish this branch.

## Passed

- Samsung SM-S721B, Android 16: 33 instrumentation tests in 113.153 seconds after
  updating SceneView to 2.3.0.
- Five actual ARCore camera sessions: advancing frame timestamps, viewport dimensions
  and release of the AI memory interlock when returning to 3D.
- Five offline Three.js scenes: rendered pixels, actions, orbit, pause/reset and practice.
- Twenty completed practices: five modules in each of English, Hindi, Santali and Tamil.
- Home language persistence, Settings language switching and sign-in-free training access.
- Real-model device evaluation: five exact corpus selections in English/Hindi, plus
  off-topic/injection refusal and unsupported Santali handling, in 70.451 seconds.
  This preceded the rendering-library update; model/evaluation code was unchanged.
- Core JVM suite: 608 tests, zero failures/errors.
- Debug lint: zero errors, 47 remaining warnings.
- Resource key and format parity: 359 keys per locale (en/hi/sat/ta).
- Hindi/Tamil screenshots checked after the native-library update; the Android
  16 KB compatibility warning was no longer present.

## Local Release Artifact

R8, resource shrinking and release lint completed. The bundled APK contains the
806,058,240-byte model and offline Three.js assets. This artifact is not committed.

- Package: `org.jaagruk.safety.release`, version `1.0-bundled`, version code 1.
- APK size: 863,931,144 bytes.
- SHA-256: `D9385A13A56FBF338807C1B27762B961981712BA822631B4A17746B877DA308C`.
- APK v2 signature and 16 KB ZIP alignment verified.
- All 20 packaged ARM64/x86_64 libraries passed ELF load-segment alignment checks.
- Release is non-debuggable.

## Still Pending

- The phone disconnected before signed-release installation. Fresh-install extraction,
  release R8/JNI behavior and final shortened Tamil navigation labels need device checks.
- Physical surface placement, anchored-object stability while moving and in-camera
  practice interaction have not passed a physical interaction test.
- Tamil and new Santali translations need native-speaker and industrial-safety review.
- AI evaluation is a small regression set, not general answer-quality certification.
- Some inherited tools/general-chat screens retain English literals.
- Spatial assessment gestures and timed AR certification are not implemented here.

The camera session tests do not establish real-hazard detection or regulatory compliance.
