import { isSharingOn } from '../trips/sharing.js';

/**
 * Decides whether the caller may report a position to this trip. Must be
 * mounted after something that attaches `req.trip` and `req.user`.
 *
 * Two things have to hold:
 *
 *  - the trip is still running. Ending a trip stops location sharing for
 *    everyone in the group, full stop — nobody carries on past it, and a
 *    rider who wants to keep going starts a trip of their own; and
 *  - this rider hasn't paused. Sharing is on by default for the whole trip,
 *    so most riders have no session at all; one exists only once someone has
 *    reached for the controls, and it can be running or spent.
 *
 * The same condition is reported as `is_sharing` on every position read, so
 * a client can always tell in advance whether a report would be accepted.
 */
export function requireSharingAllowed(db) {
  return (req, res, next) => {
    if (req.trip.status !== 'active') {
      return res.status(409).json({ error: 'trip has ended' });
    }

    const session = db
      .prepare('SELECT * FROM sharing_sessions WHERE trip_id = ? AND user_id = ?')
      .get(req.trip.id, req.user.id);

    if (!isSharingOn(session)) {
      return res.status(409).json({ error: 'your sharing session has ended' });
    }

    next();
  };
}

export default requireSharingAllowed;
