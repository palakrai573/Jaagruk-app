import java.util.Properties

/**
 * A build-time string that can be overridden without editing this file.
 *
 * Order: `-Pkey=value` on the command line, then `local.properties`, then the default. The server
 * address is a deployment detail, not a source-controlled constant, and `local.properties` is
 * already gitignored - which is the right place for a per-machine endpoint.
 */
fun buildConfigString(key: String, default: String): String {
    providers.gradleProperty(key).orNull?.let { return it }
    val localProperties = rootProject.file("local.properties")
    if (localProperties.isFile) {
        val properties = Properties()
        localProperties.inputStream().use(properties::load)
        properties.getProperty(key)?.let { return it }
    }
    return default
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    // The sync DTOs are @Serializable. They are here in phase 4 even though the network layer is
    // not, because the repositories build upload payloads at the moment a record is created rather
    // than when it is sent - a certificate minted underground has to be queued complete.
    alias(libs.plugins.kotlin.serialization)
}

providers.gradleProperty("jaagruk.appBuildDir").orNull?.let {
    layout.buildDirectory.set(file(it))
}

android {
    namespace = "org.jaagruk.safety"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        // Changed from com.infinity.ai in phase 2, alongside the source package move, so R and
        // BuildConfig relocate once rather than twice.
        //
        // A new applicationId means a new app rather than an upgrade, so the old build stays
        // installed beside this one as a reference. It also means a fresh filesDir: on a
        // `bundled` build the model extracts again for the new package, and the old app keeps
        // its own copy until it is uninstalled.
        applicationId = "org.jaagruk.safety"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Room exports its schema to app/schemas so a future migration shows up as a reviewable
        // diff. Without this, a schema change is invisible until it destroys somebody's unsynced
        // certificates on upgrade.
        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
        }

        // The app module has no native code of its own any more: llama.cpp lives in :ai.
        // These filters decide which ABIs of :ai's .so get packaged, and they must match
        // :ai's own abiFilters or the APK would carry a slice with no library in it.
        ndk {
            abiFilters += setOf("arm64-v8a", "x86_64")
        }
    }

    signingConfigs {
        create("localRelease") {
            val password = System.getenv("JAAGRUK_SIGNING_PASSWORD")
            if (!password.isNullOrBlank()) {
                storeFile = rootProject.file(".signing/release.p12")
                storePassword = password
                keyAlias = "jaagruk-release"
                keyPassword = password
            }
        }
    }

    buildTypes {
        release {
            // Preserve the installed debug app and its local records during release validation.
            applicationIdSuffix = ".release"
            signingConfig = signingConfigs.getByName("localRelease")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            buildConfigField(
                "String",
                "API_BASE_URL",
                "\"${buildConfigString("jaagruk.releaseApiBaseUrl", "https://jaagruk.jharkhand.gov.in/")}\"",
            )
            buildConfigField("boolean", "ALLOW_CLEARTEXT", "false")
        }
        debug {
            isJniDebuggable = true
            // 10.0.2.2 is the host loopback as seen from the Android emulator.
            buildConfigField(
                "String",
                "API_BASE_URL",
                "\"${buildConfigString("jaagruk.apiBaseUrl", "http://10.0.2.2:8000/")}\"",
            )
            buildConfigField("boolean", "ALLOW_CLEARTEXT", "true")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    bundle { language { enableSplit = false } }

    // How the model reaches the handset. See docs/REVAMP_PLAN.md §7.1.
    //
    // The two flavours exist because there is no single answer that is right for both a
    // sideloaded demo and a Play release, and pretending otherwise would mean picking the
    // wrong one for one of them.
    flavorDimensions += "delivery"
    productFlavors {
        create("bundled") {
            dimension = "delivery"
            // The model travels inside the APK. Install it and the model is there: no network,
            // no Play, no second step. Costs ~1.6 GB on device, because an APK asset cannot be
            // memory-mapped and has to be extracted to a real path before llama.cpp can open it.
            // This is the build to hand somebody.
            versionNameSuffix = "-bundled"
        }
        create("lean") {
            dimension = "delivery"
            // No model in the APK. It arrives by fast-follow asset pack on Play, by a file
            // dropped into app-specific external storage, or over the Nearby relay from a
            // supervisor's handset. Pays 769 MiB once instead of twice.
            versionNameSuffix = "-lean"
        }
    }

    androidResources {
        // "gguf" matters for the bundled flavour: a stored asset is a straight read during
        // extraction and reports a real length through openFd, where a deflated one would need
        // inflating and could not report progress against a known total.
        noCompress += listOf("gguf", "bin", "model", "task")
    }

    // Accessibility and localisation are functional requirements in this app, not polish.
    //
    // A worker who cannot read operates it by pictogram and TalkBack, so an unlabelled control
    // is a broken control. And a Santali speaker shown an untranslated English string has been
    // handed a safety instruction they cannot read. Both are build failures rather than warnings
    // in a report nobody opens.
    lint {
        abortOnError = true
        warningsAsErrors = false
        fatal += setOf("ContentDescription", "MissingTranslation")
        // Written so a failure can be read rather than guessed at.
        textReport = true
        htmlReport = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // BouncyCastle (:core's Ed25519 signing) and jspecify both ship this file in the
            // Java 9 multi-release section of their jars, and the merger cannot pick a winner.
            // It is OSGi container metadata; nothing on Android reads it.
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
}

dependencies {
    implementation(libs.sceneview.ar)
    // Every certification rule, scored offline and unit tested on a plain JVM.
    implementation(project(":core"))
    // The on-device model: vendored llama.cpp, JNI, model store, AR interlock.
    implementation(project(":ai"))

    implementation(libs.androidx.core.ktx)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // The site's Ed25519 signing key lives in EncryptedSharedPreferences backed by a Keystore
    // master key. Android Keystore's own Ed25519 support is unreliable below API 33, which is why
    // the key is software-held in an encrypted store rather than hardware-held.
    implementation(libs.androidx.security.crypto)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Retained comprehension aid: read a printed DGMS circular or an MSDS sheet.
    implementation(libs.mlkit.text.recognition)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
