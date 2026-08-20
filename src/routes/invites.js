import { Router } from 'express';
import { requireAuth } from '../auth/middleware.js';
import { normalizeEmail } from '../trips/email.js';
import { serializeTrip, serializeInvite } from '../trips/serialize.js';
import {
  activeTripConflict,
  activeTripFor,
  isOneActiveTripAbort,
} from '../trips/activeTrip.js';

export function createInvitesRouter({ db, config }) {
  const router = Router();
  const auth = requireAuth(db, config);

  // Joining has to add the membership and close the invite together, or a
  // crash in between would leave an invite that can never be accepted again.
  const acceptInvite = db.transaction((inviteId, tripId, userId, nowIso) => {
    db.prepare(
      `INSERT INTO trip_members (trip_id, user_id, role) VALUES (?, ?, 'member')
       ON CONFLICT (trip_id, user_id) DO NOTHING`
    ).run(tripId, userId);
    db.prepare(
      "UPDATE trip_invites SET status = 'accepted', accepted_at = ?, accepted_by = ? WHERE id = ?"
    ).run(nowIso, userId, inviteId);
  });

  /**
   * The invitee's inbox. There is no push or email notification yet, so this
   * is how someone discovers they've been invited.
   */
  router.get('/invites', auth, (req, res) => {
    const email = normalizeEmail(req.user.email);
    if (!email) {
      return res.json([]);
    }

    const invites = db
      .prepare(
        `SELECT trip_invites.*, trips.name AS trip_name
         FROM trip_invites
         JOIN trips ON trips.id = trip_invites.trip_id
         WHERE trip_invites.email = ?
           AND trip_invites.status = 'pending'
           AND trips.status = 'active'
         ORDER BY trip_invites.created_at DESC, trip_invites.id DESC`
      )
      .all(email);

    res.json(invites.map((invite) => serializeInvite(invite, { trip_name: invite.trip_name })));
  });

  router.post('/invites/:id/accept', auth, (req, res) => {
    const inviteId = Number(req.params.id);
    if (!Number.isInteger(inviteId) || inviteId <= 0) {
      return res.status(400).json({ error: 'invalid invite id' });
    }

    const invite = db.prepare('SELECT * FROM trip_invites WHERE id = ?').get(inviteId);
    if (!invite) {
      return res.status(404).json({ error: 'invite not found' });
    }

    // An invite is addressed to an email, so only the account signed in with
    // that address may claim it. Invite ids are sequential, so this is the
    // only thing standing between a guessed id and someone else's trip.
    const email = normalizeEmail(req.user.email);
    if (!email || email !== invite.email) {
      return res.status(403).json({ error: 'this invite was sent to a different email address' });
    }

    if (invite.status === 'accepted') {
      return res.status(409).json({ error: 'invite already accepted' });
    }
    if (invite.status !== 'pending') {
      return res.status(409).json({ error: 'invite is no longer valid' });
    }

    const trip = db.prepare('SELECT * FROM trips WHERE id = ?').get(invite.trip_id);
    if (trip.status === 'ended') {
      return res.status(409).json({ error: 'trip has ended' });
    }

    // One active trip per rider — see trips/activeTrip.js. The invite is left
    // pending rather than consumed, so it is still there to accept once the
    // trip in the way has ended.
    const held = activeTripFor(db, req.user.id, { excludeTripId: trip.id });
    if (held) {
      return res.status(409).json(activeTripConflict(held));
    }

    try {
      acceptInvite(invite.id, invite.trip_id, req.user.id, new Date().toISOString());
    } catch (e) {
      if (isOneActiveTripAbort(e)) {
        return res
          .status(409)
          .json(activeTripConflict(activeTripFor(db, req.user.id, { excludeTripId: trip.id })));
      }
      throw e;
    }

    const accepted = db.prepare('SELECT * FROM trip_invites WHERE id = ?').get(invite.id);
    res.json({
      trip: serializeTrip(trip, { role: 'member' }),
      invite: serializeInvite(accepted),
    });
  });

  return router;
}

export default createInvitesRouter;
