package app.ptrip.tracktrip.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** What the backend returns from POST /auth/google. */
data class AuthSession(
    val accessToken: String,
    val refreshToken: String,
    val displayName: String?,
    val email: String?,
    val photoUrl: String?,
)

/** Any failure worth showing the user a message about. */
class AuthApiException(message: String) : Exception(message)

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

class AuthApi(
    private val baseUrl: String,
    private val client: OkHttpClient = defaultClient(),
) {

    /**
     * Exchanges a Google ID token for our own session tokens.
     *
     * Runs the blocking OkHttp call on the IO dispatcher. Throws
     * [AuthApiException] with a short, user-presentable message on any
     * failure — never leaks a raw stack trace to the UI.
     */
    suspend fun signInWithGoogle(idToken: String): AuthSession = withContext(Dispatchers.IO) {
        val body = JSONObject().put("idToken", idToken).toString()
        val request = Request.Builder()
            .url("$baseUrl/auth/google")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw AuthApiException("Can't reach the server. Check your connection.")
        }

        response.use {
            // OkHttp 5 makes Response.body non-null.
            val payload = it.body.string()

            if (!it.isSuccessful) {
                throw AuthApiException(
                    when (it.code) {
                        401 -> "Google rejected that sign-in. Try again."
                        429 -> "Too many attempts. Wait a minute and try again."
                        in 500..599 -> "The server is having trouble. Try again shortly."
                        else -> "Sign-in failed (HTTP ${it.code})."
                    }
                )
            }

            parseSession(payload)
        }
    }

    private fun parseSession(payload: String): AuthSession {
        val json = try {
            JSONObject(payload)
        } catch (e: Exception) {
            throw AuthApiException("Got an unexpected response from the server.")
        }

        val accessToken = json.optString("accessToken").takeIf { it.isNotEmpty() }
        val refreshToken = json.optString("refreshToken").takeIf { it.isNotEmpty() }
        if (accessToken == null || refreshToken == null) {
            throw AuthApiException("Server response was missing sign-in tokens.")
        }

        // `optString` returns the literal "null" for a JSON null, which is a
        // real possibility here: display_name and photo_url are both nullable
        // columns, and Google doesn't always supply a picture.
        val user = json.optJSONObject("user")
        fun field(name: String): String? =
            user?.optString(name)?.takeIf { it.isNotEmpty() && it != "null" }

        return AuthSession(
            accessToken = accessToken,
            refreshToken = refreshToken,
            displayName = field("display_name"),
            email = field("email"),
            photoUrl = field("photo_url"),
        )
    }

    private companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
