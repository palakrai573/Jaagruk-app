package org.jaagruk.safety.ui.nav

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import org.jaagruk.safety.ui.buddy.BuddyScreen
import org.jaagruk.safety.ui.cert.CertificatesScreen
import org.jaagruk.safety.ui.drill.DrillScreen
import org.jaagruk.safety.ui.drill.ResultScreen
import org.jaagruk.safety.ui.hazard.HazardScreen
import org.jaagruk.safety.ui.home.HomeScreen
import org.jaagruk.safety.ui.signin.SignInScreen
import org.jaagruk.safety.ui.supervisor.SiteScanScreen
import org.jaagruk.safety.ui.supervisor.SupervisorScreen
import org.jaagruk.safety.ui.supervisor.VoiceEnrollScreen
import org.jaagruk.safety.ui.verify.VerifyScreen

/**
 * Every destination in the app.
 *
 * Sealed and string-templated rather than typed navigation. Two reasons: the drill route carries a scenario
 * id that comes from `:core` at runtime, and a verification deep link arrives from a stock camera app as a
 * URL. Both are strings at the boundary, so making them strings here removes a conversion layer that could
 * disagree with itself.
 */
sealed class Route(val pattern: String) {

    data object SignIn : Route("signin")

    data object Home : Route("home/{workerId}") {
        fun of(workerId: String) = "home/$workerId"
        const val ARG_WORKER = "workerId"
    }

    data object Drill : Route("drill/{workerId}/{scenarioId}/{mode}") {
        fun of(workerId: String, scenarioId: String, mode: String) =
            "drill/$workerId/$scenarioId/$mode"

        const val ARG_WORKER = "workerId"
        const val ARG_SCENARIO = "scenarioId"
        const val ARG_MODE = "mode"
    }

    data object Result : Route("result/{runId}") {
        fun of(runId: String) = "result/$runId"
        const val ARG_RUN = "runId"
    }

    data object Certificates : Route("certificates/{workerId}") {
        fun of(workerId: String) = "certificates/$workerId"
        const val ARG_WORKER = "workerId"
    }

    /**
     * Verification. The optional `qr` argument carries a deep-linked payload.
     *
     * Verification is entirely offline; the URL form exists only so a stock camera app can hand off to
     * Jaagruk. The signature check happens on device against the site's stored public key either way, which
     * is what makes an inspector's verdict independent of whether the gatehouse has signal.
     */
    data object Verify : Route("verify?qr={qr}") {
        fun of(qr: String? = null) = if (qr.isNullOrBlank()) "verify" else "verify?qr=$qr"
        const val ARG_QR = "qr"
    }

    data object Hazard : Route("hazard/{workerId}") {
        fun of(workerId: String) = "hazard/$workerId"
        const val ARG_WORKER = "workerId"
    }

    data object Supervisor : Route("supervisor")

    data object SiteScan : Route("sitescan")

    data object VoiceEnroll : Route("voiceenroll")

    data object Buddy : Route("buddy/{workerId}/{scenarioId}") {
        fun of(workerId: String, scenarioId: String) = "buddy/$workerId/$scenarioId"
        const val ARG_WORKER = "workerId"
        const val ARG_SCENARIO = "scenarioId"
    }
}

