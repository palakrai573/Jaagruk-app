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

rootProject.name = "Jaagruk"

// ---------------------------------------------------------------------------
// :core is a pure Kotlin/JVM module. It carries every piece of load-bearing
// logic (assessment scoring, Ed25519 hash chain, QR codec, readiness decay,
// MFCC/DTW keyword spotting, buddy-drill FSM) and has zero Android imports, so
// `gradlew :core:test` runs on any machine with a JDK and no Android SDK.
// ---------------------------------------------------------------------------
include(":core")

// ---------------------------------------------------------------------------
// :ai holds the on-device language model: the vendored llama.cpp CPU backend,
// the JNI bridge, and the orchestration that joins :core's retrieval, prompt
// building and output guard to it. Separate from :app so the NDK requirement
// and ~7 MB of third-party C++ sit behind one module boundary.
//
// :ai and :app both need the Android SDK. Including them unconditionally would
// make even `:core:test` fail to configure on a machine without one, so they
// are included only when an SDK is actually resolvable.
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
    include(":ai")
    include(":app")
} else {
    logger.lifecycle(
        buildString {
            appendLine()
            appendLine("  [jaagruk] :ai and :app were NOT included in this build.")
            appendLine("            Reason: no Android SDK found (ANDROID_HOME / ANDROID_SDK_ROOT /")
            appendLine("            local.properties 'sdk.dir').")
            appendLine("            :core still builds and tests normally -> gradlew :core:test")
            appendLine("            Open the project in Android Studio, or set ANDROID_HOME, to build the APK.")
        },
    )
}
