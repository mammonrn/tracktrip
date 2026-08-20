/**
 * Who is on a trip *now*.
 *
 * ## Why this is a function and not a query written twice
 *
 * `trip_members` used to answer that on its own: a row meant a member, and
 * every read of the table could take the row at face value. Leaving a trip is
 * a soft delete now — the row stays with `left_at` stamped, so that "have we
 * ridden together?" can still be answered yes long after somebody got off —
 * and the moment that became true, every one of those reads split into two
 * different questions that had been the same question:
 *
 * - **Are they on this trip now?** Access, rosters, the map, the one-active-
 *   trip rule. `left_at IS NULL`, strictly.
 * - **Were they ever?** "Ridden with before", and nothing else. Every row,
 *   `left_at` ignored.
 *
 * Getting the first one wrong is not a display bug. A rider who left a trip
 * and still passed the membership check would keep reading its live positions
 * and its route, and could go on reporting their own — which is exactly the
 * access the leave button exists to end. So the clause lives here, in the
 * function both gates call, rather than in each of them.
 *
 * The second question is asked in one place and says so out loud: see the
 * `mine`/`theirs` join in `GET /trips/:id/suggested-invitees`.
 *
 * ## The SQL fragment
 *
 * [CURRENT_MEMBER] is for the queries that join `trip_members` to something
 * else and cannot use [currentMembership] — rosters, the trip list, the
 * leaderboard. Written down once so a reader can grep for it and find every
 * place that had to think about this.
 */

/** The clause that separates "on the trip" from "was on the trip". */
export const CURRENT_MEMBER = 'trip_members.left_at IS NULL';

/**
 * This user's live membership of this trip, or undefined.
 *
 * Undefined for somebody who has left, exactly as for somebody who was never
 * on it: from the trip's point of view those are the same answer, and the
 * difference belongs only to the history.
 */
export function currentMembership(db, tripId, userId) {
  return db
    .prepare(
      `SELECT * FROM trip_members
        WHERE trip_id = ? AND user_id = ? AND left_at IS NULL`
    )
    .get(tripId, userId);
}

export default currentMembership;
