# Camera AR and release delivery

## Implemented flow

Home -> module -> offline Three.js scene -> Camera AR -> surface scan -> Place scene -> practice.

`CameraArScreen` uses SceneView 2.3.0, Filament and ARCore, not a WebView camera overlay.
It requests camera permission, waits for the AI memory interlock, starts a lifecycle-bound
AR session, and performs a centre-screen hit test against a tracked horizontal plane.
Placement creates an ARCore anchor and module-specific, metre-scaled training props.
The camera remains visible above the existing decision practice after placement.
Reset detaches the old anchor and destroys its prop nodes. Leaving AR releases the AI interlock.

The five prop scenes are an extinguisher and exit, confined-space vessel and detector,
conveyor and isolator, raised platform and anchor, and electrical panel and cable.
These are illustrative tabletop models, not photorealistic site reconstruction.
They do not detect actual fire, gas, electrical isolation or safe anchors.
Spatial assessment gestures, timed AR certification and scene-object answer picking
are not implemented by this practice screen. Answers still use the existing decision controls.

Camera AR requires an ARCore-compatible device and installed Google Play Services for AR.
The service may need an initial download. Bundled props, language resources, 3D scenes,
practice questions and the bundled AI model are local assets. Devices without AR support
can return to offline 3D and decision practice; there is no simulated claim of camera tracking.

## Languages and UI

Home and Settings both expose English, Hindi, Santali and Tamil selection.
Settings now has real language controls, a theme switch and model-presence status.
The four locales contain matching resource keys and format arguments, checked with
`tools/check-strings.ps1`. Language selection survives Activity recreation.
All five Tamil training catalogs and decision flows are translated.
Tamil and newly added Santali strings are draft translations requiring native-speaker
and industrial-safety review. Resource parity and glyph tests are not linguistic validation.
Some inherited document-tool/general-chat screens still contain English literals.
The safety coach supports English and Hindi only and explicitly rejects unsupported languages.

## Release identity

`tools/build-release.ps1 -CreateSigningKey` creates a local RSA signing identity once;
subsequent builds use `tools/build-release.ps1`. The key and Windows-user-encrypted
password live in gitignored `.signing/`. Do not regenerate or commit these files.
Arrange a secure backup and password recovery/export before distributing future updates;
the DPAPI password file is tied to the Windows account that created it.

On a full source drive, use `tools/build-release.ps1 -BuildDirectory <absolute output directory>`.
For a different Windows drive this creates a gitignored `app/.release-build` junction:
KSP requires source and generated files to share a logical drive root. The files physically
live in the selected output directory, and build-cache duplication of the model is disabled.
The script refuses to replace an existing junction pointing somewhere else.

The release package is `org.jaagruk.safety.release`, separate from the pre-existing
debug package `org.jaagruk.safety`, so installation does not delete the user's local data.
The two installations do not share preferences, records or extracted model files.
This is a locally signed APK, not a Play-upload-key or government-issued identity.

The bundled release contains Gemma 3 1B weights. First use extracts them to private
storage; allow space for both the APK and extracted model. Release uses R8 and resource
shrinking; dynamically referenced catalog keys are explicitly retained in `res/raw/keep.xml`.
Language splits are disabled so switching languages does not require a download.

`tools/check-apk.ps1 -Apk <apk> -BuildTools <Android SDK build-tools directory>` verifies
the signature, 16 KB ZIP alignment and every packaged ARM64/x86_64 ELF load segment.
SceneView 2.2.1 was rejected during testing because its Filament libraries were 4 KB aligned.
The published 2.3.0 dependency aligns the Filament version and compiled material assets.

## Validation boundaries

Device instrumentation covers five live AR camera sessions with advancing timestamps
and interlock release, five interactive offline scenes, language persistence, the Settings
language picker, and complete practices in all four languages.
Camera startup tests do not prove anchor stability while walking around a physical surface.
That requires a separate physical placement and movement check.

The real-model test checks exact corpus selection for five English/Hindi questions,
off-topic/injection refusal and unsupported-language handling. It does not establish
general model accuracy or regulatory correctness of the entire source corpus.
