package app.ptrip.tracktrip.data

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.channelFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * The live feed of everybody's positions on one trip.
 *
 * ## What it is for
 *
 * The phone reports its own position every 45 seconds over REST, and that is
 * unchanged. What used to lag was the *other* direction: a rider watching the
 * map found out about a friend on their own next poll, so the delay a rider
 * actually experienced was the reporting cadence plus most of a polling one.
 * This removes the second half — a fix stored on the server arrives here in
 * the same moment.
 *
 * ## What happens when it breaks
 *
 * Nothing that matters. The socket carries a copy of data that is already
 * stored and already readable over REST; it adds nothing to the app's state,
 * so losing it cannot lose any. [TripMapViewModel] keeps polling throughout —
 * slowly while this is connected, at its normal rate when it is not — and this
 * reconnects underneath with a widening backoff. A rider who rides through a
 * valley sees their map go from instant to a-poll-behind and back, and is
 * never told about it because there is nothing they need to do.
 *
 * That is a property of the design rather than a fallback bolted on, and it is
 * why this class has no error surface: there is no failure here worth putting
 * in front of somebody on a motorcycle.
 */
class PositionSocket(
    private val baseUrl: String,
    private val tokenStore: TokenStore,
    private val client: OkHttpClient = defaultSocketClient(),
) {

    /**
     * Positions for [tripId], for as long as the flow is collected.
     *
     * The flow never completes on its own and never throws: a dropped
     * connection is a pause, not an end. Collect it from a screen's scope and
     * it lives exactly as long as the screen does.
     *
     * [onConnectedChange] reports whether the socket is currently carrying
     * updates, which is what the poll cadence is chosen from. It is a callback
     * rather than a second flow because the two facts belong to one collection
     * and would otherwise have to be kept in step by the caller.
     */
    fun positions(
        tripId: Long,
        onConnectedChange: (Boolean) -> Unit = {},
    ): Flow<MemberPosition> = channelFlow {
        var attempt = 0

        while (true) {
            val token = tokenStore.accessToken()
            if (token == null) {
                // Nothing to connect with. The screen is still polling, and a
                // sign-in will put a token here; waiting the maximum backoff
                // costs nothing and avoids a spin.
                delay(LiveCadence.RETRY_MAX_MS)
                continue
            }

            attempt += 1
            val lived = connectOnce(
                tripId = tripId,
                token = token,
                onConnected = {
                    // A connection that reached `ready` is a working one, so
                    // the next drop starts its backoff from the beginning
                    // rather than from wherever the last outage left it.
                    attempt = 0
                    onConnectedChange(true)
                },
                onPosition = { trySend(it) },
            )
            onConnectedChange(false)

            // A connection that never became usable — a rejected token, a
            // server that is down — still counts towards the backoff. One that
            // worked and then dropped starts again at the shortest wait.
            delay(LiveCadence.retryDelayMs(if (lived) 1 else attempt))
        }
    }

    /**
     * Opens one connection and suspends until it closes.
     *
     * Returns whether it ever became usable, which is what decides where the
     * backoff resumes from.
     */
    private suspend fun connectOnce(
        tripId: Long,
        token: String,
        onConnected: () -> Unit,
        onPosition: (MemberPosition) -> Unit,
    ): Boolean {
        var everReady = false

        // callbackFlow rather than suspendCancellableCoroutine so that
        // cancelling the caller closes the socket through awaitClose, with no
        // way to leak one by forgetting a branch.
        val connection = callbackFlow<Unit> {
            val request = Request.Builder()
                .url(socketUrl(baseUrl))
                // The header, not a query parameter: a token in a URL reaches
                // access logs. The server accepts both, because a browser
                // cannot set headers on a WebSocket — but this is not a
                // browser.
                .header("Authorization", "Bearer $token")
                .build()

            val socket = client.newWebSocket(
                request,
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send(
                            JSONObject()
                                .put("type", "subscribe")
                                .put("trip_id", tripId)
                                .toString()
                        )
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        when (val event = parsePositionEvent(text)) {
                            is PositionEvent.Ready -> {
                                everReady = true
                                onConnected()
                            }
                            is PositionEvent.Position -> {
                                if (event.tripId == tripId) onPosition(event.member)
                            }
                            // Everything else — a subscription acknowledged, a
                            // refusal, a message from a newer server — is not
                            // this screen's business. The refusal in
                            // particular: the poll is still running and still
                            // authorised the ordinary way, so a socket that
                            // cannot subscribe is a slower map, not a broken
                            // one.
                            else -> Unit
                        }
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        close()
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        close()
                    }
                }
            )

            awaitClose { socket.cancel() }
        }

        connection.collect { }
        return everReady
    }

    companion object {

        /** `https://host/api` becomes `wss://host/api/ws`. */
        internal fun socketUrl(baseUrl: String): String {
            val trimmed = baseUrl.trimEnd('/')
            val scheme = when {
                trimmed.startsWith("https://") -> "wss://"
                trimmed.startsWith("http://") -> "ws://"
                // Already a socket URL, or something this app did not write.
                else -> ""
            }
            val rest = trimmed
                .removePrefix("https://")
                .removePrefix("http://")
            return "$scheme$rest/ws"
        }

        /**
         * OkHttp, configured for a connection that is meant to stay open.
         *
         * `readTimeout = 0` because a socket with nothing to say is not a
         * stalled one — the default would tear down a working connection every
         * time a group stopped for lunch. `pingInterval` replaces it as the
         * liveness check: it is what tells this end that a phone has ridden
         * into a tunnel, rather than waiting for TCP to work it out, which can
         * take many minutes.
         */
        fun defaultSocketClient(): OkHttpClient = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }
}
