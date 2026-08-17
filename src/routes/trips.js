import { Router } from 'express';
import { requireAuth } from '../auth/middleware.js';
import { requireTripOwner } from '../auth/tripOwnership.js';
import { requireActiveTrip } from '../auth/tripStatus.js';
import { normalizeEmail } from '../trips/email.js';
import { serializeTrip, serializeInvite } from '../trips/serialize.js';

const NAME_MIN_LENGTH = 1;
const NAME_MAX_LENGTH = 60;

/**
 * Validates a POST /trips body. Returns { error } with a message, or { value }
 * with the trimmed name ready to insert.
 */
export function validateTripInput(body) {
  const { name } = body || {};

  if (typeof name !== 'string') {
    return { error: 'name is required' };
  }
  const trimmed = name.trim();
  if (trimmed.length < NAME_MIN_LENGTH || trimmed.length > NAME_MAX_LENGTH) {
    return {
      error: `name must be between ${NAME_MIN_LENGTH} and ${NAME_MAX_LENGTH} characters`,
    };
  }

  return { value: { name: trimmed } };
}

export function createTripsRouter({ db, config }) {
  const router = Router();
  const auth = requireAuth(db, config);

  // The trip and its owner membership must land together — a trip whose owner
  // isn't in trip_members would be invisible to every membership check.
  const insertTrip = db.transaction((name, ownerId) => {
    const result = db
      .prepare('INSERT INTO trips (name, owner_id) VALUES (?, ?)')
      .run(name, ownerId);
    const tripId = Number(result.lastInsertRowid);
    db.prepare("INSERT INTO trip_members (trip_id, user_id, role) VALUES (?, ?, 'owner')").run(
      tripId,
      ownerId
    );
    return tripId;
  });

  router.post('/trips', auth, (req, res) => {
    const { error, value } = validateTripInput(req.body);
    if (error) {
      return res.status(400).json({ error });
    }

    const tripId = insertTrip(value.name, req.user.id);
    const created = db.prepare('SELECT * FROM trips WHERE id = ?').get(tripId);
    res.status(201).json(serializeTrip(created, { role: 'owner' }));
  });

  router.get('/trips', auth, (req, res) => {
    const trips = db
      .prepare(
        `SELECT trips.*, trip_members.role AS role
         FROM trips
         JOIN trip_members ON trip_members.trip_id = trips.id
         WHERE trip_members.user_id = ?
         ORDER BY trips.created_at DESC, trips.id DESC`
      )
      .all(req.user.id);

    res.json(trips.map((trip) => serializeTrip(trip, { role: trip.role })));
  });

  router.post('/trips/:id/invites', auth, requireTripOwner(db), requireActiveTrip(), (req, res) => {
    const email = normalizeEmail((req.body || {}).email);
    if (!email) {
      return res.status(400).json({ error: 'a valid email is required' });
    }
    if (email === normalizeEmail(req.user.email)) {
      return res.status(400).json({ error: 'you are already on this trip' });
    }

    // Someone who has already joined doesn't need a second invite. Compared
    // via lower() because users.email is stored as Google supplied it.
    const alreadyMember = db
      .prepare(
        `SELECT 1 FROM trip_members
         JOIN users ON users.id = trip_members.user_id
         WHERE trip_members.trip_id = ? AND lower(users.email) = ?`
      )
      .get(req.trip.id, email);
    if (alreadyMember) {
      return res.status(409).json({ error: 'that email is already a member of this trip' });
    }

    const existing = db
      .prepare('SELECT * FROM trip_invites WHERE trip_id = ? AND email = ?')
      .get(req.trip.id, email);

    if (existing) {
      if (existing.status === 'accepted') {
        return res.status(409).json({ error: 'that invite was already accepted' });
      }
      if (existing.status === 'pending') {
        // Nothing to change; re-inviting is how an owner re-sends a link.
        return res.json(serializeInvite(existing));
      }
      // Revoked: UNIQUE (trip_id, email) means the row has to be reused
      // rather than re-inserted, so reopen it under the current owner.
      db.prepare(
        `UPDATE trip_invites
         SET status = 'pending', invited_by = ?, accepted_at = NULL, accepted_by = NULL
         WHERE id = ?`
      ).run(req.user.id, existing.id);
      const reopened = db.prepare('SELECT * FROM trip_invites WHERE id = ?').get(existing.id);
      return res.json(serializeInvite(reopened));
    }

    const result = db
      .prepare('INSERT INTO trip_invites (trip_id, email, invited_by) VALUES (?, ?, ?)')
      .run(req.trip.id, email, req.user.id);
    const created = db.prepare('SELECT * FROM trip_invites WHERE id = ?').get(result.lastInsertRowid);
    res.status(201).json(serializeInvite(created));
  });

  router.post('/trips/:id/end', auth, requireTripOwner(db), (req, res) => {
    if (req.trip.status === 'ended') {
      return res.status(409).json({ error: 'trip has already ended' });
    }

    db.prepare("UPDATE trips SET status = 'ended', ended_at = ? WHERE id = ?").run(
      new Date().toISOString(),
      req.trip.id
    );

    const ended = db.prepare('SELECT * FROM trips WHERE id = ?').get(req.trip.id);
    res.json(serializeTrip(ended, { role: 'owner' }));
  });

  return router;
}

export default createTripsRouter;
