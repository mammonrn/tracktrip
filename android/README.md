# Tracktrip Android app

Kotlin + Jetpack Compose client for the trip-tracker backend. Lives in the
same monorepo as the Node backend; this folder is a self-contained Gradle
project.

**Current state:** signed-in riders can see their trips, create one, accept
an invitation, and open a trip to invite riders by email, see who's on it, and
(as owner) end it. There is a settings screen for the profile, language and
sharing defaults, and signing out. Live tracking is not here yet.

Google sign-in is wired end to end — Credential Manager obtains a Google ID
token, it's exchanged at the backend's `POST /auth/google`, and the returned
tokens are stored in `EncryptedSharedPreferences`. On failure the app shows an
inline message rather than crashing.

## Screens

| Screen | Backend it talks to |
|---|---|
| Sign-in | `POST /auth/google` |
| Trip list, with pending invitations above it | `GET /trips`, `GET /invites`, `POST /invites/:id/accept` |
| Create trip | `POST /trips` |
| Trip detail — members, invite, end trip | `GET /trips/:id/positions`, `POST /trips/:id/invites`, `POST /trips/:id/end` |
| Settings — profile, language, sharing default, sign out | nothing yet |

Reached by the gear in the trip list's header. Signing out lives there, not on
the trip list: it is the app's one destructive control, and it belongs behind a
screen the rider opens deliberately.

Navigation is a plain sealed `Screen` hierarchy over a small [`BackStack`](
app/src/main/java/app/ptrip/tracktrip/ui/Navigation.kt), wired to the system
back button through `BackHandler` — not Navigation Compose. Five screens and
one argument don't need a route DSL, and at the time of writing the only
published `navigation-compose` builds were 2.10.0 pre-releases.

## The look

A heads-up display: deep navy ground, amber for primary actions and headings,
cyan for secondary actions and anything live, ember for destructive ones.
Buttons are pills with a drawn glow, dividers are translucent accent rather
than grey, and headings run monospaced and widely tracked.

Every colour is defined in [`ui/theme/Theme.kt`](
app/src/main/java/app/ptrip/tracktrip/ui/theme/Theme.kt) and nowhere else, so
retuning the palette is one file. Screens compose the shared pieces in
[`ui/theme/HudComponents.kt`](
app/src/main/java/app/ptrip/tracktrip/ui/theme/HudComponents.kt) — buttons,
panels, dividers, top bar — rather than styling controls themselves. The icons
in [`ui/theme/HudIcons.kt`](
app/src/main/java/app/ptrip/tracktrip/ui/theme/HudIcons.kt) are drawn on a
`Canvas`, which keeps their stroke weight on the same footing as the rest of
the line work and saves pulling in an icon dependency for six glyphs.

The one colour outside `Theme.kt` is `hud_background` in `res/values/colors.xml`:
the window background the system paints before Compose starts. Keep it in step
with `HudBlack`.

## Session handling

Access tokens last an hour, which a long ride outlives. Every authenticated
call goes through [`ApiClient`](
app/src/main/java/app/ptrip/tracktrip/data/ApiClient.kt), which on a `401`
refreshes once and replays the request. Screens never see that; they only see
`SessionExpiredException` when the refresh itself fails, which drops the user
back to sign-in.

Refreshes are serialised behind a mutex. The backend rotates the refresh token
on every use and treats a re-presented old one as theft — by revoking every
token the user has — so two screens refreshing at once would sign the rider
out rather than keep them in.

## Configuration — where the Client IDs live

**Everything environment-specific is in one file:
[`app/src/main/res/values/config.xml`](app/src/main/res/values/config.xml).**
If the Google Cloud project or the API host ever changes, that file is the
only thing to edit.

| Resource | What it is | Used where |
|---|---|---|
| `google_web_client_id` | **Web** OAuth client ID | Passed to `GetGoogleIdOption.setServerClientId(...)` in `MainActivity.kt`, and verified by the backend |
| `api_base_url` | Backend base URL (`https://api.ptrip.app`) | `AuthApi` |

### Google Cloud OAuth clients (project `471850622906`)

| Client | ID | Referenced in code? |
|---|---|---|
| **Web** | `471850622906-8erl86uso7f64td0evq363k3tpfekedd.apps.googleusercontent.com` | **Yes** — `config.xml` → `google_web_client_id` |
| **Android** | `471850622906-lek8ce0l0kaohchi242a17rlbg6sabta.apps.googleusercontent.com` | **No** — recorded here only |

