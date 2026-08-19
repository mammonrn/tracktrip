/**
 * Refuses a caller who is not actually on the trip. Must be mounted after
 * requireTripMembership, which is what puts `req.membership` there.
 *
 * ## The line this draws
 *
 * A super user may *manage* every trip — read it, fix its destination, end a
 * ride whose owner has gone home with their phone off. What they may not do is
 * *take part* in one they never joined: report a position, or start a sharing
 * session. The difference is not a technicality. A position from somebody who
 * is not a member puts a rider on everybody's map who is not on the ride, and
 * credits distance to an account that never travelled it.
 *
 * Membership used to be guaranteed by requireTripMembership sitting above
 * these routes, so nothing had to say this out loud. Now that a super user
 * passes that check without a membership, the routes that need one have to ask
 * for it — and it is better to ask in a named guard than in four handlers that
 * each remember on their own.
 */
export function requireTripParticipation() {
  return (req, res, next) => {
    if (!req.membership) {
      return res.status(403).json({ error: 'not a member of this trip' });
    }
    next();
  };
}

export default requireTripParticipation;
