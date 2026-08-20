import { isSuperuser } from './roles.js';
import { currentMembership } from '../trips/membership.js';

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
 *
 * Somebody who has left the trip is not readable, exactly as if they had never
 * joined. Their `trip_members` row survives so that "ridden with before" can
 * still count the ride, and that history must never be mistaken for access —
 * this is the gate a socket asks, so a mistake here is a departed rider still
 * receiving the group's live positions.
 */
export function readableTrip(db, user, tripId) {
  if (!Number.isInteger(tripId) || tripId <= 0) {
    return null;
  }

  const trip = db.prepare('SELECT * FROM trips WHERE id = ?').get(tripId);
  if (!trip) {
    return null;
  }

  const membership = currentMembership(db, tripId, user.id);

  if (!membership && !isSuperuser(user)) {
    return null;
  }

  return { trip, membership: membership ?? null };
}

export default readableTrip;
