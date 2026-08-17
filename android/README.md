# Tracktrip Android app

Kotlin + Jetpack Compose client for the trip-tracker backend. Lives in the
same monorepo as the Node backend; this folder is a self-contained Gradle
project.

**Current state:** Google sign-in is wired end to end — Credential Manager
obtains a Google ID token, it's exchanged at the backend's
`POST /auth/google`, and the returned tokens are stored in
`EncryptedSharedPreferences`. On success the app moves to a placeholder
`TripListScreen`; on failure it shows an inline message rather than crashing.

Before it will actually run, the **web client ID must be filled in** — see
the configuration section directly below.

## Configuration — where the Client IDs live

**Everything environment-specific is in one file:
[`app/src/main/res/values/config.xml`](app/src/main/res/values/config.xml).**
If the Google Cloud project or the API host ever changes, that file is the
only thing to edit.

| Resource | What it is | Used where |
|---|---|---|
| `google_web_client_id` | **Web** OAuth client ID | Passed to `GetGoogleIdOption.setServerClientId(...)` in `MainActivity.kt`, and verified by the backend |
| `google_android_client_id` | **Android** OAuth client ID | **Not referenced in code** — see below |
| `api_base_url` | Backend base URL (`https://api.ptrip.app`) | `AuthApi` |

Two things that routinely trip people up:

**The Web client ID is the correct one to pass to Credential Manager**, not
the Android one. `setServerClientId()` wants the client ID of the *server*
that will verify the token. The returned Google ID token carries that value
in its `aud` claim, and the backend checks it against its own
`GOOGLE_CLIENT_ID`. So the same web client ID must appear in **both**
`config.xml` here and the backend's `.env`.

**The Android client ID is never passed to any API.** It exists so Google can
authorise *this app* to request tokens, matched by package name + signing
certificate SHA-1 registered in Google Cloud Console. It's recorded in
`config.xml` purely as documentation. A consequence worth knowing: debug
builds use application ID `app.ptrip.tracktrip.debug` and the debug keystore,
so they need their **own** Android OAuth client registration (with the debug
SHA-1) or sign-in will fail with a "developer error" in debug while working
in release.

> ⚠️ The client IDs currently in `config.xml` are **incomplete placeholders**
> containing `__FILL_IN__`, because only truncated values were available when
> this was written. Sign-in will not work until the full web client ID is
> pasted in. The app detects the placeholder and shows
> "Google sign-in isn't configured yet…" rather than failing with an opaque
> Google error.

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
- **`androidx.security:security-crypto`** for `EncryptedSharedPreferences`,
  so the access/refresh tokens are encrypted at rest (AES256-GCM values,
  AES256-SIV keys) under an Android Keystore master key — never plain text.

## Sign-in flow

```
SignInScreen  ──tap──▶  CredentialManager.getCredential()
                              │  GetGoogleIdOption(serverClientId = WEB client id)
                              ▼
                        Google ID token
                              │
                              ▼
        AuthApi ──POST {idToken}──▶  https://api.ptrip.app/auth/google
                              │
                              ▼
              { accessToken, refreshToken, user }
                              │
                              ▼
                TokenStore (EncryptedSharedPreferences)
                              │
                              ▼
                        TripListScreen
```

Relevant files:

| File | Role |
|---|---|
| `MainActivity.kt` | Credential Manager call + which screen to show |
| `ui/SignInViewModel.kt` | `SignedOut / Loading / Error / SignedIn` state |
| `ui/SignInScreen.kt` | Button, spinner, inline error text |
| `ui/TripListScreen.kt` | Placeholder post-sign-in screen |
| `data/AuthApi.kt` | OkHttp call to `POST /auth/google` |
| `data/TokenStore.kt` | Encrypted token storage |

Every failure path — no Google account, user dismissed the sheet, network
down, non-2xx from the backend, malformed response — is caught and turned
into a short message on screen. Nothing throws to the top level.

Tokens are stored but **not yet used**: there's no authenticated request or
refresh-on-401 logic, since no screen calls a protected endpoint yet.
Navigation is deliberately simple state switching in `MainActivity` rather
than `navigation-compose`; worth revisiting once there's more than one real
screen.

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
