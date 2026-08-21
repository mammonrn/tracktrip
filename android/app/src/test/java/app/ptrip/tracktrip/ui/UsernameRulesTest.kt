package app.ptrip.tracktrip.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone's copy of the server's username rule.
 *
 * Two jobs, and the second is the one worth a test file. It has to accept what
 * the backend accepts — otherwise the app refuses a name the server would have
 * taken, which is the worst kind of client validation. And it has to keep
 * refusing the homoglyphs, because those were being blocked by the old rule
 * *by accident*: `[a-zA-Z0-9_.]` had no opinion about Cyrillic or fullwidth
 * Latin, it simply could not match them, and opening the rule up to Thai is
 * exactly the moment that accident could be lost.
 *
 * The cases below are the same ones `test/profile.test.js` runs against the
 * server. Two copies of a rule drifting apart is the failure this pairing
 * exists to make loud.
 */
class UsernameRulesTest {

    private fun accepts(vararg names: String) = names.forEach {
        assertTrue("expected \"$it\" to be accepted", UsernameRules.isValid(it))
    }

    private fun refuses(vararg names: String) = names.forEach {
        assertFalse("expected \"$it\" to be refused", UsernameRules.isValid(it))
    }

    @Test
    fun `Thai is accepted, which is the whole point of the change`() {
        accepts("กรงกราง", "ก็อง", "น้ำ", "poom_ไทย", "ไทย.rider", "สำคัญ")
    }

    @Test
    fun `every ASCII username that worked before still works`() {
        // The column has rows in it already and every one of them is ASCII, so
        // this is the backward-compatibility half.
        accepts("abc", "poom", "poom_rides", "poom.rides", "Poom99", "x".repeat(20))
    }

    @Test
    fun `blank is valid, because blank is how a rider gives their username up`() {
        // The server reads an empty string as "take it off me" rather than as
        // a mistake, so a form that greyed Save out here would trap a rider
        // with a username they cannot remove.
        accepts("", "   ")
    }

    @Test
    fun `homoglyph scripts stay refused`() {
        // Every one of these is what a script property would have let through:
        // U+FF30 and U+0131 are Latin, U+0E4F and U+0E51 are Thai. Script says
        // which writing system a character belongs to, not what it looks like.
        refuses(
            "Ｐｏｏｍ", // fullwidth Latin
            "ıdent", // U+0131 dotless i
            "pооm", // Cyrillic о
            "Ροom", // Greek Rho
            "poom๑", // Thai digit — said aloud exactly like poom1
            "poom๏", // Thai punctuation
        )
    }

    @Test
    fun `the shape rules the ASCII version had are unchanged`() {
        refuses(
            "ab", // too short
            "x".repeat(21), // too long
            "has space",
            "has@symbol",
            "poom-rides",
            ".leading",
            "trailing.",
            "do..uble",
            "poom\uD83D\uDE00", // emoji
            "' OR 1=1 --",
            "<script>x</script>",
            "poom\u200Bx", // zero-width space
        )
    }

    @Test
    fun `a combining mark cannot open a username, but it can close one`() {
        // Opening with one renders as a dotted circle — the font drawing "this
        // mark has nothing to attach to". Ending with one is ordinary Thai:
        // ก็ is a word.
        refuses("\u0E48กอง")
        accepts("กอ\u0E48", "ก็อง")
    }

    @Test
    fun `length is counted after trimming, like the server counts it`() {
        accepts("  abc  ")
        refuses("  ab  ")
    }
}
