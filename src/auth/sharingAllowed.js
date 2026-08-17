import { isSessionOpen } from '../trips/sharing.js';

/**
 * Decides whether the caller may report a position to this trip. Must be
 * mounted after something that attaches `req.trip` and `req.user`.
 *
 * Two ways through:
 *
 *  - the trip is still running, which is the ordinary case and needs no
 *    session at all; or
 *  - the trip has ended, but this rider has sharing switched on and it hasn't
 *    lapsed — they carried on after the group broke up.
 *
 * A rider who never started sharing, or whose session has run out or been
 * stopped, gets the same 409 an ended trip has always given. Riding on alone
 * is opt-in, per rider: one member continuing does not keep the trip open for
 * anyone else.
 *
 * This replaces requireActiveTrip on position reports only. Waypoints stay
 * shut on an ended trip, which is unchanged.
 */
export function requireSharingAllowed(db) {
  return (req, res, next) => {
    if (req.trip.status === 'active') {
      return next();
    }

    const session = db
      .prepare('SELECT * FROM sharing_sessions WHERE trip_id = ? AND user_id = ?')
      .get(req.trip.id, req.user.id);

    if (!session) {
      // Deliberately the same message as before this feature existed: from
      // the caller's point of view nothing has changed until they opt in.
      return res.status(409).json({ error: 'trip has ended' });
    }

    if (!isSessionOpen(session)) {
      return res.status(409).json({ error: 'your sharing session has ended' });
    }

    next();
  };
}

export default requireSharingAllowed;
