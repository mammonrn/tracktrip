import { isSuperuser } from './roles.js';

/**
 * Loads the trip named by :id and confirms the authenticated user owns it.
 * Must be mounted after requireAuth.
 *
 * The stricter sibling of requireTripMembership, for actions only the owner
 * may take (inviting riders, ending the trip). Attaches `req.trip`.
 *
 * Responds 400 for a non-numeric :id, 404 when the trip doesn't exist, and
 * 403 when the caller isn't its owner — including when they are an ordinary
 * member of it.
 *
 * A super user passes. That is what "manage every trip" means: ending a ride
 * whose owner has gone home with their phone off, or fixing a destination
 * somebody typed wrong, without being added to the trip to do it. `req.trip`
 * still holds the real owner_id, so nothing downstream mistakes them for one.
 */
export function requireTripOwner(db) {
  return (req, res, next) => {
    const tripId = Number(req.params.id);
    if (!Number.isInteger(tripId) || tripId <= 0) {
      return res.status(400).json({ error: 'invalid trip id' });
    }

    const trip = db.prepare('SELECT * FROM trips WHERE id = ?').get(tripId);
    if (!trip) {
      return res.status(404).json({ error: 'trip not found' });
    }

    if (trip.owner_id !== req.user.id && !isSuperuser(req.user)) {
      return res.status(403).json({ error: 'only the trip owner can do this' });
    }

    req.trip = trip;
    req.isSuperuser = isSuperuser(req.user);
    next();
  };
}

export default requireTripOwner;
