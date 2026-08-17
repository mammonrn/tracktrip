package app.ptrip.tracktrip.auth

import android.content.Context
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

const val GOOGLE_SIGN_IN_LOG_TAG = "TracktripSignIn"

sealed interface GoogleSignInResult {
    data class Success(val idToken: String) : GoogleSignInResult

    /** User dismissed the sheet — not an error. */
    data object Cancelled : GoogleSignInResult

    data class Failure(val reason: GoogleSignInFailure) : GoogleSignInResult
}

enum class GoogleSignInFailure {
    /**
     * Every strategy came back with no credential.
     *
     * Despite the name of the underlying exception, this very rarely means
     * "the device has no Google account". With
     * setFilterByAuthorizedAccounts(false) and the Sign in with Google
     * fallback, the usual cause is that Play Services refused to surface any
     * account because the calling app isn't recognised: its package name +
     * signing certificate SHA-1 aren't registered as an Android OAuth client
     * in the same Google Cloud project that owns the server client ID.
     */
    NO_CREDENTIAL,

    /** Got a credential, but not a Google ID token. */
    UNEXPECTED_CREDENTIAL,

    /** Anything else — Play Services missing/outdated, transient errors. */
    OTHER,
}

/**
 * Requests a Google ID token via Credential Manager.
 *
 * Tries three strategies in order, because a single request that comes back
 * empty is indistinguishable from "no accounts" without them:
 *
 *  1. [GetGoogleIdOption] filtering to previously-authorized accounts — the
 *     quiet path for users who have signed in before.
 *  2. [GetGoogleIdOption] with the filter off — needed for a first-ever
 *     sign-in, where nobody has authorized this app yet.
 *  3. [GetSignInWithGoogleOption] — the explicit "Sign in with Google" button
 *     flow, which always presents the account chooser. This one can still
 *     succeed on devices where the ID-token options return nothing.
 *
 * A cancellation at any step is returned immediately: the user said no, so
 * silently retrying with a different strategy would be wrong.
 */
suspend fun requestGoogleIdToken(
    context: Context,
    webClientId: String,
    credentialManager: CredentialManager = CredentialManager.create(context),
): GoogleSignInResult {
    val strategies = listOf(
        "GoogleIdOption(filterByAuthorizedAccounts=true)" to {
            GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(true)
                .setServerClientId(webClientId)
                .build()
        },
        "GoogleIdOption(filterByAuthorizedAccounts=false)" to {
            GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(webClientId)
                .build()
        },
        "SignInWithGoogleOption" to {
            GetSignInWithGoogleOption.Builder(webClientId).build()
        },
    )

    var sawNoCredential = false

    for ((label, buildOption) in strategies) {
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(buildOption())
            .build()

        try {
            val response = credentialManager.getCredential(context = context, request = request)
            val credential = response.credential

            if (
                credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                Log.i(GOOGLE_SIGN_IN_LOG_TAG, "Got a Google ID token via $label")
                return GoogleSignInResult.Success(
                    GoogleIdTokenCredential.createFrom(credential.data).idToken
                )
            }

            Log.w(
                GOOGLE_SIGN_IN_LOG_TAG,
                "$label returned an unexpected credential type: ${credential.type}",
            )
            return GoogleSignInResult.Failure(GoogleSignInFailure.UNEXPECTED_CREDENTIAL)
        } catch (e: GetCredentialCancellationException) {
            Log.i(GOOGLE_SIGN_IN_LOG_TAG, "User cancelled sign-in during $label")
            return GoogleSignInResult.Cancelled
        } catch (e: NoCredentialException) {
            // Expected for step 1 on a first-ever sign-in; keep going.
            sawNoCredential = true
            Log.w(GOOGLE_SIGN_IN_LOG_TAG, "$label returned no credential, trying next strategy", e)
        } catch (e: GetCredentialException) {
            // Log the concrete type/message — without this, every distinct
            // cause looked identical from the outside.
            Log.e(
                GOOGLE_SIGN_IN_LOG_TAG,
                "$label failed: ${e.javaClass.simpleName}: ${e.message}",
                e,
            )
            return GoogleSignInResult.Failure(GoogleSignInFailure.OTHER)
        } catch (e: Exception) {
            Log.e(GOOGLE_SIGN_IN_LOG_TAG, "$label threw ${e.javaClass.simpleName}: ${e.message}", e)
            return GoogleSignInResult.Failure(GoogleSignInFailure.OTHER)
        }
    }

    if (sawNoCredential) {
        Log.e(
            GOOGLE_SIGN_IN_LOG_TAG,
            "All sign-in strategies returned no credential. This usually means this build's " +
                "package name + signing SHA-1 are not registered as an Android OAuth client in " +
                "the Google Cloud project that owns the server client ID — not that the device " +
                "has no Google account.",
        )
    }
    return GoogleSignInResult.Failure(GoogleSignInFailure.NO_CREDENTIAL)
}
