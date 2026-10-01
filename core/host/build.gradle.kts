plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

/**
 * AetherEngine — Layer 1: The Host.
 *
 * This module is the *container*, not an app. It is consumed by :app (and by
 * any OEM shell) and owns:
 *
 *   - DynamicApkLoader   : ClassLoader / Resources / native-lib plumbing
 *   - VirtualActivity    : the proxy Activity that hosts a guest's lifecycle
 *   - HostInitializer    : the ART instrumentation installed once per process
 *
 * Native stack:
 *   Kotlin <-> C++ (JNI, libaether-native.so) <-> Rust (libaether_vfs.a)
 *
 * The Rust staticlib must exist before CMake configure runs; build it with
 * ./tools/build-rust.sh (or let the `compileAetherVfs` task below do it).
 */
android {
    namespace = "dev.aether.host"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testOptions.targetSdk = libs.versions.targetSdk.get().toInt()

        consumerProguardFiles("consumer-rules.pro")

        // The guest believes it is talking to Android; these stay in the host
        // manifest, never in the guest's.
        buildConfigField("String", "HOST_AUTHORITY", "\"dev.aether.host\"")
        buildConfigField("int", "STUB_STANDARD_COUNT", "${stubCounts.standard}")
        buildConfigField("int", "STUB_SINGLE_TOP_COUNT", "${stubCounts.singleTop}")
    }

    // 64-bit guests are the interesting case; 32-bit is kept for the long tail
    // of 32-bit-only Unity/Unreal builds still shipping in the wild.
    defaultConfig.ndk {
        abiFilters += listOf("arm64-v8a", "armeabi-v7a")
    }

    externalNativeBuild {
        cmake {
            path = "src/main/cpp/CMakeLists.txt"
            version = "3.22.1"
        }
    }

    defaultConfig.externalNativeBuild {
        cmake {
            cppFlags += listOf(
                "-std=c++20",
                "-fvisibility=hidden",
                "-fno-exceptions",   // JNI boundary: we return error codes, never throw
                "-fno-rtti",
                "-Werror=implicit-function-declaration",
            )
            arguments += listOf(
                "-DANDROID_STL=c++_static",
                "-DAETHER_VFS_LIB=${rootProject.file("core/vfs/target/${'$'}{ANDROID_ABI}/release/libaether_vfs.a")}",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.jvmTarget.get())
        targetCompatibility = JavaVersion.toVersion(libs.versions.jvmTarget.get())
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = libs.versions.jvmTarget.get()
        freeCompilerArgs += listOf(
            "-opt-in=kotlin.RequiresOptIn",
            "-Xjvm-default=all",
        )
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/*.kotlin_module",
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*",
        )
        // Never let AGP strip or re-compress the guest's .so files.
        jniLibs.useLegacyPackaging = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.process)

    coreLibraryDesugaring(libs.desugar.jdk.libs)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.mockito.core)
    androidTestImplementation(libs.androidx.junit)
}

// ---------------------------------------------------------------------------
// Native build orchestration
// ---------------------------------------------------------------------------

/**
 * Cross-compile `core/vfs` for every ABI in `abiFilters` before CMake runs.
 * Gradle cannot express "cargo-ndk for each ABI" declaratively, so this task
 * shells out — and is wired into the CMake configure step below.
 */
val compileAetherVfs = tasks.register<Exec>("compileAetherVfs") {
    group = "build"
    description = "Cross-compiles core/vfs (Rust) into a staticlib for every enabled ABI."
    workingDir = rootProject.projectDir
    commandLine("./tools/build-rust.sh", "--release")
    inputs.dir(rootProject.file("core/vfs/src"))
    inputs.file(rootProject.file("core/vfs/Cargo.toml"))
    outputs.upToDateWhen {
        rootProject.file("core/vfs/target/aarch64-linux-android/release/libaether_vfs.a").exists()
    }
}

tasks.whenTaskAdded {
    if (name.startsWith("configureCMake") || name.startsWith("externalNativeBuild")) {
        dependsOn(compileAetherVfs)
    }
}

/**
 * Re-generates the VirtualActivity stubs. Run manually after changing
 * tools/gen-stubs/spec.toml; CI verifies the generated files are fresh.
 */
val generateStubs = tasks.register<Exec>("generateVirtualStubs") {
    group = "codegen"
    description = "Regenerates VirtualStubs.kt and src/main/stubs/AndroidManifest.xml."
    workingDir = rootProject.projectDir
    commandLine("./tools/gen-stubs/gen_stubs.sh")
}

object stubCounts {
    const val standard = 12
    const val singleTop = 6
}
