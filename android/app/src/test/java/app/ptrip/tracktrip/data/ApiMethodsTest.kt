package app.ptrip.tracktrip.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * That every verb the client offers actually reaches the wire.
 *
 * ## The bug this was written for
 *
 * `ApiClient.delete` has existed since shared places did, and the `when` that
 * turns a method name into a request had no `"DELETE"` branch — so every
 * removal fell through to `throw IllegalArgumentException`. Deleting a shared
 * place, a private place or a waypoint could never have worked.
 *
 * It went unnoticed because of what it threw. An `IllegalArgumentException` is
 * not an `ApiException`, so the `catch (e: ApiException)` in every view model
 * let it past; the failure left the coroutine instead of landing on the error
 * line, and nothing on screen said the removal had not happened. And no test
 * could see it, because the mapping was buried in a private function that
 * needed a `Context` and a socket to reach.
 *
 * So the mapping is [applyMethod] now, and these are what stop the list of
 * verbs and the `when` parting company again.
 */
class ApiMethodsTest {

    private val body = "{}".toRequestBody("application/json; charset=utf-8".toMediaType())

    private fun built(method: String) =
        Request.Builder().url("https://api.example.com/x").applyMethod(method, body).build()

    @Test
    fun `every verb the client offers is one the builder can send`() {
        // The four ApiClient exposes: get, post, patch, delete. If a fifth is
        // ever added and not wired here, this is the line that fails.
        for (method in listOf("GET", "POST", "PATCH", "DELETE")) {
            assertEquals(method, built(method).method)
        }
    }

    @Test
    fun `DELETE goes out without a body`() {
        // The endpoints behind it answer 204, and OkHttp's delete() defaults to
        // no body — which is what a proxy in front of the API expects for one.
        assertNull(built("DELETE").body)
    }

    @Test
    fun `GET goes out without a body, and the writes carry theirs`() {
        assertNull(built("GET").body)
        assertNotNull(built("POST").body)
        assertNotNull(built("PATCH").body)
    }

    @Test
    fun `an unknown verb is still refused loudly`() {
        // The throw was only wrong for DELETE, which belonged in the list. A
        // verb nobody wired up is a programming mistake and should not become
        // a silent GET.
        assertThrows(IllegalArgumentException::class.java) { built("TRACE") }
    }
}
