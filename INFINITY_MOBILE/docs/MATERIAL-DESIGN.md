# Jaagruk Material 3

The September 30, 2026 design update maps the supplied CSS theme to native
Jetpack Compose Material 3. The Android app does not embed a web UI framework.

- Light: white background, near-black text, neutral gray secondary surfaces.
- Dark: #0A0A0A background, #FAFAFA text, #262626 secondary surfaces.
- Brand: #FF6B6E coral, with #171717 text on coral fills.
- Small text and text buttons use darker #A52D3C coral in light mode.
- Outlined module rows and answer panels replace elevated cards.
- Corners use 8 dp; standard Material touch targets remain intact.
- Existing system fonts preserve Devanagari and Ol Chiki font fallback.
- New installations start in light mode; saved theme preferences are preserved.
- Safety signal colors remain semantic and follow the selected app theme.

Implementation: ui/theme/MaterialPalette.kt, shared surface components,
TrainingScreens.kt, SafetyCoachScreen.kt, and AppNavigation.kt.

The design change does not validate camera AR, model answer quality, or the
linguistic accuracy of Santali translations. String-key parity is a separate check.

## Verification

- Lean debug APK and instrumentation APK assembled successfully.
- TrainingNavigationTest passed: direct entry and safety-coach navigation.
- TrainingLanguageTest passed: English, Hindi, and Santali selection survives
  recreation and renders the localized catalog.
- All three locales have 344 matching string keys.
- Main text contrast: dark on coral 6.47:1; coral ink on white 6.91:1;
  secondary gray on white 5.74:1.
- English and Santali home screenshots were visually inspected on Pixel 9 Pro
  emulator. Evidence: docs/validation/2026-09-30/material-home-*.png.
- An emulator System UI error initially obscured screenshots. It was dismissed
  and the language test was rerun successfully before capturing clean evidence.
- No USB phone was detected during this pass. Physical-device validation remains
  outstanding.

## Follow-up

Settings sections are now unframed, values wrap below their labels, and the
language row reflects the selected locale. The theme row has one switch semantic
target rather than nested click handlers. Status badges use dark-mode signal
colors. System bars use the app's selected appearance.

At 130% system text size, TrainingNavigationTest and TrainingLanguageTest passed
(2 tests, 36.632 s). Settings screenshots are captured at the same scale.
The five actual Three.js scenes passed SimulationDeviceTest after fixing a
zero-height WebView document. See WEB-PARITY.md for renderer evidence.
