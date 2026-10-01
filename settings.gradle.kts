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

rootProject.name = "AetherEngine"

// ---------------------------------------------------------------------------
// Language boundaries
//
//  :core:host      Kotlin + C++ (JNI)  — the virtualisation container itself
//  :app            Kotlin             — thin launcher shell around :core:host
//  :core:vfs       Rust               — NOT a Gradle module. Built by
//                                       tools/build-rust.sh (cargo-ndk) and
//                                       consumed as a staticlib by CMake.
//  ui/flutter      Dart               — embedded Flutter module (optional)
// ---------------------------------------------------------------------------

include(":core:host")
include(":app")

// Flutter is embedded with the standard "android module" dance. It is skipped
// (not failed) when the Flutter SDK is absent so that Android-only CI jobs and
// contributors who never touch the UI still get a green sync.
val flutterModule = file("ui/flutter/.android/include_flutter.groovy")
if (flutterModule.exists()) {
    apply(from = flutterModule)
    include(":aether_ui")
    project(":aether_ui").projectDir = file("ui/flutter")
} else {
    logger.lifecycle("Flutter module not found at ui/flutter — building Android-only. Run 'flutter create -t module aether_ui' or see docs/PHASE0-1.md.")
}
