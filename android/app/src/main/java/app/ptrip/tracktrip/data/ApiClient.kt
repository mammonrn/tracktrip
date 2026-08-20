package app.ptrip.tracktrip.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * A failure with a message already fit to put in front of the user.
 *
 * [status] is the HTTP status it came from, or null when the request never
 * got an answer at all — no connection, DNS, a timeout.
 *
 * Carried because the message alone is not enough for a caller that can say
 * something better than this class can. The place search is the case that
 * proved it: a 404 on `/geocode/search` means the *route is not on the
 * server*, and the generic wording for a 404 — "That's no longer there." —
 * sent a real debugging session looking for a missing place instead of a
 * backend that had never been deployed.
 */
open class ApiException(message: String, val status: Int? = null) : Exception(message)

/**
 * The backend refused because this rider is already out on a trip.
 *
 * A class of its own rather than a 409 with a message, because this is the one
 * refusal the app can word better than the server can. Server messages are
 * English — the client shows them verbatim, which is right for "trip has
 * ended" and for everything else a rider meets once — and this one they will
 * meet whenever they forget to end a ride, on a phone that may well be set to
 * Thai. So the body carries `code` and the trip that is in the way, and the
 * screens say it in the rider's own language, with [message] left as the
 * fallback for a server that has not learned the code yet.
 */
class ActiveTripException(
    message: String,
    val tripId: Long?,
    val tripName: String,
) : ApiException(message, 409) {

    companion object {
        /** The `code` the backend sends with it. See src/trips/activeTrip.js. */
        const val CODE = "active_trip_exists"
    }
}

/**
 * The session is gone for good — the refresh token was rejected, so there is
 * nothing left to retry with and the user has to sign in again.
 */
class SessionExpiredException : ApiException("Your session expired. Please sign in again.", 401)

/**
 * Puts the verb on the request.
 *
 * A function of its own, and tested on its own, because the `when` it replaces
 * had a hole in it: [ApiClient.delete] has existed since shared places did and
 * there was no `"DELETE"` branch, so every removal — a shared place, a private
 * place, a waypoint — fell through to the throw. `IllegalArgumentException` is
 * not an `ApiException`, so no view model caught it either; the failure left
 * the coroutine rather than the error line.
 *
 * Nothing about it needed a server to catch, only somewhere a test could reach
 * without a `Context` and a socket — which is what this now is. Adding a verb
 * to [ApiClient] without adding it here fails `ApiMethodsTest`.
 *
 * [body] is passed for every verb and used by the ones that carry one, so the
 * caller does not have to know which those are.
 */
internal fun Request.Builder.applyMethod(method: String, body: RequestBody): Request.Builder =
    when (method) {
        "GET" -> get()
        "POST" -> post(body)
        "PATCH" -> patch(body)
        // Explicitly bodyless: OkHttp's no-argument `delete()` attaches an
        // empty one, and a DELETE carrying Content-Length: 0 is the shape some
        // proxies are fussiest about. Nothing is sent and 204 comes back —
        // see [ApiClient.delete].
        "DELETE" -> delete(null)
        else -> throw IllegalArgumentException("unsupported method $method")
    }

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

/**
 * Every authenticated call to the backend goes through here.
 *
 * Access tokens last an hour, so a long ride will outlive one mid-session.
 * Rather than make each screen deal with that, a 401 is handled once here:
 * refresh, then replay the original request. Only a failed *refresh* reaches
 * the caller, as [SessionExpiredException].
 */
