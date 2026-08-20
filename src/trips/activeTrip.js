/**
 * One active trip per rider.
 *
 * ## Why this is a rule at all
 *
 * The Android app has always assumed it: the phone holds a single
 * `ActiveSharing` and the location service reports to the trip it was started
 * for, so "which trip am I sharing to?" has exactly one answer. Settings used
 * to draw a switch per running trip, and collapsing that to one switch made
 * the assumption load-bearing.
 *
 * Nothing enforced it. `trips` had no constraint, `POST /trips` no guard,
 * accepting an invite and redeeming a join code neither, and
 * `sharing_sessions` is keyed `(trip_id, user_id)` — so the server would
 * happily hold two live sessions for one rider while the phone could only feed
 * one of them. A rider in that state has a trip on their list that can never
 * receive a position, and no way to tell which.
 *
 * So it is enforced here, at the three doors into a trip, and again at the
 * database by migration 0013 — see there for why the backstop is a trigger
 * rather than a unique index.
 *
 * ## What counts as being on a trip
 *
 * A **live** row in `trip_members` — `left_at IS NULL` — whatever its `role`.
 * Leaving is a soft delete, so the row of a rider who got off survives to be
 * counted as history; counting it here would leave them locked out of ever
 * starting another trip, which is the trap the leave button exists to open.
 *
 * Owner and member both, because both are *riding*: it is the same table and the same rows that
 * `GET /trips/:id/suggested-invitees` counts as having "ridden together", and
 * the same rows `GET /trips` turns into a rider's list. A rule that counted
 * only the trips you own would let one invitation put you on three rides at
 * once.
 *
 * `users.role` is a different thing entirely — see `auth/roles.js`. A super
 * user browsing every trip through `GET /trips?all=true` is not a member of
 * them, holds no `trip_members` row, and is neither blocked by this nor
 * counted by it.
 *
 * ## What counts as active
 *
 * `trips.status = 'active'`, which is the app's own definition: `Trip.isActive`
 * is `status == "active"` and `hasEnded` is its negation. There is no third
 * state and no way back from `ended` — `POST /trips/:id/end` is the only
 * writer of the column, and it only ever sets `ended`.
 */

/**
 * The machine-readable half of the refusal.
 *
 * The message is written to be read — the Android client shows a server's
 * `error` string verbatim — but that leaves it English on a Thai phone, and
 * this refusal is one riders will meet often enough to be worth translating.
 * So the body carries a code the client can switch on, with the sentence as
 * the fallback for a client that has not learned it.
 */
export const ACTIVE_TRIP_EXISTS = 'active_trip_exists';

/** What migration 0013's triggers raise, so a lost race can be told apart. */
export const ONE_ACTIVE_TRIP_ABORT = 'one active trip per rider';

/**
 * The active trip this rider is already on, or undefined.
 *
 * @param excludeTripId a trip to ignore — the one being joined. Redeeming a
 *   join code for a trip you are already on is a success rather than a
 *   conflict, and without this it would refuse itself.
 */
export function activeTripFor(db, userId, { excludeTripId = null } = {}) {
  return db
    .prepare(
      `SELECT trips.id, trips.name, trip_members.role
         FROM trip_members
         JOIN trips ON trips.id = trip_members.trip_id
        WHERE trip_members.user_id = ?
          AND trip_members.left_at IS NULL
          AND trips.status = 'active'
          AND (? IS NULL OR trips.id != ?)
        ORDER BY trips.created_at DESC, trips.id DESC
        LIMIT 1`
    )
    .get(userId, excludeTripId, excludeTripId);
}

/**
 * The 409 body for a rider who is already out on a trip.
 *
 * Names the trip, because "you already have an active trip" sends somebody to
 * a list of forty to work out which — and the answer is usually a ride they
 * forgot to end months ago rather than anything they remember starting.
 */
export function activeTripConflict(trip) {
  // No trip only happens on a lost race whose winner has since been ended —
  // vanishingly unlikely, and not worth naming the wrong ride over. Naming the
  // trip the rider was *trying* to start would be worse than naming none.
  if (!trip) {
    return {
      error: "You're already on an active trip. End it before starting or joining another.",
      code: ACTIVE_TRIP_EXISTS,
      active_trip: null,
    };
  }

  return {
    error:
      `You're already on an active trip, "${trip.name}". ` +
      'End it before starting or joining another.',
    code: ACTIVE_TRIP_EXISTS,
    active_trip: { id: trip.id, name: trip.name, role: trip.role },
  };
}

/**
 * Whether an error thrown out of a write is migration 0013's trigger firing.
 *
 * The guards below catch this case first, so the trigger only ever fires on a
 * race they cannot see — two requests for the same rider landing in the gap
 * between the check and the insert. That is a conflict, not a server fault, so
 * it must not surface as a 500.
 */
export function isOneActiveTripAbort(error) {
  return typeof error?.message === 'string' && error.message.includes(ONE_ACTIVE_TRIP_ABORT);
}
