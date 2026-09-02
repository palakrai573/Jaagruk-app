pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "jaagruk"

// ---------------------------------------------------------------------------
// :core is a pure Kotlin/JVM module. It carries every piece of load-bearing
// logic (assessment scoring, Ed25519 hash chain, QR codec, readiness decay,
// MFCC/DTW keyword spotting, buddy-drill FSM) and has zero Android imports,
// so `gradlew :core:test` runs on any machine with a JDK and no Android SDK.
// ---------------------------------------------------------------------------
include(":core")

// ---------------------------------------------------------------------------
// :android-app requires the Android SDK. Including it unconditionally would
// make even `:core:test` fail to configure on a machine without the SDK, so it
// is included only when an SDK is actually resolvable.
//
//   Auto-detected from : ANDROID_HOME | ANDROID_SDK_ROOT | local.properties(sdk.dir)
//   Force on           : -Pjaagruk.forceAndroid=true
//   Force off          : -Pjaagruk.skipAndroid=true
// ---------------------------------------------------------------------------
val skipAndroid: Boolean =
    (settings.providers.gradleProperty("jaagruk.skipAndroid").orNull ?: "false").toBoolean()

val forceAndroid: Boolean =
    (settings.providers.gradleProperty("jaagruk.forceAndroid").orNull ?: "false").toBoolean()

val localPropertiesDeclaresSdk: Boolean =
    file("local.properties").let { f ->
        f.isFile && f.readLines().any { it.trimStart().startsWith("sdk.dir") }
    }

val androidSdkAvailable: Boolean =
    System.getenv("ANDROID_HOME").isNullOrBlank().not() ||
        System.getenv("ANDROID_SDK_ROOT").isNullOrBlank().not() ||
        localPropertiesDeclaresSdk

if (!skipAndroid && (forceAndroid || androidSdkAvailable)) {
    include(":android-app")
} else {
    logger.lifecycle(
        buildString {
            appendLine()
            appendLine("  [jaagruk] :android-app was NOT included in this build.")
            appendLine("            Reason: no Android SDK found (ANDROID_HOME / ANDROID_SDK_ROOT /")
            appendLine("            local.properties 'sdk.dir').")
            appendLine("            :core still builds and tests normally -> gradlew :core:test")
            appendLine("            Open the project in Android Studio, or set ANDROID_HOME, to build the APK.")
        },
    )
}