class ApiClient(
    private val baseUrl: String,
    private val tokenStore: TokenStore,
    private val client: OkHttpClient = defaultClient(),
) {

    /**
     * Serialises refreshes. Several screens can be loading at once, and
     * without this each of their 401s would fire its own refresh — with the
     * backend rotating the refresh token every time, the losers of that race
     * would present an already-rotated token, which the backend deliberately
     * treats as theft and answers by revoking every token the user has.
     */
    private val refreshMutex = Mutex()

    suspend fun get(path: String): String = send("GET", path, body = null)

    suspend fun post(path: String, body: JSONObject? = null): String =
        send("POST", path, jsonBody(body))

    suspend fun patch(path: String, body: JSONObject): String = send("PATCH", path, jsonBody(body))

    /**
     * No body, and none expected back: the endpoints that use this answer 204.
     * [send] returns the body as a string, which for a 204 is the empty one.
     */
    suspend fun delete(path: String): String = send("DELETE", path, body = null)

    /**
     * A single file upload, as multipart/form-data.
     *
     * Takes the bytes rather than a stream: a request body has to survive
     * being sent twice, because a 401 halfway through a long ride is replayed
     * after a token refresh, and a consumed stream would replay as an empty
     * file.
     */
    suspend fun postFile(
        path: String,
        fieldName: String,
        filename: String,
        contentType: String,
        bytes: ByteArray,
    ): String {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                fieldName,
                filename,
                bytes.toRequestBody(contentType.toMediaType()),
            )
            .build()
        return send("POST", path, body)
    }

    private fun jsonBody(body: JSONObject?): RequestBody =
        (body?.toString() ?: "{}").toRequestBody(JSON_MEDIA_TYPE)

    private suspend fun send(method: String, path: String, body: RequestBody?): String =
        withContext(Dispatchers.IO) {
            val first = execute(method, path, body, tokenStore.accessToken())
            if (first.code != 401) {
                return@withContext first.bodyOrThrow()
            }
            first.close()

            // 401: one refresh, then one replay. If the replay also 401s the
            // session is genuinely finished, so don't loop.
            val refreshed = refreshAccessToken()
            val second = execute(method, path, body, refreshed)
            if (second.code == 401) {
                second.close()
                tokenStore.clear()
                throw SessionExpiredException()
            }
            second.bodyOrThrow()
        }

    private fun execute(
        method: String,
        path: String,
        body: RequestBody?,
        accessToken: String?,
    ): Outcome {
        val builder = Request.Builder().url("$baseUrl$path")
        if (accessToken != null) {
            builder.header("Authorization", "Bearer $accessToken")
        }
        builder.applyMethod(method, body ?: jsonBody(null))

        val response = try {
            client.newCall(builder.build()).execute()
        } catch (e: IOException) {
            // No status: there was no response to take one from, and that is
            // exactly what tells "the server said no" from "there was no
            // server". Callers rely on the difference.
            throw ApiException("Can't reach the server. Check your connection.")
        }
        return Outcome(response)
    }

    /**
     * Swaps the refresh token for a fresh pair and stores it. Returns the new
     * access token.
     */
    private suspend fun refreshAccessToken(): String = refreshMutex.withLock {
        val refreshToken = tokenStore.refreshToken() ?: run {
            tokenStore.clear()
            throw SessionExpiredException()
        }

        val request = Request.Builder()
            .url("$baseUrl/auth/refresh")
            .post(JSONObject().put("refreshToken", refreshToken).toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw ApiException("Can't reach the server. Check your connection.")
        }

        response.use {
            if (!it.isSuccessful) {
                tokenStore.clear()
                throw SessionExpiredException()
            }
            val json = try {
                JSONObject(it.body.string())
            } catch (e: Exception) {
                tokenStore.clear()
                throw SessionExpiredException()
            }
            val access = json.optString("accessToken").takeIf { s -> s.isNotEmpty() }
            val refresh = json.optString("refreshToken").takeIf { s -> s.isNotEmpty() }
            if (access == null || refresh == null) {
                tokenStore.clear()
                throw SessionExpiredException()
            }
            tokenStore.saveTokens(access, refresh)
            access
        }
    }

    private class Outcome(private val response: okhttp3.Response) {
        val code: Int get() = response.code

        fun close() = response.close()

        /** The body on success; a message the user can read on anything else. */
        fun bodyOrThrow(): String = response.use {
            val payload = it.body.string()
            if (it.isSuccessful) return payload

            val message = serverMessage(payload) ?: genericMessage(it.code)
            throw activeTripRefusal(payload, message) ?: ApiException(message, it.code)
        }

        /**
         * The one refusal the app re-words, or null for every other failure.
         *
         * Read defensively: a body missing the trip it named would leave the
         * screens with a blank to interpolate, and a plain [ApiException]
         * carrying the server's own sentence is a better answer than "You're
         * already on \"\"".
         */
        private fun activeTripRefusal(payload: String, message: String): ApiException? = try {
            val json = JSONObject(payload)
            val trip = json.optJSONObject("active_trip")
            val name = trip?.optString("name").orEmpty()
            if (json.optString("code") != ActiveTripException.CODE ||
                trip == null ||
                name.isEmpty()
            ) {
                null
            } else {
                ActiveTripException(
                    message = message,
                    tripId = trip.optLong("id").takeIf { it > 0L },
                    tripName = name,
                )
            }
        } catch (e: Exception) {
            null
        }

        /**
         * The backend answers errors as `{"error": "..."}`, and those
         * messages are written to be read — "trip has ended", "only the trip
         * owner can do this". Preferring them keeps the app's wording in step
         * with the server's rules instead of guessing at them here.
         */
        private fun serverMessage(payload: String): String? = try {
            JSONObject(payload).optString("error").takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }

        private fun genericMessage(code: Int): String = when (code) {
            403 -> "You don't have permission to do that."
            404 -> "That's no longer there."
            409 -> "That can't be done right now."
            429 -> "Slow down a moment, then try again."
            in 500..599 -> "The server is having trouble. Try again shortly."
            else -> "Something went wrong (HTTP $code)."
        }
    }

    private companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
