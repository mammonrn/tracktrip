import { isSuperuser } from './roles.js';

/**
 * Loads the trip named by :id and confirms the authenticated user belongs to
 * it. Must be mounted after requireAuth.
 *
 * Attaches `req.trip` (the trips row) and `req.membership` (the trip_members
 * row) for downstream handlers.
 *
 * Responds 400 for a non-numeric :id, 404 when the trip doesn't exist, and
 * 403 when the caller isn't a member of it.
 *
 * A super user passes without a membership, and `req.membership` is then
 * null — deliberately, rather than a synthesised row. They are not a member
 * of the trip and nothing downstream should be able to mistake them for one:
 * a fabricated row would put them in rosters, in position lists, and in the
 * distance credited to real riders. Handlers that need a membership must say
 * so; the one place that genuinely acts as a member — reporting a position —
 * goes through requireSharingAllowed instead, which has no bypass and never
 * will.
 */
export function requireTripMembership(db) {
  return (req, res, next) => {
    const tripId = Number(req.params.id);
    if (!Number.isInteger(tripId) || tripId <= 0) {
      return res.status(400).json({ error: 'invalid trip id' });
    }

    const trip = db.prepare('SELECT * FROM trips WHERE id = ?').get(tripId);
    if (!trip) {
      return res.status(404).json({ error: 'trip not found' });
    }

    const membership = db
      .prepare('SELECT * FROM trip_members WHERE trip_id = ? AND user_id = ?')
      .get(tripId, req.user.id);
    if (!membership && !isSuperuser(req.user)) {
      return res.status(403).json({ error: 'not a member of this trip' });
    }

    req.trip = trip;
    req.membership = membership ?? null;
    req.isSuperuser = isSuperuser(req.user);
    next();
  };
}

export default requireTripMembership;
