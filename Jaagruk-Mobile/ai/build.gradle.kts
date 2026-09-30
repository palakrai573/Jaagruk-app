plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// :ai holds the on-device language model and nothing else.
//
// It is a separate module rather than a package inside :android-app for three reasons:
//
//  1. **The NDK requirement is contained.** Only this module has an `externalNativeBuild`, so a
//     contributor without the NDK can exclude one module rather than losing the APK entirely.
//  2. **The vendored llama.cpp tree has one home.** About 7 MB of third-party C++ lives under
//     `src/main/cpp/llama`, pinned rather than fetched, so the build is reproducible offline.
//  3. **The dependency direction stays honest.** :ai depends on :core for the retrieval, prompt and
//     guard contracts. :core does not know :ai exists, which is what keeps every certification rule
//     testable on a plain JVM.

android {
    namespace = "org.jaagruk.ai"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()

        // Only the ABIs with vendored CPU kernels and enough memory to be worth it.
        //
        // armeabi-v7a is deliberately absent. A 32-bit handset has a 4 GB address space shared with
        // the ARCore session and the camera pipeline, and a 1B model at Q4 plus its KV cache does not
        // fit alongside them. `System.loadLibrary` fails on those devices, which the Kotlin layer
        // turns into AiCapability.UNSUPPORTED_DEVICE and the UI states plainly. That is a better
        // outcome than a build that installs and then dies under memory pressure mid-shift.
        //
        // x86_64 is here for the emulator, so the feature is demonstrable without a physical handset.
        ndk {
            abiFilters += setOf("arm64-v8a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    // NEON is baseline on arm64-v8a; stating it keeps the ggml ARM kernels on the
                    // vectorised path rather than the scalar fallback.
                    "-DANDROID_ARM_NEON=TRUE",
                )
            }
        }

        consumerProguardFiles("consumer-rules.pro")
    }

    // Pinned rather than left to the newest installed NDK: ggml's inline assembly and the
    // arch-specific kernels are sensitive to toolchain changes, and a silent NDK bump is not a thing
    // to debug from a crash report off a mine site.
    ndkVersion = "28.2.13676358"

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
        debug {
            // Native crashes in a vendored 7 MB C++ tree are not debuggable from a stack address.
            isJniDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-Xjsr305=strict")
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
            // Debug symbols are deliberately NOT kept. The unstripped library is 65 MB against
            // roughly 3 MB stripped, and shipping it would more than double the download for symbols
            // no handset can use. They stay in ai/build/intermediates/cxx for local crash analysis,
            // and a release build should upload its native symbol file rather than embed it.
        }
    }

    lint {
        warningsAsErrors = false
        abortOnError = false
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            // The orchestration in this module is ordinary Kotlin that happens to call
            // android.util.Log. Returning defaults rather than throwing lets AiCoach and
            // LlmSessionGuard be tested on a plain JVM, with no emulator and no native library —
            // the same reason every certification rule lives in :core.
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    // Retrieval, prompt construction, the output guard and the capability enum all live in :core,
    // where they are unit tested without an emulator. This module contributes the engine only.
    implementation(project(":core"))

    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.junit)
}
