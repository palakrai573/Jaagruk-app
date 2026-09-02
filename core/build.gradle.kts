import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// :core is deliberately a plain Kotlin/JVM library.
//
// Everything that decides whether a worker is certified -- scoring, hesitation
// detection, Ed25519 signing, hash-chain linkage, QR encoding, readiness decay,
// keyword spotting, buddy-drill sequencing -- lives here, so all of it is unit
// tested on a normal JVM with no emulator, no Android SDK and no device.

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        // Warnings stay warnings: a red build from a deprecation in a transitive
        // library is noise, not signal.
        allWarningsAsErrors.set(false)
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    // api, not implementation: :android-app signs and verifies with the same types.
    api(libs.bouncycastle.prov)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit.jupiter.params)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.serialization.json)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        // On, deliberately: DtwSeparationTest prints the measured acoustic distance profile that
        // justifies the voice thresholds, and a reviewer should be able to see those margins in
        // the build output rather than take the constants on trust.
        showStandardStreams = true
    }
}

// Regenerates the cross-language canonical-encoding fixtures that the Python backend is also
// asserted against. Run deliberately, never as part of `build`: a fixture that regenerates
// itself would defeat the entire point of pinning the format.
tasks.register<JavaExec>("generateFixtures") {
    group = "jaagruk"
    description = "Rewrites core/src/test/resources/fixtures/attestation_vectors.json."
    mainClass.set("org.jaagruk.core.tools.FixtureGenerator")
    classpath = sourceSets["test"].runtimeClasspath
    args(
        rootProject.layout.projectDirectory
            .file("core/src/test/resources/fixtures/attestation_vectors.json")
            .asFile
            .absolutePath,
    )
}

// Writes out every string key, pictogram and AR target the catalog references. The Android
// resource files are checked against this, so adding a scenario option cannot silently skip the
// Hindi and Santali translations -- `MissingTranslation` is a fatal lint check in :android-app.
tasks.register<JavaExec>("dumpCatalogManifest") {
    group = "jaagruk"
    description = "Writes build/catalog-manifest.txt: string keys, pictograms and AR targets."
    mainClass.set("org.jaagruk.core.tools.CatalogManifest")
    classpath = sourceSets["test"].runtimeClasspath
    args(layout.buildDirectory.file("catalog-manifest.txt").get().asFile.absolutePath)
}
