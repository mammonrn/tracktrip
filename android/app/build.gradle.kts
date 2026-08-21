// Imported rather than written as java.util.Properties at the use site: inside
// the `android { }` block `java` resolves to Gradle's own java extension, which
// shadows the package and fails to compile.
import java.util.Properties

plugins {
    // AGP 9 has built-in Kotlin support, so no separate kotlin-android plugin.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/**
 * android/local.properties, or an empty set of properties when it is not
 * there.
 *
 * The same file the Android SDK location lives in, and gitignored for that
 * reason long before it held a password. Read once, here, rather than by
 * each value that wants something out of it.
 */
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) {
        file.inputStream().use { load(it) }
    }
}

// Blank counts as absent, for the reason the env() above says: a key left
// in the file with nothing after the `=` is somebody who meant to fill it
// in, not somebody who chose an empty password.
fun localProperty(name: String): String? =
    localProperties.getProperty(name)?.takeIf { it.isNotBlank() }

/** The four values release signing needs, in the order the message lists them. */
val releaseSigningKeys = listOf(
    "RELEASE_STORE_FILE",
    "RELEASE_STORE_PASSWORD",
    "RELEASE_KEY_ALIAS",
    "RELEASE_KEY_PASSWORD",
)

/**
 * The keystore, or null when it cannot be used.
 *
 * `rootProject.file` resolves a relative path against android/ and leaves
 * an absolute one alone, so both spellings work in local.properties.
 */
val releaseKeystore = localProperty("RELEASE_STORE_FILE")
    ?.let(rootProject::file)
    ?.takeIf { it.isFile }

/**
 * Why a release build cannot be signed, or null when it can.
 *
 * A `String` rather than a thrown exception, because *when* this is
 * discovered matters. Throwing here would fail every Gradle invocation on
 * a machine without the keystore — including `assembleDebug` and the unit
 * tests, which is what CI runs and what a contributor runs. It is attached
 * to the release tasks instead (below the android block), so it fires
 * exactly when somebody asks for a release and stays silent otherwise.
 */
val releaseSigningProblem: String? = run {
    val missing = releaseSigningKeys.filter { localProperty(it) == null }
    val storePath = localProperty("RELEASE_STORE_FILE")
    when {
        missing.isNotEmpty() -> buildString {
            appendLine("Release signing is not configured.")
            appendLine()
            appendLine("  android/local.properties is missing: ${missing.joinToString(", ")}")
            appendLine()
            appendLine("Copy android/local.properties.example alongside it and fill in the")
            appendLine("four RELEASE_* values. See android/README.md, \"Building a release APK\".")
        }

        releaseKeystore == null -> buildString {
            appendLine("Release signing is not configured.")
            appendLine()
            appendLine("  RELEASE_STORE_FILE points at a file that is not there:")
            appendLine("    $storePath")
            appendLine("    resolved to ${rootProject.file(storePath!!).absolutePath}")
            appendLine()
            appendLine("A relative path is resolved against the android/ directory.")
        }

        else -> null
    }
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

        // versionCode is what Android compares; versionName is what a human
        // reads. **Bump versionCode on every release you hand anybody**, even
        // a one-character fix. The installer refuses an APK whose versionCode
        // is not higher than the installed one — it does not look at
        // versionName at all — so shipping twice on the same number means the
        // rider cannot update: they have to uninstall first, and uninstalling
        // takes their stored tokens with it, so they sign in again.
        //
        // It is an integer and it only ever goes up. Nothing requires it to
        // track versionName, and it is easier if it does not try.
        versionCode = 1
        versionName = "1.0.0"
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
        // Release signing, from android/local.properties — a file git ignores,
        // so the keystore's passwords stay on the machine that has the
        // keystore. Nothing here reads an environment variable: CI builds
        // debug only, and a release key that CI could reach is a release key
        // anybody with push access could sign with.
        //
        // Created only when all four values are present and the keystore is
        // really there. When they are not, there is no config to attach and
        // [releaseSigningProblem] below fails any release build outright — the
        // one thing that must never happen is a release quietly coming out
        // signed with the debug key or not signed at all, because both install
        // fine on the machine that built them and neither can be updated by
        // the real one later.
        releaseKeystore?.let { keystore ->
            create("release") {
                storeFile = keystore
                storePassword = localProperty("RELEASE_STORE_PASSWORD")
                keyAlias = localProperty("RELEASE_KEY_ALIAS")
                keyPassword = localProperty("RELEASE_KEY_PASSWORD")
            }
        }

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
            // No applicationIdSuffix, deliberately and by omission: the id is
            // defaultConfig's `app.ptrip.tracktrip` exactly. Only debug carries
            // `.debug`, which is what lets both sit on one phone at once — and
            // what makes the release id the one registered with Google.
            //
            // isMinifyEnabled stays false, matching debug. Turning R8 on is a
            // change to what the app *does* — reflection, Compose, osmdroid and
            // the JSON parsing all have shapes R8 can strip — and it belongs in
            // its own change with its own testing, not smuggled in beside a
            // signing config.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfigs.findByName("release")?.let { signingConfig = it }
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

            // Robolectric reads the merged resources and the manifest, so a
            // test that composes a screen can resolve `stringResource` for
            // real rather than against a stub that answers "".
            isIncludeAndroidResources = true
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

/**
 * Refuses to build a release that cannot be signed properly.
 *
 * On the packaging and bundling tasks rather than on `assembleRelease`, so the
 * build stops *before* producing an artefact rather than after — an unsigned
 * APK sitting in build/outputs is exactly the thing somebody sideloads by
 * mistake and cannot then update.
 *
 * `doFirst` rather than a check at configuration time: this must not fire for
 * `assembleDebug` or `testDebugUnitTest`, which is all CI runs and all a
 * contributor without the keystore ever needs.
 */
run {
    val problem = releaseSigningProblem
    val releaseTask = Regex("^(assemble|bundle|package|install)Release$")

    tasks.matching { releaseTask.matches(it.name) }.configureEach {
        doFirst {
            if (problem != null) {
                throw GradleException(problem)
            }
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
    implementation(libs.androidx.appcompat)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.osmdroid.android)
    implementation(libs.zxing.core)
    implementation(libs.zxing.android.embedded)
    implementation(libs.androidx.security.crypto)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(libs.kotlinx.coroutines.test)

    // Composing a screen on the JVM. Unit-test scope only — none of this is in
    // the APK. See PlacesMapRenderTest for why it is worth the dependency.
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    // Debug scope, not test scope: this artifact is a manifest, and the empty
    // activity it declares has to be *merged into the debug manifest* for a
    // Robolectric test to be able to launch one. On the test classpath the AAR
    // is present and the manifest is not, which fails as "unable to resolve
    // activity". Debug builds only — it never reaches a release APK.
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
