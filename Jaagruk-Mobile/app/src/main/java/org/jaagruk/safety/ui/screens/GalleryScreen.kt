package org.jaagruk.safety.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.jaagruk.core.retention.ReadinessBand
import org.jaagruk.safety.ui.charts.BandCount
import org.jaagruk.safety.ui.charts.BandDonutChart
import org.jaagruk.safety.ui.charts.HesitationPoint
import org.jaagruk.safety.ui.charts.HesitationScatterChart
import org.jaagruk.safety.ui.charts.ReadinessDecayChart
import org.jaagruk.safety.ui.charts.TrendChart
import org.jaagruk.safety.ui.charts.TrendPoint
import org.jaagruk.safety.ui.components.BandChip
import org.jaagruk.safety.ui.components.JaagrukCard
import org.jaagruk.safety.ui.components.PrimaryButton
import org.jaagruk.safety.ui.components.ReadinessRing
import org.jaagruk.safety.ui.components.SecondaryButton
import org.jaagruk.safety.ui.components.SectionHeader
import org.jaagruk.safety.ui.components.StatTile
import org.jaagruk.safety.ui.components.StatusChip
import org.jaagruk.safety.ui.theme.Amber400
import org.jaagruk.safety.ui.theme.Amber600
import org.jaagruk.safety.ui.theme.Clay500
import org.jaagruk.safety.ui.theme.Indigo500
import org.jaagruk.safety.ui.theme.JaagrukTheme
import org.jaagruk.safety.ui.theme.Moss500
import org.jaagruk.safety.ui.theme.Space
import org.jaagruk.safety.ui.theme.Teal200
import org.jaagruk.safety.ui.theme.Teal50
import org.jaagruk.safety.ui.theme.Teal500
import org.jaagruk.safety.ui.theme.Teal700
import org.jaagruk.safety.ui.theme.Teal900
import org.jaagruk.safety.ui.theme.signalsFor

/**
 * The design-system gallery. Phase 3's gate: every token, chart and component on one screen, in
 * light and dark.
 *
 * It exists because a design system nobody can see in one place is a set of claims. Rendering all
 * of it together is how a contrast failure in dark mode, or a chart that assumes a light
 * background, or a label that overflows in Devanagari, gets noticed before it ships inside a drill.
 *
 * The theme toggle is local rather than system: a reviewer should be able to flip both modes in two
 * seconds without leaving the app, and the app's own toggle is the thing under test anyway.
 *
 * Not reachable from the bottom navigation. It is developer-facing and lives on a route.
 */