@Composable
fun JaagrukNavHost(
    openRefreshers: Boolean = false,
    notifiedWorkerId: String? = null,
    navController: NavHostController = rememberNavController(),
) {
    // A refresher notification names the worker, so the app opens on their list rather than the picker.
    val start = if (openRefreshers && !notifiedWorkerId.isNullOrBlank()) {
        Route.Home.of(notifiedWorkerId)
    } else {
        Route.SignIn.pattern
    }

    NavHost(navController = navController, startDestination = start) {

        composable(Route.SignIn.pattern) {
            SignInScreen(
                onWorkerSignedIn = { workerId ->
                    navController.navigate(Route.Home.of(workerId)) {
                        // The picker is not somewhere to go back to mid-shift; a worker who backs out of
                        // their home screen should leave the app, not land on a list of colleagues.
                        popUpTo(Route.SignIn.pattern) { inclusive = true }
                    }
                },
                onSupervisorTools = { navController.navigate(Route.Supervisor.pattern) },
                onVerify = { navController.navigate(Route.Verify.of()) },
            )
        }

        composable(
            route = Route.Home.pattern,
            arguments = listOf(navArgument(Route.Home.ARG_WORKER) { type = NavType.StringType }),
        ) { entry ->
            val workerId = entry.arguments?.getString(Route.Home.ARG_WORKER).orEmpty()
            HomeScreen(
                workerId = workerId,
                highlightRefreshers = openRefreshers,
                onStartDrill = { scenarioId, mode ->
                    navController.navigate(Route.Drill.of(workerId, scenarioId, mode))
                },
                onStartBuddyDrill = { scenarioId ->
                    navController.navigate(Route.Buddy.of(workerId, scenarioId))
                },
                onCertificates = { navController.navigate(Route.Certificates.of(workerId)) },
                onReportHazard = { navController.navigate(Route.Hazard.of(workerId)) },
                onVerify = { navController.navigate(Route.Verify.of()) },
                onSupervisorTools = { navController.navigate(Route.Supervisor.pattern) },
                onSignOut = {
                    navController.navigate(Route.SignIn.pattern) {
                        popUpTo(0) { inclusive = true }
                    }
                },
            )
        }

        composable(
            route = Route.Drill.pattern,
            arguments = listOf(
                navArgument(Route.Drill.ARG_WORKER) { type = NavType.StringType },
                navArgument(Route.Drill.ARG_SCENARIO) { type = NavType.StringType },
                navArgument(Route.Drill.ARG_MODE) { type = NavType.StringType },
            ),
        ) { entry ->
            val args = entry.arguments
            DrillScreen(
                workerId = args?.getString(Route.Drill.ARG_WORKER).orEmpty(),
                scenarioId = args?.getString(Route.Drill.ARG_SCENARIO).orEmpty(),
                mode = args?.getString(Route.Drill.ARG_MODE).orEmpty(),
                onFinished = { runId ->
                    navController.navigate(Route.Result.of(runId)) {
                        // The drill is not re-enterable: its run row is sealed and its result is
                        // immutable, so backing into it would show a session that can no longer be played.
                        popUpTo(Route.Drill.pattern) { inclusive = true }
                    }
                },
                onAbandoned = { navController.popBackStack() },
            )
        }

        composable(
            route = Route.Result.pattern,
            arguments = listOf(navArgument(Route.Result.ARG_RUN) { type = NavType.StringType }),
        ) { entry ->
            ResultScreen(
                runId = entry.arguments?.getString(Route.Result.ARG_RUN).orEmpty(),
                onDone = { workerId ->
                    if (workerId.isNullOrBlank()) {
                        navController.popBackStack()
                    } else {
                        navController.navigate(Route.Home.of(workerId)) {
                            popUpTo(Route.Result.pattern) { inclusive = true }
                        }
                    }
                },
                onViewCertificates = { workerId ->
                    navController.navigate(Route.Certificates.of(workerId))
                },
            )
        }

        composable(
            route = Route.Certificates.pattern,
            arguments = listOf(
                navArgument(Route.Certificates.ARG_WORKER) { type = NavType.StringType },
            ),
        ) { entry ->
            CertificatesScreen(
                workerId = entry.arguments?.getString(Route.Certificates.ARG_WORKER).orEmpty(),
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = Route.Verify.pattern,
            arguments = listOf(
                navArgument(Route.Verify.ARG_QR) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            VerifyScreen(
                incomingQr = entry.arguments?.getString(Route.Verify.ARG_QR),
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = Route.Hazard.pattern,
            arguments = listOf(navArgument(Route.Hazard.ARG_WORKER) { type = NavType.StringType }),
        ) { entry ->
            HazardScreen(
                reporterWorkerId = entry.arguments?.getString(Route.Hazard.ARG_WORKER),
                onDone = { navController.popBackStack() },
            )
        }

        composable(Route.Supervisor.pattern) {
            SupervisorScreen(
                onBack = { navController.popBackStack() },
                onSiteScan = { navController.navigate(Route.SiteScan.pattern) },
                onVoiceEnroll = { navController.navigate(Route.VoiceEnroll.pattern) },
                onVerify = { navController.navigate(Route.Verify.of()) },
            )
        }

        composable(Route.SiteScan.pattern) {
            SiteScanScreen(onBack = { navController.popBackStack() })
        }

        composable(Route.VoiceEnroll.pattern) {
            VoiceEnrollScreen(onBack = { navController.popBackStack() })
        }

        composable(
            route = Route.Buddy.pattern,
            arguments = listOf(
                navArgument(Route.Buddy.ARG_WORKER) { type = NavType.StringType },
                navArgument(Route.Buddy.ARG_SCENARIO) { type = NavType.StringType },
            ),
        ) { entry ->
            val args = entry.arguments
            BuddyScreen(
                workerId = args?.getString(Route.Buddy.ARG_WORKER).orEmpty(),
                scenarioId = args?.getString(Route.Buddy.ARG_SCENARIO).orEmpty(),
                onPaired = { workerId, scenarioId ->
                    navController.navigate(Route.Drill.of(workerId, scenarioId, MODE_BUDDY)) {
                        popUpTo(Route.Buddy.pattern) { inclusive = true }
                    }
                },
                onCancelled = { navController.popBackStack() },
            )
        }
    }
}

/** Mode token used in the drill route. Matches `AssessmentMode.BUDDY.name`. */
private const val MODE_BUDDY = "BUDDY"