Neither is a secret; OAuth client IDs are public identifiers that ship inside
the app. The private key that proves the app's identity is the release
keystore, which never enters this repo.

Two things that routinely trip people up:

**The Web client ID is the correct one to pass to Credential Manager**, not
the Android one. `setServerClientId()` wants the client ID of the *server*
that will verify the token. The returned Google ID token carries that value
in its `aud` claim, and the backend checks it against its own
`GOOGLE_CLIENT_ID`. So the same web client ID must appear in **both**
`config.xml` here and the backend's `.env`.

**The Android client ID is never passed to any API**, which is why it lives
in this README rather than in `config.xml` — storing it as a string resource
would ship an unused value in the APK. It exists so Google can authorise
*this app* to request tokens, matched by package name + signing certificate
SHA-1 registered in Google Cloud Console.

A consequence worth knowing: debug builds use application ID
`app.ptrip.tracktrip.debug` and the debug keystore, so they need their **own**
Android OAuth client registration (with the debug SHA-1 and the `.debug`
package name) or sign-in will fail with a "developer error" in debug while
working fine in release. Get the debug SHA-1 with:

```bash
keytool -list -v -keystore ~/.android/debug.keystore \
  -alias androiddebugkey -storepass android | grep SHA1:
```

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
- **OkHttp** for backend calls. Responses are parsed with `org.json` from the
  platform — no Moshi/Gson/kotlinx-serialization, since the payloads are small
  and flat enough that a code-generating serializer would cost more than it
  saves.
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

Every failure path — no credential offered, user dismissed the sheet, network
down, non-2xx from the backend, malformed response — is caught and turned
into a short message on screen. Nothing throws to the top level.

### Credential Manager fallback chain

`auth/GoogleSignInHelper.kt` tries three strategies in order, stopping at the
first that yields a token (a user cancellation stops the chain immediately):

1. `GetGoogleIdOption` with `setFilterByAuthorizedAccounts(true)` — quiet
   path for users who've signed in before.
2. `GetGoogleIdOption` with the filter **off** — required for a first-ever
   sign-in, when nobody has authorized the app yet.
3. `GetSignInWithGoogleOption` — the explicit button flow, which always shows
   the account chooser and can succeed where the ID-token options return
   nothing.

Each step logs to logcat under the tag **`TracktripSignIn`**, including the
concrete exception class, Credential Manager's `type` string, and the stack
trace. Filter logcat on that tag when diagnosing sign-in problems:

```bash
adb logcat -s TracktripSignIn
```

### "Google closed the sign-in without returning an account"

Credential Manager maps an activity result of `RESULT_CANCELED` to
`GetCredentialCancellationException` — and Play Services returns
`RESULT_CANCELED` **both** when the user dismisses the sheet **and** when it
aborts internally. The exception carries nothing that distinguishes them, so
a chooser that appears, accepts a tap, and then reports "cancelled" is a
known signature of the app not being a registered OAuth client rather than of
anything the user did.

The helper therefore treats a cancellation as an *abort* when earlier
strategies already returned `NoCredentialException` — the user must have
interacted for the chooser to get that far, so a genuine dismissal is
unlikely. That surfaces a message instead of silently returning to the
sign-in button. A cancellation with no prior `NoCredentialException` is still
treated as a real user cancellation and stays silent.

Fix is the same as the section above: register this build's package name and
signing SHA-1, remembering that debug is a different pair from release.

### "Google didn't offer an account for this app"

This is `NoCredentialException` after all three strategies. The name is
misleading: it very rarely means the device has no Google account. The usual
cause is that **this build isn't a recognised OAuth client** — its package
name plus signing certificate SHA-1 aren't registered as an Android OAuth
client in the Google Cloud project that owns the server client ID.

The trap is the debug build. `applicationIdSuffix = ".debug"` means debug
installs are `app.ptrip.tracktrip.debug`, signed with the debug keystore — a
*different* package name and a *different* SHA-1 from release. Registering
only the release pair produces exactly this error in debug while release
works. Register both:

| Build | Package name | Certificate |
|---|---|---|
| debug | `app.ptrip.tracktrip.debug` | `~/.android/debug.keystore` (alias `androiddebugkey`, password `android`) |
| release | `app.ptrip.tracktrip` | your release keystore |

```bash
# debug SHA-1
keytool -list -v -keystore ~/.android/debug.keystore \
  -alias androiddebugkey -storepass android | grep SHA1:

# release SHA-1
./scripts/keystore-sha1.sh
```

