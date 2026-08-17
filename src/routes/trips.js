import { Router } from 'express';
import rateLimit from 'express-rate-limit';
import { requireAuth } from '../auth/middleware.js';
import { requireTripMembership } from '../auth/tripMembership.js';
import { requireTripOwner } from '../auth/tripOwnership.js';
import { requireActiveTrip } from '../auth/tripStatus.js';
import { normalizeEmail } from '../trips/email.js';
import {
  generateJoinCode,
  isJoinCodeLive,
  joinCodeExpiry,
  normalizeJoinCode,
  serializeJoinCode,
} from '../trips/joinCodes.js';
import { serializeTrip, serializeInvite } from '../trips/serialize.js';

const NAME_MIN_LENGTH = 1;
const NAME_MAX_LENGTH = 60;

/** How many past companions the invite screen offers. */
const SUGGESTED_INVITEE_LIMIT = 10;

/**
 * Redeeming a code is the one endpoint where guessing pays, so it gets its own
 * budget — tighter than sign-in's, because no honest rider redeems ten codes a
 * minute. 40 bits of code and 15 minutes of life make a blind search hopeless
 * already; this is what keeps it from being worth starting.
 */
const JOIN_ATTEMPTS_PER_MINUTE = 10;

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

  /**
   * People this rider has ridden with before, who aren't on this trip yet.
   *
   * Saves typing an email address from memory, which is the slowest part of
   * adding someone. Owner-only, matching POST /trips/:id/invites: these are
   * suggestions *for* that form, and offering them to a member who would get a
   * 403 on tapping one would be a worse experience than not offering them.
   */
  router.get(
    '/trips/:id/suggested-invitees',
    auth,
    requireTripOwner(db),
    requireActiveTrip(),
    (req, res) => {
      const suggestions = db
        .prepare(
          `SELECT users.id            AS user_id,
                  users.email         AS email,
                  users.display_name  AS display_name,
                  users.photo_url     AS photo_url,
                  MAX(theirs.joined_at) AS last_ridden_together
           FROM trip_members AS mine
           JOIN trip_members AS theirs
             ON theirs.trip_id = mine.trip_id
            AND theirs.user_id != mine.user_id
           JOIN users ON users.id = theirs.user_id
           WHERE mine.user_id = ?
             AND users.email IS NOT NULL
             AND users.email != ''
             AND theirs.user_id NOT IN (
               SELECT user_id FROM trip_members WHERE trip_id = ?
             )
           GROUP BY users.id
           ORDER BY last_ridden_together DESC, users.id DESC
           LIMIT ?`
        )
        .all(req.user.id, req.trip.id, SUGGESTED_INVITEE_LIMIT);

      res.json(
        suggestions.map((row) => ({
          user_id: row.user_id,
          email: row.email,
          display_name: row.display_name,
          photo_url: row.photo_url,
        }))
      );
    }
  );

  /**
   * Issues a join code for the QR screen.
   *
   * Any member may issue one, not just the owner: the rider who has met
   * someone at a fuel stop is the one holding a phone, and making them find
   * the owner first defeats the point.
   *
   * Issuing retires the trip's previous codes in the same transaction, so at
   * most one is live at a time. A QR left on a screen ten minutes ago stops
   * working the moment a new one is shown, which is the behaviour someone
   * regenerating a code is asking for.
   */
  const issueJoinCode = db.transaction((tripId, userId, code, expiresAt, nowIso) => {
    db.prepare('UPDATE trip_join_codes SET expires_at = ? WHERE trip_id = ? AND expires_at > ?').run(
      nowIso,
      tripId,
      nowIso
    );
    db.prepare(
      'INSERT INTO trip_join_codes (trip_id, code, expires_at, created_by) VALUES (?, ?, ?, ?)'
    ).run(tripId, code, expiresAt, userId);
  });

  router.post(
    '/trips/:id/join-code',
    auth,
    requireTripMembership(db),
    requireActiveTrip(),
    (req, res) => {
      const now = new Date();
      const code = generateJoinCode();
      issueJoinCode(req.trip.id, req.user.id, code, joinCodeExpiry(now), now.toISOString());

      const created = db.prepare('SELECT * FROM trip_join_codes WHERE code = ?').get(code);
      res.status(201).json(serializeJoinCode(created));
    }
  );

  /**
   * Redeems a join code.
   *
   * A code is not consumed by being used — a QR held up to four riders should
   * add four riders. It expires on time, and on the next code being issued,
   * and those are the only two ways it stops working.
   */
  const joinByCode = db.transaction((tripId, userId) => {
    db.prepare(
      `INSERT INTO trip_members (trip_id, user_id, role) VALUES (?, ?, 'member')
       ON CONFLICT (trip_id, user_id) DO NOTHING`
    ).run(tripId, userId);
  });

  router.post(
    '/trips/join',
    rateLimit({
      windowMs: 60 * 1000,
      max: JOIN_ATTEMPTS_PER_MINUTE,
      standardHeaders: true,
      legacyHeaders: false,
    }),
    auth,
    (req, res) => {
      const code = normalizeJoinCode((req.body || {}).code);
      if (!code) {
        return res.status(400).json({ error: 'a join code is required' });
      }

      const row = db.prepare('SELECT * FROM trip_join_codes WHERE code = ?').get(code);
      if (!row) {
        return res.status(404).json({ error: 'join code not found' });
      }
      if (!isJoinCodeLive(row)) {
        // 410 rather than 404: the difference between "never existed" and
        // "you were a minute too late" is the difference between retyping it
        // and asking for a new one.
        return res.status(410).json({ error: 'this join code has expired' });
      }

      const trip = db.prepare('SELECT * FROM trips WHERE id = ?').get(row.trip_id);
      if (!trip || trip.status === 'ended') {
        return res.status(409).json({ error: 'trip has ended' });
      }

      const existing = db
        .prepare('SELECT * FROM trip_members WHERE trip_id = ? AND user_id = ?')
        .get(trip.id, req.user.id);

      // Already a member is a success, not a conflict: two riders scanning the
      // same QR twice should both end up looking at the trip.
      if (!existing) {
        joinByCode(trip.id, req.user.id);
      }

      const membership = db
        .prepare('SELECT * FROM trip_members WHERE trip_id = ? AND user_id = ?')
        .get(trip.id, req.user.id);

      res.json({
        trip: serializeTrip(trip, { role: membership.role }),
        already_member: Boolean(existing),
      });
    }
  );

  /**
   * Ending a trip stops location sharing for the whole group.
   *
   * One transaction, because these are one act: a trip marked ended while
   * somebody's session survived would leave that rider believing they were
   * still being tracked. Riders who want to keep going start a trip of their
   * own.
   */
  const endTrip = db.transaction((tripId, endedAt) => {
    db.prepare("UPDATE trips SET status = 'ended', ended_at = ? WHERE id = ?").run(
      endedAt,
      tripId
    );
    db.prepare('DELETE FROM sharing_sessions WHERE trip_id = ?').run(tripId);
  });

  router.post('/trips/:id/end', auth, requireTripOwner(db), (req, res) => {
    if (req.trip.status === 'ended') {
      return res.status(409).json({ error: 'trip has already ended' });
    }

    endTrip(req.trip.id, new Date().toISOString());

    const ended = db.prepare('SELECT * FROM trips WHERE id = ?').get(req.trip.id);
    res.json(serializeTrip(ended, { role: 'owner' }));
  });

  return router;
}

export default createTripsRouter;
