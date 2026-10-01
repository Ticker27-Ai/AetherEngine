// Root project: no code lives here, only shared conventions and housekeeping.

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.ksp) apply false
}

allprojects {
    group = "dev.aether"
    version = providers.gradleProperty("VERSION_NAME").getOrElse("0.1.0-dev")
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}

// Runs the Rust test-suite from Gradle so `./gradlew check` really does check
// everything, not just the JVM half of the monorepo.
tasks.register<Exec>("checkVfs") {
    group = "verification"
    description = "Runs `cargo test` for core/vfs (the native policy engine)."
    workingDir = file("core/vfs")
    commandLine("cargo", "test", "--all-targets")
    // Keep the host toolchain honest: this target is pure logic, no NDK needed.
    environment("RUST_BACKTRACE", "1")
}

// Regenerates docs/vfs-policy.txt; CI fails if the golden file is stale.
tasks.register<Exec>("dumpVfsPolicy") {
    group = "documentation"
    description = "Regenerates the VFS policy golden file."
    workingDir = file("core/vfs")
    commandLine("cargo", "run", "--quiet", "--example", "dump_policy")
    standardOutput = file("docs/vfs-policy.txt").outputStream()
}
