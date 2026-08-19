import { isSuperuser } from './roles.js';

/**
 * Whether this user may read this trip, and what they are to it.
 *
 * The rule behind `requireTripMembership`, lifted out of Express so that the
 * WebSocket layer can ask the same question. A socket has no `req` and no
 * `next`, and the alternative — a second copy of "member, or super user" — is
 * exactly the kind of duplication that drifts: the copy that is not in front
 * of anybody's eyes is the one that keeps the old rule after the other
 * changes.
 *
 * Returns null when the trip does not exist or the user may not see it. On
 * success, `membership` is the `trip_members` row, or null for a super user
 * looking at a trip they do not belong to — the same distinction the
 * middleware draws, and for the same reason: reading a trip must never make
 * somebody a member of it.
 */
export function readableTrip(db, user, tripId) {
  if (!Number.isInteger(tripId) || tripId <= 0) {
    return null;
  }

  const trip = db.prepare('SELECT * FROM trips WHERE id = ?').get(tripId);
  if (!trip) {
    return null;
  }

  const membership = db
    .prepare('SELECT * FROM trip_members WHERE trip_id = ? AND user_id = ?')
    .get(tripId, user.id);

  if (!membership && !isSuperuser(user)) {
    return null;
  }

  return { trip, membership: membership ?? null };
}

export default readableTrip;
