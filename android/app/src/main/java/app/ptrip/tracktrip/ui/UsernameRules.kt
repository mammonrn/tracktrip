package app.ptrip.tracktrip.ui

/**
 * What a username may contain, on the phone's side of the wire.
 *
 * A copy of the server's rule, for the reason [TripNameRules] is a copy of
 * its own: without it the only way to find out that `กรงกราง` is or is not
 * allowed was to type it, press Save, wait for a round trip and read an
 * English sentence from the backend. That is a slow way to learn a rule, and
 * on a form with five fields it is not even obvious which one the sentence is
 * about.
 *
 * The server still decides — see `src/users/username.js`, which this mirrors
 * and which holds the reasoning. This only saves the round trip.
 *
 * ## Kept deliberately in step, and deliberately not clever
 *
 * The two ranges below are the same two the backend spells out, and for the
 * same reason it spells them out rather than reaching for `\p{Script=Latin}`
 * and `\p{Script=Thai}`: the script properties are broader than they look.
 * `Ｐ` (U+FF30) is Latin by script, `ı` (U+0131) is Latin by script, and `๏`
 * (U+0E4F) is Thai by script — a fullwidth impostor, a dotless i and a
 * paragraph mark. Script says which writing system a character belongs to,
 * not whether it can be mistaken for another character.
 *
 * What this copy does **not** do is normalise. Deciding whether two usernames
 * collide is the server's job and needs the whole table to answer; the phone
 * only ever asks "could this be a username at all". A rider who types a name
 * somebody already holds still learns that from the 409, which arrives as the
 * app's own red bar.
 *
 * Free of Compose and of Android, so it can be tested on a laptop.
 */
object UsernameRules {

    const val MIN_LENGTH = 3
    const val MAX_LENGTH = 20

    /** ก through ฯ, the spacing vowels ะ า ำ, and the leading vowels เ แ โ ใ ไ ๅ ๆ. */
    private const val THAI_BASE = "\u0E01-\u0E30\u0E32\u0E33\u0E40-\u0E46"

    /** The marks that hang off a base: ั, the upper and lower vowels, the tones. */
    private const val THAI_MARK = "\u0E31\u0E34-\u0E3A\u0E47-\u0E4E"

    /**
     * The shape. A username opens with something a mark can hang off, never
     * with a dot or a combining mark; it may end with a mark — `ก็` is a Thai
     * word and ends in one — but never with a dot; and two dots never touch.
     */
    private val PATTERN = Regex(
        "^[a-zA-Z0-9_$THAI_BASE]" +
            "(?:[a-zA-Z0-9_$THAI_BASE$THAI_MARK]|\\.(?!\\.))*" +
            "[a-zA-Z0-9_$THAI_BASE$THAI_MARK]$"
    )

    /**
     * Whether a typed username is something the server would accept.
     *
     * Blank is valid: it is how a rider clears the field, and the server reads
     * an empty string as "take my username off me" rather than as a mistake.
     */
    fun isValid(typed: String): Boolean {
        val trimmed = typed.trim()
        if (trimmed.isEmpty()) return true
        return trimmed.length in MIN_LENGTH..MAX_LENGTH && PATTERN.matches(trimmed)
    }
}
