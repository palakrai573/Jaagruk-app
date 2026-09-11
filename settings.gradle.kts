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
    // ---------------------------------------------------------------------------
    // :ai holds the on-device language model: the vendored llama.cpp CPU backend,
    // the JNI bridge, and the orchestration that joins :core's retrieval, prompt
    // building and output guard to it. Separate from :android-app so the NDK
    // requirement and 7 MB of third-party C++ sit behind one module boundary.
    //
    // Included unconditionally alongside :android-app, and therefore the APK build
    // needs the NDK as well as the SDK. There is deliberately no flag to leave it
    // out: :android-app references AiCoach directly, so an absent module would not
    // compile, and an escape hatch that does not work is worse than none.
    // `tools\bootstrap-android-sdk.ps1` installs the NDK and CMake alongside the
    // platform, and `docs/ARCHITECTURE.md` states the requirement.
    // ---------------------------------------------------------------------------
    include(":ai")
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
