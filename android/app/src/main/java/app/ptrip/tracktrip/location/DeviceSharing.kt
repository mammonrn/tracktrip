package app.ptrip.tracktrip.location

import app.ptrip.tracktrip.data.Trip

/**
 * The one switch that answers "does this phone share where it is?"
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
 * ## Why it is one switch and not two
 *
 * The first fix for that put a device switch above the per-trip rows and kept
 * both: the phone's answer, then which trip it applied to. That was one
 * question too many. **A rider is on one ride at a time.** The phone is on one
 * ride at a time by construction — [SharingState] holds a single
 * [ActiveSharing] and the service reports to the trip it was started for — so
 * a row asking *which* trip was asking a rider to choose between things they
 * cannot be doing at once, and it re-introduced the confusion it was meant to
 * settle: two switches that can disagree about the same fact.
 *
 * So: one switch. On is "share where I am", off is "send nothing", and the
 * trip is whichever one is running. What used to be two rows is now this file
 * and a line of text.
 *
 * ## What ON means when nothing is being sent
 *
 * Consent, not transmission. A phone can be willing to share and have nowhere
 * to send — no trip running, or the only one just ended — and that is an
 * ordinary state rather than an error, so the switch sits in it happily and
 * the line underneath says which of the two it is ([Status]).
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

    /** What the switch is doing right now, in the terms the line explains it. */
    enum class Status {
        /** Off. This phone sends nothing, whatever is running. */
        OFF,

        /** On, and there is nowhere to send: no trip is running. */
        ON_IDLE,

        /** On, and a fix is going out to the trip that is running. */
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

        /** "Start sending, on the ride I am on" — see [onSwitched]. */
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
     * **On** starts sharing on the running trip, if there is one. With none
     * there is nothing to start and the choice is simply kept — which is the
     * whole of the original report, and the reason this cannot consult the
     * trip list to decide whether the rider is *allowed* to press.
     *
     * [running] is expected to hold at most one trip, because a rider is on
     * one ride at a time. Nothing in the schema or the API enforces that
     * (`trips` has no such constraint, `POST /trips` no such guard, and
     * `sharing_sessions` is keyed per trip *and* rider) — so rather than
     * assert it and crash a rider who has somehow ended up on two, this takes
     * the **first**, which `GET /trips` orders `created_at DESC, id DESC`:
     * the most recently started of them, which is the one somebody with two
     * open trips is riding. Deliberately not a picker. The old picker is what
     * this change removed.
     *
     * @param sharingTripId the trip the service is reporting to, from
     *   [SharingState], or null when it is not running.
     * @param running the trips that could be shared on: active, and this
     *   rider's.
     */
    fun onSwitched(on: Boolean, sharingTripId: Long?, running: List<Trip>): Effect = when {
        !on -> sharingTripId?.let { Effect.Stop(it) } ?: Effect.Remember
        sharingTripId != null -> Effect.Remember
        else -> running.firstOrNull()?.let { Effect.Start(it) } ?: Effect.Remember
    }
}
