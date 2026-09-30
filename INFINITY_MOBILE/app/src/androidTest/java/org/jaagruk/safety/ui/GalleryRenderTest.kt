package org.jaagruk.safety.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.jaagruk.safety.ui.screens.GalleryScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 3's gate: the design system composes in light and in dark.
 *
 * This is an instrumented test rather than a screenshot because the target handset is somebody's
 * personal phone and a screen capture photographs whatever is in front of it. An assertion is also
 * a better gate than an image: it fails loudly on a regression instead of requiring somebody to
 * notice a difference between two pictures.
 *
 * What it actually proves: every token block, all four Canvas charts and every component compose
 * without throwing, in both colour schemes, at the device's real density and font scale. Canvas
 * code is where a divide-by-zero on an empty data set or an out-of-bounds text measurement lives,
 * and none of that shows up at compile time.
 *
 * What it does not prove: that any of it looks right. Contrast, overflow in Devanagari and Ol
 * Chiki, and whether a chart reads at a glance still need eyes.
 */
@RunWith(AndroidJUnit4::class)
class GalleryRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private fun setGallery() {
        compose.setContent { GalleryScreen(onNavigateBack = {}) }
    }

    @Test
    fun everySectionComposesInLightMode() {
        setGallery()

        // The gallery opens light. Sections are asserted by scrolling to each in turn, because a
        // vertical scroller only composes what is near the viewport - asserting without scrolling
        // would pass on an empty screen.
        listOf(
            "Design system",
            "Brand",
            "Accent — chart series in order",
            "ISO 7010 signal — status only",
            "Type — 1234567890 is tabular",
            "Status — colour + silhouette + label",
            "Actions — 64 dp floor, haptics on press",
            "Readiness ring",
            "Readiness decay — the argument",
            "Workforce bands",
            "Hesitation — accuracy against latency",
            "Drill volume and pass rate",
            "Figures",
        ).forEach { section ->
            compose.onNodeWithText(section).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun everySectionComposesInDarkMode() {
        setGallery()

        compose.onNodeWithContentDescription("Switch to dark").performClick()
        compose.waitForIdle()

        // The toggle itself is the assertion that the scheme flipped: its description is derived
        // from the current mode.
        compose.onNodeWithContentDescription("Switch to light").assertIsDisplayed()

        listOf(
            "Brand",
            "Type — 1234567890 is tabular",
            "Readiness ring",
            "Readiness decay — the argument",
            "Workforce bands",
            "Hesitation — accuracy against latency",
            "Drill volume and pass rate",
        ).forEach { section ->
            compose.onNodeWithText(section).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun chartsAnnounceTheirFindingNotTheirShape() {
        setGallery()

        // A chart described to TalkBack as "line graph" tells a worker who cannot see it nothing.
        // Each chart publishes its actual finding instead.
        //
        // 535 is not an arbitrary expectation: base 850 with no refreshers, thirty days on, is
        // 850 * 0.5^(30/45). If ReadinessCalculator's constants ever change, this fails, which is
        // the point - the chart and the engine must not drift apart.
        compose.onNodeWithText("Readiness decay — the argument").performScrollTo()
        compose.onNode(
            hasContentDescription("Readiness today 535 of 1000", substring = true),
        ).assertExists()

        // That worker crossed below READY on day 13, so at day 30 there is no countdown to give.
        compose.onNode(
            hasContentDescription("Already below ready", substring = true),
        ).assertExists()
    }

    @Test
    fun theReadinessRingIsOneNodeNotThree() {
        setGallery()

        // Left to itself the ring would be three separate nodes - arc, number, band label - and
        // TalkBack would read them as unrelated fragments.
        compose.onNodeWithText("Readiness ring").performScrollTo()
        compose.onNode(
            hasContentDescription("Readiness 696 of 1000, due", substring = true),
        ).assertExists()
    }

    @Test
    fun theDonutCentreReportsTheCohortThatMatters() {
        setGallery()

        // Statutorily valid but operationally stale. The number a blended compliance percentage
        // hides, and the reason the centre of the donut is not the total.
        compose.onNodeWithText("Workforce bands").performScrollTo()
        compose.onNode(
            hasContentDescription("hold a valid certificate but are no longer ready", substring = true),
        ).assertExists()
    }
}
