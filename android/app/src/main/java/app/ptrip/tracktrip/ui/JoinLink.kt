package app.ptrip.tracktrip.ui

/**
 * What goes inside the QR code.
 *
 * `tracktrip://join?code=ABCD2345` rather than the bare code or an https URL,
 * for two reasons:
 *
 * - A bare eight-character code is indistinguishable from any other QR code in
 *   the world. Scanning a receipt would look like a valid attempt to join
 *   something, and the app would have to guess.
 * - An https URL would promise a web page. There isn't one, and pointing at
 *   the API host would show whoever scanned it with a general-purpose scanner
 *   a JSON error instead of anything useful.
 *
 * A custom scheme says "this is for that app" and leaves the door open for a
 * real deep link later without changing what is printed on the code.
 *
 * The scanner still accepts a bare code, because a rider reading one aloud
 * over the phone is the fallback when a camera won't focus.
 */
private const val JOIN_SCHEME = "tracktrip"
private const val JOIN_HOST = "join"
private const val CODE_PARAM = "code"

/** The payload to encode for [code]. */
fun joinLinkFor(code: String): String = "$JOIN_SCHEME://$JOIN_HOST?$CODE_PARAM=$code"

/**
 * The web form of the same invite, for pasting into a chat.
 *
 * A QR code is scanned by this app, so the custom scheme is enough there. A
 * link sent to LINE or SMS is not: chat apps linkify `https://` and leave a
 * custom scheme as plain text, and the person receiving it may not have
 * Tracktrip installed at all. The manifest claims this path, so a phone that
 * does have the app opens it there.
 *
 * The domain does not serve this path yet — see the PR notes. Until it does,
 * the code in the message body is what a recipient acts on.
 */
fun joinWebLinkFor(code: String): String = "https://ptrip.app/join/$code"

/**
 * The join code inside a scanned string, or null if it isn't one of ours.
 *
 * Accepts the link form and a bare code. Everything else — a URL to somewhere
 * else, a Wi-Fi credential, a bus ticket — is not an error to report, just a
 * QR code that isn't this.
 */
fun joinCodeFrom(scanned: String?): String? {
    val text = scanned?.trim().orEmpty()
    if (text.isEmpty()) return null

    val candidate = when {
        text.startsWith("$JOIN_SCHEME://", ignoreCase = true) ->
            text.substringAfter("$CODE_PARAM=", missingDelimiterValue = "").substringBefore('&')

        // The shared web form: .../join/CODE, with whatever query or fragment
        // a chat app decided to staple on the end.
        text.startsWith("http://", ignoreCase = true) ||
            text.startsWith("https://", ignoreCase = true) ->
            text.substringAfter("/$JOIN_HOST/", missingDelimiterValue = "")
                .substringBefore('?')
                .substringBefore('#')
                .substringBefore('/')

        else -> text
    }

    val cleaned = normalizeCode(candidate)
    return cleaned.takeIf { it.length == CODE_LENGTH && it.all { c -> c in CODE_ALPHABET } }
}

/**
 * The shape the server issues: eight characters from an alphabet with no
 * ambiguous pairs. Checked here only to avoid sending obvious rubbish — the
 * server is still the one that decides whether a code is real.
 */
private const val CODE_LENGTH = 8
private const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

/** Upper-cased, with the separators a person might have typed removed. */
private fun normalizeCode(value: String): String =
    value.uppercase().filterNot { it == '-' || it.isWhitespace() }
