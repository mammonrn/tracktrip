# Tracktrip Android app

Kotlin + Jetpack Compose client for the trip-tracker backend. Lives in the
same monorepo as the Node backend; this folder is a self-contained Gradle
project.

**Current state:** scaffold only. The first screen is a single
"Sign in with Google" button that does nothing yet — it gets wired up once
the Google OAuth Android client ID exists (which needs the keystore's SHA-1
fingerprint, see below).

## Toolchain

| Component | Version |
|---|---|
| Android Gradle Plugin | 9.3.1 |
| Gradle | 9.7.0 |
| Kotlin | 2.4.10 |
| compileSdk / targetSdk | 37 |
| **minSdk** | **26** (Android 8.0) |
| Compose BOM | 2026.08.00 |

`minSdk` is 26 because the next phase needs a foreground service for
background location tracking.

Note that AGP 9 has **built-in Kotlin support**, so there is no separate
`org.jetbrains.kotlin.android` plugin — applying it is an error. Only the
Compose compiler plugin is applied alongside AGP.

## Dependencies of note

- **Google Sign-In uses Credential Manager**
  (`androidx.credentials` + `com.google.android.libraries.identity.googleid`),
  *not* the deprecated `GoogleSignInClient` / `play-services-auth` sign-in API.
- **OkHttp** for backend calls.

## Building

```bash
cd android
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`.

Debug builds use the `.debug` application ID suffix, so a debug and a release
build can sit side by side on one device.

CI builds a debug APK on every push touching `android/**` — grab it from the
run's **Artifacts** section on the Actions tab (`tracktrip-debug-apk`).

---

## Release keystore

The release keystore is a **private key you must create yourself, on your own
machine**. It is deliberately not created here, not committed, and not stored
on GitHub.

Why it matters:

- **Lose it** → you can never ship an update to an already-published app. The
  Play Store rejects an APK for an existing package signed with a different
  key.
- **Leak it** → someone else can publish an app that Android trusts as yours.

`*.jks`, `*.keystore`, `*.p12`, and `keystore.properties` are all gitignored
(unanchored, so they're caught in subdirectories too), but the safest place
for the file is **outside this repository entirely**.

### 1. Create it (run on your own machine)

```bash
./scripts/create-release-keystore.sh
```

Writes `~/keystores/tracktrip-release.jks` — RSA 4096, alias `tracktrip`,
valid 9855 days (~27 years, comfortably past Google Play's
"valid through 2033-10-22" requirement). Pass a different path as the first
argument if you like.

Or the equivalent raw command:

```bash
keytool -genkeypair -v \
  -keystore ~/keystores/tracktrip-release.jks \
  -alias tracktrip \
  -keyalg RSA -keysize 4096 \
  -validity 9855 \
  -storetype JKS
```

> `keytool` will warn that JKS is a proprietary format and suggest PKCS12.
> That warning is harmless — Android signs fine with JKS. If you'd rather use
> the modern format, add `-storetype PKCS12` instead; both work with Gradle
> signing configs.

**Back the file up** somewhere private and durable (password manager vault,
encrypted drive), along with its password. Neither can be recovered.

### 2. Get the SHA-1 fingerprint (for the Google OAuth Android client)

One command:

```bash
keytool -list -v -keystore ~/keystores/tracktrip-release.jks -alias tracktrip | grep SHA1:
```

Or via the helper, which prints SHA-1 and SHA-256:

```bash
./scripts/keystore-sha1.sh
```

Use that SHA-1 plus the package name `app.ptrip.tracktrip` to create the
**Android** OAuth client in Google Cloud Console. Add the resulting client ID
to the backend's `GOOGLE_CLIENT_ID` (it accepts a comma-separated list, so the
web and Android client IDs can coexist).

For debug builds, Android's auto-generated debug keystore has its own
fingerprint — register that one too if you want Sign-In working in debug:

```bash
keytool -list -v -keystore ~/.android/debug.keystore \
  -alias androiddebugkey -storepass android | grep SHA1:
```

---

## Adding release signing later (not done in this change)

Release signing is intentionally **not** configured yet, because the keystore
must not touch GitHub until we deliberately put it there. When you're ready:

1. Base64-encode the keystore so it survives as a text secret:
   ```bash
   base64 -w0 ~/keystores/tracktrip-release.jks > keystore.b64
   ```
   (on macOS: `base64 -i ~/keystores/tracktrip-release.jks -o keystore.b64`)

2. Add four repository secrets under
   **Settings → Secrets and variables → Actions**:

   | Secret | Value |
   |---|---|
   | `KEYSTORE_BASE64` | contents of `keystore.b64` |
   | `KEYSTORE_PASSWORD` | the store password |
   | `KEY_ALIAS` | `tracktrip` |
   | `KEY_PASSWORD` | the key password |

   Then delete `keystore.b64` — don't leave it lying around.

3. In `app/build.gradle.kts`, add a `signingConfigs.release` block that reads
   those values from environment variables, and point
   `buildTypes.release.signingConfig` at it. Guard it so local builds without
   the env vars still work.

4. In the workflow, decode the secret into a file before building:
   ```yaml
   - name: Decode keystore
     run: echo "${{ secrets.KEYSTORE_BASE64 }}" | base64 -d > android/app/release.jks
   ```
   then run `./gradlew assembleRelease` with the passwords passed as `env:`.

Two cautions: GitHub masks secrets in logs but anyone who can push a workflow
change to the repo can exfiltrate them, so limit who has write access. And
keep the local `.jks` as the source of truth — the base64 secret is a copy,
not a backup.
