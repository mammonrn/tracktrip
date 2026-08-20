package app.ptrip.tracktrip.location

import app.ptrip.tracktrip.data.Trip

/**
 * The one switch that answers "does this phone share where it is?", kept apart
 * from "which trip is running".
 *
 * ## The bug this exists to end
 *
 * Settings had no such switch. What it had was one switch *per running trip* —
 * `Switch(checked = trip.id == sharingTripId)` over a list built from
 * `listTrips().filter { it.isActive }` — and every one of those is a statement
 * about a trip rather than about the phone. So:
 *
 * - A rider whose trip had **ended** went to Settings to turn sharing on and
 *   found nothing to press. The trip was filtered out of the list before it
 *   was drawn, and there was no other control on the screen. The report read
 *   "I can't turn sharing on"; the screen's answer was "there is no trip to
 *   turn it on *for*", which is not the question that was asked.
 * - A rider with **no trips at all** saw "No trips are running" where a
 *   privacy setting should be. Whether a phone may broadcast its position is
 *   not a fact about anybody's weekend plans.
 * - Turning it off was only ever reachable *through* a trip, so the one
 *   control that matters most — stop sending — was the one that could go
 *   missing exactly when a session had outlived the trip that started it.
 *
 * Two concerns had been folded into one row. They are separated here:
 *
 * | | what it means | where it lives |
 * |---|---|---|
 * | **this** | may this phone share at all | `AppSettings.shareLocationFromThisPhone`, a device preference |
 * | the trip rows | which trip a fix is reported to | the service, via [SharingState] |
 *
 * ## What ON means when nothing is being sent
 *
 * Consent, not transmission. A phone can be willing to share and have nowhere
 * to send — no trip running, or the only one just ended — and that is an
 * ordinary state rather than an error, so the switch sits in it happily and
 * the row underneath says which of the two it is ([Status]).
 *
 * The default is on, matching the backend, where a rider who has never touched
 * the controls is already `is_sharing`. Defaulting the new switch off would
 * have quietly stopped riders who upgraded mid-tour without ever asking them.
 *
 * Nothing here is Android — the same reason [BatteryExemption.destinations]
 * lives apart from the intent that launches it. The decisions are testable on
 * their own; the effects are carried out by [SharingController].
 */
object DeviceSharing {

    /** What the switch is doing right now, in the terms the row explains it. */
    enum class Status {
        /** Off. This phone sends nothing, whatever is running. */
        OFF,

        /** On, and there is nowhere to send: no running trip, or none picked. */
        ON_IDLE,

        /** On, and a fix is going out to a trip. */
        ON_SHARING,
    }

    fun status(on: Boolean, sharingTripId: Long?): Status = when {
        !on -> Status.OFF
        sharingTripId != null -> Status.ON_SHARING
        else -> Status.ON_IDLE
    }

    /**
     * What flipping the switch has to do to whatever is already live.
     *
     * The switch is not a label on top of the service; it commands it. The
     * cases are few and each one is a rider's sentence:
     */
    sealed interface Effect {

        /** Remember the choice and do nothing else. */
        data object Remember : Effect

        /** "Stop sending" — the kill switch half, and the one that must never fail. */
        data class Stop(val tripId: Long) : Effect

        /** "Start sending, on the obvious trip" — see [onSwitched]. */
        data class Start(val trip: Trip) : Effect
    }

    /**
     * The effect of the rider moving the switch to [on].
     *
     * **Off** stops whatever is live, and is otherwise nothing to do. It never
     * consults the trip list: a session that outlived its trip — the trip
     * ended, or was left — is exactly the case that used to have no way out,
     * and the id to stop comes from the service rather than from a list that
     * may no longer contain it.
     *
     * **On** starts sharing when, and only when, there is one obvious trip to
     * start on. One running trip is the ordinary case and the switch is then
     * the whole gesture, the way the per-trip toggle used to be. With none,
     * there is nothing to start and the choice is simply kept. With several,
     * *which* is a second question and the rows below ask it — guessing would
     * broadcast a rider's position to the wrong group, which is the one
     * mistake this feature cannot make.
     *
     * @param sharingTripId the trip the service is reporting to, from
     *   [SharingState], or null when it is not running.
     * @param running the trips that could be shared on: active, and this
     *   rider's.
     */
    fun onSwitched(on: Boolean, sharingTripId: Long?, running: List<Trip>): Effect = when {
        !on -> sharingTripId?.let { Effect.Stop(it) } ?: Effect.Remember
        sharingTripId != null -> Effect.Remember
        else -> running.singleOrNull()?.let { Effect.Start(it) } ?: Effect.Remember
    }

    /**
     * Whether sharing can start on [trip] at all.
     *
     * Both halves, in one place, because the trip screen and the settings rows
     * both have to ask and had been asking differently: the phone must be
     * willing, and the trip must be running — the server refuses a session on
     * an ended one, so offering it would be offering a 409.
     */
    fun canShareOn(trip: Trip, on: Boolean): Boolean = on && trip.isActive
}