@Composable
fun GalleryScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var dark by remember { mutableStateOf(false) }

    JaagrukTheme(darkTheme = dark) {
        Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(Space.lg),
                verticalArrangement = Arrangement.spacedBy(Space.md),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text(
                            "Design system",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        Text(
                            if (dark) "dark" else "light",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { dark = !dark }) {
                        Icon(
                            imageVector = if (dark) Icons.Default.LightMode else Icons.Default.DarkMode,
                            contentDescription = if (dark) "Switch to light" else "Switch to dark",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                // ── Palette ──────────────────────────────────────────────────
                SectionHeader("Brand")
                SwatchRow(
                    listOf(
                        "900" to Teal900, "700" to Teal700, "500" to Teal500,
                        "200" to Teal200, "50" to Teal50,
                    ),
                )
                SectionHeader("Accent — chart series in order")
                SwatchRow(
                    listOf(
                        "teal" to Teal700, "amber" to Amber400, "clay" to Clay500,
                        "moss" to Moss500, "indigo" to Indigo500,
                    ),
                )
                SectionHeader("ISO 7010 signal — status only")
                val signals = signalsFor(dark)
                SwatchRow(
                    listOf(
                        "red" to signals.red, "amber" to signals.amber,
                        "green" to signals.green, "blue" to signals.blue,
                    ),
                )

                // ── Type ─────────────────────────────────────────────────────
                SectionHeader("Type — 1234567890 is tabular")
                JaagrukCard {
                    Text("Display 38", style = MaterialTheme.typography.displayMedium)
                    Text("Headline 24", style = MaterialTheme.typography.headlineSmall)
                    Text("Title 22", style = MaterialTheme.typography.titleLarge)
                    Text("Body 17 — the size a safety instruction is read at.", style = MaterialTheme.typography.bodyLarge)
                    Text("Label 12 — nav and axis ticks", style = MaterialTheme.typography.labelSmall)
                    // Both scripts, because this is where clipped Devanagari and Ol Chiki show up.
                    Text("मीथेन 1.25% पर बाहर निकलें", style = MaterialTheme.typography.bodyLarge)
                    Text("ᱡᱟᱜᱨᱩᱠ ᱨᱠᱷᱟ ᱛᱟᱞᱤᱢ", style = MaterialTheme.typography.bodyLarge)
                }

                // ── Status ───────────────────────────────────────────────────
                SectionHeader("Status — colour + silhouette + label")
                JaagrukCard {
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                        ReadinessBand.entries.forEach { BandChip(band = it, darkTheme = dark) }
                    }
                    Box(Modifier.height(Space.sm))
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                        StatusChip("synced", signals.green, RoundedCornerShape(50), darkTheme = dark)
                        StatusChip("queued", signals.amber, RoundedCornerShape(2.dp), darkTheme = dark)
                        StatusChip("chain break", signals.red, RoundedCornerShape(0.dp), darkTheme = dark)
                    }
                }

                // ── Buttons ──────────────────────────────────────────────────
                SectionHeader("Actions — 64 dp floor, haptics on press")
                PrimaryButton(label = "Report a hazard", onClick = {}, leading = Icons.Default.ReportProblem)
                SecondaryButton(label = "Verify a certificate", onClick = {})
                PrimaryButton(label = "Disabled", onClick = {}, enabled = false)

                // ── Readiness ring ───────────────────────────────────────────
                SectionHeader("Readiness ring")
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    ReadinessRing(
                        readinessPermille = 696,
                        band = ReadinessBand.DUE,
                        darkTheme = dark,
                        caption = "below ready in 6 days",
                    )
                }

                // ── Charts ───────────────────────────────────────────────────
                SectionHeader("Readiness decay — the argument")
                JaagrukCard {
                    // 850 with no refreshers: crosses READY at day 13, STALE at 35, EXPIRED at 68,
                    // and the statutory certificate stays valid the whole way down.
                    ReadinessDecayChart(
                        baseScorePermille = 850,
                        refresherStage = 0,
                        daysElapsed = 30,
                        darkTheme = dark,
                    )
                }

                SectionHeader("Workforce bands")
                JaagrukCard {
                    BandDonutChart(
                        counts = listOf(
                            BandCount(ReadinessBand.READY, 48),
                            BandCount(ReadinessBand.DUE, 23),
                            BandCount(ReadinessBand.STALE, 14),
                            BandCount(ReadinessBand.EXPIRED, 9),
                        ),
                        statutorilyValidButStale = 37,
                        darkTheme = dark,
                    )
                }

                SectionHeader("Hesitation — accuracy against latency")
                JaagrukCard {
                    HesitationScatterChart(
                        points = sampleHesitation(),
                        expertBaselineMs = 3_000L,
                        darkTheme = dark,
                    )
                }

                SectionHeader("Drill volume and pass rate")
                JaagrukCard {
                    TrendChart(
                        points = listOf(
                            TrendPoint("Apr", 32, 640),
                            TrendPoint("May", 41, 700),
                            TrendPoint("Jun", 28, 580),
                            TrendPoint("Jul", 55, 760),
                            TrendPoint("Aug", 62, 810),
                            TrendPoint("Sep", 47, 720),
                        ),
                        darkTheme = dark,
                    )
                }

                // ── Stat tiles ───────────────────────────────────────────────
                SectionHeader("Figures")
                Row(horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                    StatTile("47", "drills this month", Modifier.weight(1f), Teal700)
                    StatTile("9", "open hazards", Modifier.weight(1f), Amber600)
                    StatTile("3", "chain gaps", Modifier.weight(1f), signals.red)
                }

                Box(Modifier.height(Space.xxxl))
                SecondaryButton(label = "Back", onClick = onNavigateBack)
                Box(Modifier.height(Space.xxl))
            }
        }
    }
}

@Composable
private fun SwatchRow(entries: List<Pair<String, Color>>, swatch: Dp = 52.dp) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        entries.forEach { (name, colour) ->
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(swatch)
                        .background(colour, MaterialTheme.shapes.small),
                )
                Text(
                    text = name,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Deliberately shaped to put points in every region: two suspiciously fast, a cluster of
 * correct-and-quick, and a correct-but-slow group above the pass line and right of 6 s. If the
 * sample data avoided the interesting quadrants, the chart would look finished while being wrong.
 */
private fun sampleHesitation(): List<HesitationPoint> = listOf(
    HesitationPoint(920, 1_800), HesitationPoint(880, 2_400), HesitationPoint(960, 2_100),
    HesitationPoint(840, 3_200), HesitationPoint(900, 2_900), HesitationPoint(780, 4_100),
    HesitationPoint(820, 7_400), HesitationPoint(760, 8_200), HesitationPoint(880, 6_900),
    HesitationPoint(740, 9_600), HesitationPoint(800, 7_100),
    HesitationPoint(520, 5_400), HesitationPoint(430, 6_200), HesitationPoint(610, 3_800),
    HesitationPoint(340, 10_500),
    HesitationPoint(980, 180), HesitationPoint(950, 220),
)
