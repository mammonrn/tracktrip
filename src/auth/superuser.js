import { isSuperuser } from './roles.js';

/**
 * Refuses anyone who is not a super user. Must be mounted after requireAuth.
 *
 * Nothing uses this yet — the super-user privilege currently expresses itself
 * by widening checks that already exist (see requireTripMembership and
 * requireTripOwner) rather than by opening doors of its own. It is here
 * because the first genuinely administrative route — a user list, a way to
 * end somebody else's runaway trip from outside a trip — should not have to
 * invent its own check, and because having one place to look for "what does
 * super user actually let you do" is worth more than the six lines.
 *
 * 403 rather than 404: the caller is authenticated and the route exists; they
 * are simply not allowed. Hiding it behind a 404 would be pretending, and
 * every honest error message this codebase writes says what happened.
 */
export function requireSuperuser() {
  return (req, res, next) => {
    if (!isSuperuser(req.user)) {
      return res.status(403).json({ error: 'super user only' });
    }
    next();
  };
}

export default requireSuperuser;
