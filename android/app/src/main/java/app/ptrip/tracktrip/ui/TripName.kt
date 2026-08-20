package app.ptrip.tracktrip.ui

/**
 * What a trip may be called, on the phone's side of the wire.
 *
 * A copy of the server's rule, and that is the point of it being here rather
 * than spelled out inside a screen: the same rule is now asked twice — once by
 * the form that creates a trip and once by the form that renames one — and two
 * hand-written copies of "1 to 60 characters after trimming" is how a title
 * comes to be accepted by one screen and refused by the other.
 *
 * The server still decides. This is what stops the app from sending a request
 * it already knows the answer to, and what greys out the Save button while
 * there is nothing worth saving; it is not a substitute for the 400.
 *
 * Free of Compose so it can be tested on a laptop.
 */
object TripNameRules {

    /** Matches the backend's 1–60 characters after trimming. */
    const val MAX_LENGTH = 60

    /** Whether a typed name is something the server would accept. */
    fun isValid(typed: String): Boolean {
        val trimmed = typed.trim()
        return trimmed.isNotEmpty() && trimmed.length <= MAX_LENGTH
    }

    /**
     * Whether saving this typed name would change anything.
     *
     * Compared trimmed, because the difference between `"Pai loop"` and
     * `"Pai loop "` is a difference the server throws away — offering to save
     * it would be offering a request that comes back with the name the rider
     * already had.
     */
    fun isChangedFrom(current: String, typed: String): Boolean =
        typed.trim() != current.trim()

    /** Whether the Save button on a rename form should be pressable. */
    fun canSave(current: String, typed: String, saving: Boolean): Boolean =
        !saving && isValid(typed) && isChangedFrom(current, typed)
}
