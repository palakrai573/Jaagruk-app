// Root build script.
//
// Deliberately does NOT declare the Android Gradle Plugin here. Declaring it at the
// root (even with `apply false`) makes the whole build depend on AGP resolving, which
// breaks `gradlew :core:test` on machines without an Android SDK. Each module applies
// its own plugins from the version catalog instead, so versions stay consistent while
// :core remains independently buildable.

tasks.register<Delete>("clean") {
    group = "build"
    description = "Deletes the root build directory."
    delete(layout.buildDirectory)
}

tasks.register("jaagrukVerify") {
    group = "verification"
    description = "Runs every check that does not require an Android SDK or a device."
    dependsOn(":core:test")
}
