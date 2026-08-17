/**
 * Loads the trip named by :id and confirms the authenticated user belongs to
 * it. Must be mounted after requireAuth.
 *
 * Attaches `req.trip` (the trips row) and `req.membership` (the trip_members
 * row) for downstream handlers.
 *
 * Responds 400 for a non-numeric :id, 404 when the trip doesn't exist, and
 * 403 when the caller isn't a member of it.
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
    if (!membership) {
      return res.status(403).json({ error: 'not a member of this trip' });
    }

    req.trip = trip;
    req.membership = membership;
    next();
  };
}

export default requireTripMembership;
