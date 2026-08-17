/**
 * Blocks writes to a trip that has already ended. Must be mounted after
 * something that attaches `req.trip` (requireTripMembership or
 * requireTripOwner).
 *
 * An ended trip is a closed record: members stop reporting positions and
 * nothing new gets attached to it. Reads stay open, so the ride can still be
 * looked back on.
 */
export function requireActiveTrip() {
  return (req, res, next) => {
    if (req.trip.status === 'ended') {
      return res.status(409).json({ error: 'trip has ended' });
    }
    next();
  };
}

export default requireActiveTrip;
