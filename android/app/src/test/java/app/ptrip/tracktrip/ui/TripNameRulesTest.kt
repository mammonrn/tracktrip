package app.ptrip.tracktrip.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the rename form will and will not offer to save.
 *
 * The rule is the server's — 1 to 60 characters after trimming — and this is
 * the copy of it the phone uses to decide whether the Save button is
 * pressable. Two forms ask it now, create and rename, which is the whole
 * reason it stopped living inside a screen: a limit written out twice is a
 * limit that eventually disagrees with itself, and the rider who finds out is
 * the one whose title was accepted when they made the trip and refused when
 * they edited it.
 */
class TripNameRulesTest {

    @Test
    fun `the limit is the server's`() {
        assertTrue(TripNameRules.isValid("x".repeat(TripNameRules.MAX_LENGTH)))
        assertFalse(TripNameRules.isValid("x".repeat(TripNameRules.MAX_LENGTH + 1)))
    }

    @Test
    fun `a name made only of spaces is no name`() {
        // The server trims before it measures, so a field full of spaces is
        // an empty name that looks full. Refused here rather than sent, or
        // the rider watches a save fail for a field that does not look empty.
        assertFalse(TripNameRules.isValid(""))
        assertFalse(TripNameRules.isValid("   "))
        assertFalse(TripNameRules.isValid("\n\t "))
        assertTrue(TripNameRules.isValid("  Pai loop  "))
    }

    @Test
    fun `sixty characters of padding still fits`() {
        // Trimmed *then* measured, in that order. Measuring first would refuse
        // a name that the server would accept.
        assertTrue(TripNameRules.isValid("   " + "x".repeat(TripNameRules.MAX_LENGTH) + "   "))
    }

    @Test
    fun `whitespace alone is not a change`() {
        // The server throws the padding away, so saving this would spend a
        // request to be handed back the name the rider already had.
        assertFalse(TripNameRules.isChangedFrom("Pai loop", "Pai loop"))
        assertFalse(TripNameRules.isChangedFrom("Pai loop", "  Pai loop  "))
        assertTrue(TripNameRules.isChangedFrom("Pai loop", "Pai loop 2"))
        // Case is a change: the server stores what it is given.
        assertTrue(TripNameRules.isChangedFrom("Pai loop", "PAI LOOP"))
    }

    @Test
    fun `save is offered only for a change worth sending`() {
        assertTrue(TripNameRules.canSave(current = "Trip 3", typed = "Pai loop", saving = false))

        // Nothing typed, nothing changed, or already in flight: all three are
        // requests whose answer is known, and a pressable button that does
        // nothing reads as a save that silently failed.
        assertFalse(TripNameRules.canSave(current = "Trip 3", typed = "", saving = false))
        assertFalse(TripNameRules.canSave(current = "Trip 3", typed = "   ", saving = false))
        assertFalse(TripNameRules.canSave(current = "Trip 3", typed = "Trip 3", saving = false))
        assertFalse(TripNameRules.canSave(current = "Trip 3", typed = "Pai loop", saving = true))
    }
}