Also confirm the web client ID in `config.xml` and the backend's
`GOOGLE_CLIENT_ID` are the *same* client, from the *same* Cloud project as
the Android clients above. A client ID from a different project fails the
same way.

### Devices without genuine Google Play Services (microG, Huawei) — unsupported

On devices running microG or Huawei Mobile Services instead of real Google
Play Services, the account chooser may appear but every account is rejected
with **"Account abnormality"**. That's the reimplementation failing Google's
account checks; it happens before our code sees any result and there is
nothing to fix on our side.

**Genuine Google Play Services is a requirement.** Devices without it aren't
supported, and this isn't tracked as a bug.

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

Unit tests (plain JVM, no device needed):

```bash
./gradlew testDebugUnitTest
```

These are contract tests over the backend's JSON, built from payloads copied
verbatim off a running server — `app/src/test/.../TripApiParsingTest.kt`. A
field renamed on the backend fails a test here instead of silently emptying a
screen. `org.json` is stubbed out in `android.jar` (every method throws), so
the real implementation is on the unit-test classpath.

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

## CI debug signing — pinning the debug keystore

By default AGP signs debug builds with `~/.android/debug.keystore`, creating
it if missing. A CI runner is a fresh machine every time, so **every CI build
got a different debug certificate**. Measured on two runs of `main`:

| Build | SHA-1 |
|---|---|
| CI run #14 | `5E:19:47:9A:7B:98:3A:50:37:40:A7:21:24:B7:05:F4:37:C2:23:2B` |
| CI run #8 | `4A:CC:F5:8A:18:01:AD:09:5B:36:4E:1D:13:A4:C1:7C:C2:95:45:17` |
| Poom's machine | `03:21:F8:4C:C1:3C:2E:A0:29:C3:9C:97:61:C9:67:46:3F:CE:11:E4` |

Google Sign-In only issues tokens to a package name + certificate fingerprint
registered in Cloud Console, so a CI APK could never sign in — and registering
one CI fingerprint wouldn't help, since the next build changes it again.

The build now accepts a **pinned** debug keystore. Set
`DEBUG_KEYSTORE_PATH` (plus `DEBUG_KEYSTORE_PASSWORD`, `DEBUG_KEY_ALIAS`,
`DEBUG_KEY_PASSWORD` if they aren't the Android defaults) and debug builds are
signed with it. Unset, behaviour is exactly as before.

CI reads it from the `ANDROID_DEBUG_KEYSTORE_BASE64` secret. **Until that
secret exists, CI keeps building but the APK still can't sign in** — the run
is annotated with a warning and the job summary says so.

### Creating the secret (Windows, PowerShell)

Do this once, on the machine whose debug keystore is registered in Cloud
Console:

```powershell
# 1. Confirm this is the right keystore — the SHA-1 must match what's
#    registered in Google Cloud Console for app.ptrip.tracktrip.debug
keytool -list -v -keystore "$env:USERPROFILE\.android\debug.keystore" `
  -alias androiddebugkey -storepass android | Select-String "SHA1:"

# 2. Base64-encode it to a single line
[Convert]::ToBase64String([IO.File]::ReadAllBytes("$env:USERPROFILE\.android\debug.keystore")) `
  | Set-Content -NoNewline "$env:USERPROFILE\debug.keystore.b64"

# 3. Copy to clipboard
Get-Content "$env:USERPROFILE\debug.keystore.b64" | Set-Clipboard
```

macOS/Linux equivalent:

```bash
keytool -list -v -keystore ~/.android/debug.keystore \
  -alias androiddebugkey -storepass android | grep SHA1:
base64 -w0 ~/.android/debug.keystore | pbcopy   # Linux: | xclip -selection clipboard
```

Then in **Settings → Secrets and variables → Actions → New repository
secret**:

| Secret | Value |
|---|---|
| `ANDROID_DEBUG_KEYSTORE_BASE64` | the base64 string |
| `ANDROID_DEBUG_KEYSTORE_PASSWORD` | *(optional — defaults to `android`)* |
| `ANDROID_DEBUG_KEY_ALIAS` | *(optional — defaults to `androiddebugkey`)* |
| `ANDROID_DEBUG_KEY_PASSWORD` | *(optional — defaults to `android`)* |

Delete the `.b64` file afterwards. Then re-run the workflow: every run prints
the signing SHA-1 to the job summary, so you can confirm it matches.

A debug keystore isn't really a secret — its password is the publicly known
`android` and it has no security value — but keeping it out of the repo avoids
confusing it with the release keystore, which genuinely must never be shared.

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
