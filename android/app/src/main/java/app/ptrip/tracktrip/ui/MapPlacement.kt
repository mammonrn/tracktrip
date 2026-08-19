package app.ptrip.tracktrip.ui

/**
 * What a rider can put on the map by pressing and holding on it.
 *
 * The trip already has somewhere to store all three — `trips.origin_*` and
 * `trips.destination_*` from migration 0009, and `trip_waypoints` from 0003 —
 * and the API has accepted them since. This is the part that was missing: a
 * way to say so from a phone.
 */
enum class MapPlacement {
    /** Where the ride starts. One per trip, the owner's to set. */
    ORIGIN,

    /** Where it is going. Also one per trip, also the owner's. */
    DESTINATION,

    /**
     * A stop dropped mid-ride — a viewpoint, a fuel station, the place the
     * group actually turned round. Anyone on the trip can drop one, and it is
     * always a `live` waypoint: a point added because somebody stopped there
     * has no place in a planned route's ordering.
     */
    WAYPOINT,
}

/**
 * Who may place what, and what has to be typed in first.
 *
 * The server decides all of this too — `requireTripOwner` on `PATCH /trips/:id`,
 * `requireActiveTrip` on posting a waypoint — and it stays the authority. This
 * exists so the screen does not offer a rider a button that will come back
 * as a 403, which reads as a broken app rather than as a rule.
 */
object MapPlacementRules {

    /** Waypoint names, as `validateWaypointInput` in the API bounds them. */
    const val NAME_MAX_LENGTH = 60

    /** Endpoint labels, as `validateTripEndpoint` bounds them. */
    const val LABEL_MAX_LENGTH = 120

    /**
     * What this rider may place on this trip, in the order to offer it.
     *
     * The two ends stay available on a trip that has ended, on purpose:
     * `PATCH /trips/:id` allows them there, because filling in where a ride
     * actually finished is something people do afterwards. Waypoints do not —
     * writes to a finished trip are refused, and a stop nobody can still be
     * standing at is not information anyone needs.
     */
    fun allowed(isOwner: Boolean, isTripActive: Boolean): List<MapPlacement> = buildList {
        if (isOwner) {
            add(MapPlacement.ORIGIN)
            add(MapPlacement.DESTINATION)
        }
        if (isTripActive) {
            add(MapPlacement.WAYPOINT)
        }
    }

    /**
     * Whether a name has to be typed before the point can be saved.
     *
     * A waypoint's is required because the server requires it: a list of
     * unnamed stops is a list of identical rows. An end of the trip needs no
     * label — a rider pointing at where they are heading may have no name for
     * it, and the map draws the flag either way.
     */
    fun nameRequired(placement: MapPlacement): Boolean = placement == MapPlacement.WAYPOINT

    /** The longest name this placement's endpoint will accept. */
    fun maxNameLength(placement: MapPlacement): Int =
        if (placement == MapPlacement.WAYPOINT) NAME_MAX_LENGTH else LABEL_MAX_LENGTH

    /**
     * Whether what has been typed can be sent.
     *
     * Checked here rather than left to the server so a rider learns their
     * name is too long while they are still typing it, instead of by having
     * the save fail after the dialog closes.
     */
    fun isNameValid(placement: MapPlacement, name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.length > maxNameLength(placement)) return false
        return !(nameRequired(placement) && trimmed.isEmpty())
    }

    /**
     * Whether this rider may remove a point somebody dropped.
     *
     * Mirrors the server's rule exactly — trip owner, or the member who added
     * it. [addedBy] is null on a point from a build that did not report it,
     * in which case only the owner is offered the control: guessing "probably
     * mine" would put a button in front of a rider that answers 403.
     */
    fun canRemoveWaypoint(isOwner: Boolean, addedBy: Long?, currentUserId: Long?): Boolean {
        if (isOwner) return true
        if (addedBy == null || currentUserId == null) return false
        return addedBy == currentUserId
    }
}
