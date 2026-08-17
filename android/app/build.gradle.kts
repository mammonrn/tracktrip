plugins {
    // AGP 9 has built-in Kotlin support, so no separate kotlin-android plugin.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.ptrip.tracktrip"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.ptrip.tracktrip"
        // minSdk 26 (Android 8.0): required for the foreground-service based
        // background location tracking coming in the next phase.
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    // Optional pinned debug keystore.
    //
    // By default AGP signs debug builds with ~/.android/debug.keystore and
    // generates that file if it's missing. On an ephemeral CI runner it's
    // always missing, so every build gets a brand-new key with a different
    // SHA-1 — which Google Sign-In rejects, because only registered
    // certificate fingerprints are allowed to request tokens.
    //
    // Point DEBUG_KEYSTORE_PATH at a keystore to sign debug builds with a
    // stable, registered certificate instead. Left unset, behaviour is
    // unchanged.
    // GitHub Actions substitutes a missing secret as an EMPTY STRING rather
    // than leaving the variable unset, and Gradle reports that as present.
    // A plain `?: default` therefore never fires and the empty value wins —
    // which made AGP try to open the keystore with a blank password and fail
    // as "keystore password was incorrect". Treat blank as absent.
    fun env(name: String): String? =
        providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() }

    val debugKeystorePath = env("DEBUG_KEYSTORE_PATH")
    val pinnedDebugKeystore = debugKeystorePath
        ?.takeIf { it.isNotBlank() }
        ?.let(::file)
        ?.takeIf { it.isFile }

    signingConfigs {
        // Release signing is intentionally not configured yet — the keystore is
        // kept off git and off CI. See android/README.md for how to wire it up
        // via GitHub Secrets later.

        if (pinnedDebugKeystore != null) {
            create("debugPinned") {
                storeFile = pinnedDebugKeystore
                // Defaults match Android's standard debug keystore, so only a
                // non-standard keystore needs the env vars set.
                storePassword = env("DEBUG_KEYSTORE_PASSWORD") ?: "android"
                keyAlias = env("DEBUG_KEY_ALIAS") ?: "androiddebugkey"
                keyPassword = env("DEBUG_KEY_PASSWORD") ?: storePassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
            signingConfigs.findByName("debugPinned")?.let { signingConfig = it }
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            // Anything android.jar stubs out returns a default instead of
            // throwing. org.json is the one that matters here, and the real
            // implementation is on the test classpath ahead of the stub.
            isReturnDefaultValues = true
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Google Sign-In via Credential Manager (not the deprecated
    // GoogleSignInClient API).
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)

    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.zxing.core)
    implementation(libs.zxing.android.embedded)
    implementation(libs.androidx.security.crypto)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    testImplementation(libs.junit)
    testImplementation(libs.json)
}
