import { Router } from 'express';
import { requireAuth } from '../auth/middleware.js';
import { requireTripMembership } from '../auth/tripMembership.js';
import { requireTripParticipation } from '../auth/tripParticipation.js';
import { requireActiveTrip } from '../auth/tripStatus.js';
import {
  expiryFor,
  isSharingOn,
  serializeSharingSession,
  validateShareStartInput,
} from '../trips/sharing.js';

export function createSharingRouter({ db, config }) {
  const router = Router();

  // A sharing session only means anything inside a running trip: ending one
  // stops sharing for the whole group, and clears every session with it.
  // Starting or stopping on a finished trip would be writing state nothing
  // will ever read, so it is refused outright.
  // requireTripParticipation as well as requireTripMembership: a sharing
  // session is a rider saying where *they* are, which is taking part rather
  // than managing, and a super user passes the membership check without being
  // on the trip at all. See auth/tripParticipation.js.
  router.use(
    '/trips/:id/share',
    requireAuth(db, config),
    requireTripMembership(db),
    requireTripParticipation(),
    requireActiveTrip()
  );

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
    const now = new Date().toISOString();

    const existing = db
      .prepare('SELECT * FROM sharing_sessions WHERE trip_id = ? AND user_id = ?')
      .get(req.trip.id, req.user.id);

    if (!isSharingOn(existing, now)) {
      return res.status(409).json({ error: 'you are not sharing on this trip' });
    }

    // Stopping marks the session spent rather than deleting it. Deleting
    // would leave no row at all, and no row is how a rider who never touched
    // the controls looks — which is *sharing*, the default. So a delete here
    // would silently switch sharing back on.
    //
    // A rider with no session yet still needs one written, or the same thing
    // happens: pausing has to leave a trace to be a pause at all.
    db.prepare(
      `INSERT INTO sharing_sessions (trip_id, user_id, started_at, expires_at)
       VALUES (?, ?, ?, ?)
       ON CONFLICT (trip_id, user_id) DO UPDATE SET expires_at = excluded.expires_at`
    ).run(req.trip.id, req.user.id, now, now);

    const session = db
      .prepare('SELECT * FROM sharing_sessions WHERE trip_id = ? AND user_id = ?')
      .get(req.trip.id, req.user.id);

    res.json(serializeSharingSession(req.trip.id, req.user.id, session, now));
  });

  return router;
}

export default createSharingRouter;
