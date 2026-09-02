import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// The API host is a build-time constant so a debug build can point at a laptop on the site LAN
// while a release build points at the DGMS server. Override with -Pjaagruk.apiBaseUrl=... or in
// local.properties; neither is committed.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun buildConfigString(key: String, default: String): String =
    (project.findProperty(key) as String?)
        ?: localProperties.getProperty(key)
        ?: default

android {
    namespace = "org.jaagruk.safety"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "org.jaagruk.safety"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Only the languages that are actually translated. Without this, resource shrinking keeps
        // every locale AAPT2 has ever heard of and the APK carries dead weight.
        resourceConfigurations += setOf("en", "hi", "sat")

        // Room exports its schema so a migration can be reviewed in a diff rather than trusted.
        ksp { arg("room.schemaLocation", "$projectDir/schemas") }

        // MediaPipe and ARCore each ship native libraries per ABI, and 32-bit x86 accounts for the single
        // largest slice of them while running on nothing anybody uses: no shipped device is x86, and every
        // current emulator image is x86_64 or arm64. Dropping it removes about 26 MB of dead weight.
        ndk {
            abiFilters += setOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }

        // Cloud Anchors need a Google Cloud ARCore API key, which cannot be committed. Absent one,
        // site anchors are session-scoped: a supervisor scans, and drills run against those anchors
        // until the session ends. The app says so rather than implying cross-device persistence it
        // cannot deliver. Supply with -Pjaagruk.arcoreApiKey=... or in local.properties.
        val cloudAnchorKey = buildConfigString("jaagruk.arcoreApiKey", "")
        buildConfigField("String", "ARCORE_API_KEY", "\"$cloudAnchorKey\"")
        buildConfigField("boolean", "CLOUD_ANCHORS_ENABLED", cloudAnchorKey.isNotBlank().toString())
        manifestPlaceholders["arcoreApiKey"] = cloudAnchorKey
    }

    /**
     * Release signing.
     *
     * A real keystore is never committed, so this reads one from `local.properties` or a Gradle property when
     * available and otherwise leaves the release build signed by the debug key. That is deliberate and stated
     * rather than hidden: a debug-signed release APK installs by sideload, which is what a reviewer or a pilot
     * site needs, and it cannot be published to Play — which is correct, because it should not be.
     */
    signingConfigs {
        create("releaseDemo") {
            val storePath = buildConfigString("jaagruk.keystorePath", "")
            if (storePath.isNotBlank() && rootProject.file(storePath).exists()) {
                storeFile = rootProject.file(storePath)
                storePassword = buildConfigString("jaagruk.keystorePassword", "")
                keyAlias = buildConfigString("jaagruk.keyAlias", "jaagruk")
                keyPassword = buildConfigString("jaagruk.keyPassword", "")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
            // 10.0.2.2 is the host loopback as seen from the Android emulator.
            buildConfigField(
                "String",
                "API_BASE_URL",
                "\"${buildConfigString("jaagruk.apiBaseUrl", "http://10.0.2.2:8000/")}\"",
            )
            buildConfigField("boolean", "ALLOW_CLEARTEXT", "true")
            buildConfigField("boolean", "VERBOSE_LOGGING", "true")
        }
        release {
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
            buildConfigField("boolean", "VERBOSE_LOGGING", "false")

            // Debuggable release builds ship in more government tenders than anyone admits.
            isDebuggable = false

            // Falls back to the debug key when no keystore is configured, so `assembleRelease` always
            // produces something installable rather than an unsigned APK nobody can put on a phone.
            val configured = signingConfigs.getByName("releaseDemo").storeFile != null
            signingConfig = if (configured) {
                signingConfigs.getByName("releaseDemo")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    /**
     * One APK per ABI, plus a universal one.
     *
     * MediaPipe and ARCore native libraries dominate the download, and a phone only ever needs its own. The
     * universal APK stays because a reviewer sideloading onto an unknown handset should not have to work out
     * which slice they need.
     */
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-Xjsr305=strict",
            // Compose needs these for the AR surface callbacks that cross coroutine boundaries.
            "-opt-in=kotlin.RequiresOptIn",
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            // MediaPipe, BouncyCastle and OkHttp all ship overlapping metadata.
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/INDEX.LIST",
                "/META-INF/io.netty.versions.properties",
                "META-INF/*.kotlin_module",
            )
        }
        // The TFLite hand-tracking model must stay uncompressed or MediaPipe cannot mmap it.
        jniLibs { useLegacyPackaging = false }
    }

    lint {
        warningsAsErrors = false
        abortOnError = false
        // Accessibility and localisation are functional requirements here, not nice-to-haves:
        // a missing content description or an untranslated string is a worker who cannot proceed.
        fatal += setOf("ContentDescription", "MissingTranslation")
        disable += setOf("GradleDependency", "ObsoleteLintCustomCheck")
        htmlReport = true
        textReport = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

// MediaPipe declares `com.google.auto.value:auto-value` as a runtime dependency rather than
// `compileOnly`, which drags an annotation processor into the APK. Its classes reference
// `javax.lang.model.*`, which does not exist on Android, so R8 fails the release build on classes that
// could never have run on a phone anyway. Excluded at the configuration level so the reason lives in one
// place rather than being papered over with `-dontwarn` alone.
// Scoped to the *runtime* classpath only. Room's KSP processor legitimately needs AutoValue on the
// annotation-processing classpath, so a blanket exclusion breaks `kspDebugKotlin` with a
// NoClassDefFoundError — which is a far more confusing failure than the R8 warning it was fixing.
configurations.configureEach {
    if (name.contains("RuntimeClasspath")) {
        exclude(group = "com.google.auto.value", module = "auto-value")
    }
}

dependencies {
    // Every piece of load-bearing logic lives in :core, which is a plain JVM module with 437
    // unit tests. The Android layer is deliberately a thin shell around it.
    implementation(project(":core"))

    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.kotlinx.serialization.json)

    // --- androidx / compose ---------------------------------------------
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // --- persistence ------------------------------------------------------
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    // DataStore is deliberately not used. Two durable stores are enough and each has a reason: Room for
    // records, and Keystore-backed EncryptedSharedPreferences for the site signing key. A third would be a
    // third place to look when something is missing.
    implementation(libs.androidx.security.crypto)

    // --- di ---------------------------------------------------------------
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    // --- background work --------------------------------------------------
    implementation(libs.androidx.work.runtime.ktx)

    // --- ar, camera, ml ---------------------------------------------------
    implementation(libs.arcore)
    implementation(libs.mediapipe.tasks.vision)
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.mlkit.barcode.scanning)

    // --- peer to peer -----------------------------------------------------
    // Nearby only. `play-services-location` is deliberately absent: there is no GPS fix underground, so
    // hazards are located by zone label or AR anchor instead, and pulling in a location SDK the app never
    // calls would add weight and invite a permission question nobody can justify.
    implementation(libs.play.services.nearby)

    // --- networking -------------------------------------------------------
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    debugImplementation(libs.okhttp.logging)

    // --- qr ---------------------------------------------------------------
    // ZXing encodes; ML Kit decodes. No image-loading library: every graphic in the app is drawn in Compose
    // or is a vector, and nothing is fetched from a network.
    implementation(libs.zxing.core)

    // --- test -------------------------------------------------------------
    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.mockk)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.work.testing)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
