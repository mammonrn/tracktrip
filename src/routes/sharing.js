import { Router } from 'express';
import { requireAuth } from '../auth/middleware.js';
import { requireTripMembership } from '../auth/tripMembership.js';
import {
  expiryFor,
  serializeSharingSession,
  validateShareStartInput,
} from '../trips/sharing.js';

export function createSharingRouter({ db, config }) {
  const router = Router();

  // Membership-gated, but deliberately NOT behind requireActiveTrip: turning
  // sharing on for a trip that has already ended is the whole point of these
  // routes. A rider who forgot to press it before the owner ended the trip
  // can still press it after.
  router.use('/trips/:id/share', requireAuth(db, config), requireTripMembership(db));

  router.post('/trips/:id/share/start', (req, res) => {
    const { error, value } = validateShareStartInput(req.body);
    if (error) {
      return res.status(400).json({ error });
    }

    const now = new Date();
    const startedAt = now.toISOString();
    const expiresAt = expiryFor(value.durationMinutes, now);

    // Starting again replaces the session rather than erroring: switching
    // from "30 minutes" to "until I stop" is a normal thing to do halfway
    // through, and it is the same rider changing their own mind.
    db.prepare(
      `INSERT INTO sharing_sessions (trip_id, user_id, started_at, expires_at)
       VALUES (?, ?, ?, ?)
       ON CONFLICT (trip_id, user_id) DO UPDATE SET
         started_at = excluded.started_at,
         expires_at = excluded.expires_at`
    ).run(req.trip.id, req.user.id, startedAt, expiresAt);

    const session = db
      .prepare('SELECT * FROM sharing_sessions WHERE trip_id = ? AND user_id = ?')
      .get(req.trip.id, req.user.id);

    res.json(serializeSharingSession(req.trip.id, req.user.id, session, startedAt));
  });

  router.post('/trips/:id/share/stop', (req, res) => {
    const result = db
      .prepare('DELETE FROM sharing_sessions WHERE trip_id = ? AND user_id = ?')
      .run(req.trip.id, req.user.id);

    if (result.changes === 0) {
      return res.status(409).json({ error: 'you are not sharing on this trip' });
    }

    res.json(serializeSharingSession(req.trip.id, req.user.id, null));
  });

  return router;
}

export default createSharingRouter;
