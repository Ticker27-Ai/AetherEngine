plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

/**
 * Reference host shell.
 *
 * Deliberately thin: everything interesting lives in :core:host. This module
 * exists so the container can be launched and debugged as a real app, and so
 * CI has something to assemble.
 */
android {
    namespace = "dev.aether.host.app"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "dev.aether.host"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = providers.gradleProperty("VERSION_CODE").getOrElse("1").toInt()
        versionName = providers.gradleProperty("VERSION_NAME").getOrElse("0.1.0-dev")

        // Guests run in this process: it needs room for their dex, resources,
        // native heaps and shadow trees.
        multiDexEnabled = true
    }

    defaultConfig.ndk {
        abiFilters += listOf("arm64-v8a", "armeabi-v7a")
    }

    signingConfigs {
        // CI injects STORE_FILE / STORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD via
        // environment variables; without them this stays a debug-signed build.
        create("release") {
            val store = System.getenv("STORE_FILE")
            if (!store.isNullOrBlank()) {
                storeFile = file(store)
                storePassword = System.getenv("STORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    packaging {
        jniLibs.useLegacyPackaging = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.jvmTarget.get())
        targetCompatibility = JavaVersion.toVersion(libs.versions.jvmTarget.get())
    }
    kotlinOptions {
        jvmTarget = libs.versions.jvmTarget.get()
    }
}

dependencies {
    implementation(project(":core:host"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.process)
}
