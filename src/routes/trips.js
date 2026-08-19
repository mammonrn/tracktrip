import { Router } from 'express';
import rateLimit from 'express-rate-limit';
import { requireAuth } from '../auth/middleware.js';
import { requireTripMembership } from '../auth/tripMembership.js';
import { requireTripOwner } from '../auth/tripOwnership.js';
import { requireActiveTrip } from '../auth/tripStatus.js';
import { ROLE_SUPERUSER, isSuperuser } from '../auth/roles.js';
import { normalizeEmail } from '../trips/email.js';
import {
  generateJoinCode,
  isJoinCodeLive,
  joinCodeExpiry,
  normalizeJoinCode,
  serializeJoinCode,
} from '../trips/joinCodes.js';
import { serializeTrip, serializeInvite } from '../trips/serialize.js';
import { progressFor } from '../users/levels.js';

const NAME_MIN_LENGTH = 1;
const NAME_MAX_LENGTH = 60;

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

const LABEL_MAX_LENGTH = 120;

/**
 * Validates one end of a trip — `origin` or `destination` in a request body.
 *
 * Three outcomes, and they are all different things:
 *
 *  - the field was **absent**: `{ value: undefined }`, meaning leave whatever
 *    is stored alone;
 *  - the field was **null**: `{ value: null }`, meaning clear it;
 *  - the field was an **object**: `{ value: { lat, lng, label } }`.
 *
 * A coordinate without its other half is refused rather than stored: half a
 * position is not a place, and it would reach the map as a pin in the Gulf of
 * Guinea. The label is optional — a rider dropping a destination from the map
 * may not have a name for it — but a label on its own is refused for the same
 * reason, since there would be nothing to draw.
 */
export function validateTripEndpoint(payload, field) {
  if (!Object.prototype.hasOwnProperty.call(payload, field)) {
    return { value: undefined };
  }

  const raw = payload[field];
  if (raw === null) {
    return { value: null };
  }

  if (typeof raw !== 'object' || Array.isArray(raw)) {
    return { error: `${field} must be an object with lat and lng, or null` };
  }

  const { lat, lng } = raw;
  if (!Number.isFinite(lat) || lat < -90 || lat > 90) {
    return { error: `${field}.lat must be a number between -90 and 90` };
  }
  if (!Number.isFinite(lng) || lng < -180 || lng > 180) {
    return { error: `${field}.lng must be a number between -180 and 180` };
  }

  let label = null;
  if (raw.label !== undefined && raw.label !== null) {
    if (typeof raw.label !== 'string') {
      return { error: `${field}.label must be a string` };
    }
    const trimmed = raw.label.trim();
    if (trimmed.length > LABEL_MAX_LENGTH) {
      return { error: `${field}.label must be at most ${LABEL_MAX_LENGTH} characters` };
    }
    label = trimmed.length > 0 ? trimmed : null;
  }

  return { value: { lat, lng, label } };
}

/**
 * Reads `origin` and `destination` off a body, in the three-way shape
 * [validateTripEndpoint] returns.
 */
export function validateTripEndpoints(body) {
  const payload = body || {};
  const origin = validateTripEndpoint(payload, 'origin');
  if (origin.error) {
    return { error: origin.error };
  }
  const destination = validateTripEndpoint(payload, 'destination');
  if (destination.error) {
    return { error: destination.error };
  }
  return { value: { origin: origin.value, destination: destination.value } };
}

/**
 * Turns a validated endpoint into the three columns it occupies.
 * `undefined` contributes nothing, so an untouched field is left alone.
 */
function endpointColumns(field, value) {
  if (value === undefined) {
    return {};
  }
  if (value === null) {
    return { [`${field}_lat`]: null, [`${field}_lng`]: null, [`${field}_label`]: null };
  }
  return {
    [`${field}_lat`]: value.lat,
    [`${field}_lng`]: value.lng,
    [`${field}_label`]: value.label,
  };
}

export function createTripsRouter({ db, config }) {
  const router = Router();
  const auth = requireAuth(db, config);

  // The trip and its owner membership must land together — a trip whose owner
  // isn't in trip_members would be invisible to every membership check.
  const insertTrip = db.transaction((name, ownerId, endpoints) => {
    const columns = { ...endpointColumns('origin', endpoints.origin ?? null) };
    Object.assign(columns, endpointColumns('destination', endpoints.destination ?? null));

    const names = ['name', 'owner_id', ...Object.keys(columns)];
    const result = db
      .prepare(
        `INSERT INTO trips (${names.join(', ')}) VALUES (${names.map(() => '?').join(', ')})`
      )
      .run(name, ownerId, ...Object.values(columns));
    const tripId = Number(result.lastInsertRowid);
    db.prepare("INSERT INTO trip_members (trip_id, user_id, role) VALUES (?, ?, 'owner')").run(
      tripId,
      ownerId
    );
    return tripId;
  });

  /**
   * Creates a trip. `origin` and `destination` are optional here — a rider
   * naming a trip from the trip list often has no idea where it is going yet,
   * and PATCH below is how they fill it in later.
   */
  router.post('/trips', auth, (req, res) => {
    const { error, value } = validateTripInput(req.body);
    if (error) {
      return res.status(400).json({ error });
    }

    const endpoints = validateTripEndpoints(req.body);
    if (endpoints.error) {
      return res.status(400).json({ error: endpoints.error });
    }

    const tripId = insertTrip(value.name, req.user.id, endpoints.value);
    const created = db.prepare('SELECT * FROM trips WHERE id = ?').get(tripId);
    res.status(201).json(serializeTrip(created, { role: 'owner' }));
  });

  /**
   * Sets or clears where a trip starts and ends.
   *
   * A partial update: a field left out is left alone, and sending it as
   * `null` clears it. That distinction is the whole reason this is a PATCH —
   * a PUT would make "I only want to set the destination" indistinguishable
   * from "I want to wipe the origin".
   *
   * Owner-only, matching every other edit to the trip itself. Allowed on an
   * ended trip on purpose: filling in where a ride actually finished is
   * something people do afterwards, and the write guard that stops position
   * reports has nothing to do with it.
   */
  router.patch('/trips/:id', auth, requireTripOwner(db), (req, res) => {
    const { error, value } = validateTripEndpoints(req.body);
    if (error) {
      return res.status(400).json({ error });
    }

    const columns = { ...endpointColumns('origin', value.origin) };
    Object.assign(columns, endpointColumns('destination', value.destination));

    const fields = Object.keys(columns);
    if (fields.length > 0) {
      db.prepare(`UPDATE trips SET ${fields.map((f) => `${f} = ?`).join(', ')} WHERE id = ?`).run(
        ...fields.map((f) => columns[f]),
        req.trip.id
      );
    }

    const updated = db.prepare('SELECT * FROM trips WHERE id = ?').get(req.trip.id);
    // The caller is the owner — requireTripOwner just proved it — so the role
    // is known without another query.
    res.json(serializeTrip(updated, { role: 'owner' }));
  });

  /**
   * The caller's trips — or, for a super user asking with `?all=true`, every
   * trip on the server.
   *
   * Opt-in rather than automatic, and that is the whole design of it. A super
   * user is still a rider: they open the app to see the ride they are on, and
   * a list that silently grew to hold everybody else's would make their own
   * app worse the day they were promoted. The wider view is a thing they ask
   * for.
   *
   * Trips they are not on come back with `role: 'superuser'`. Not 'owner',
   * which would be a lie about a real column, and not null, which every client
   * would have to special-case — a distinct value says exactly what the caller
   * is to this trip: someone who can see it because of who they are, not
   * because of anything on it.
   */
  router.get('/trips', auth, (req, res) => {
    const wantsAll = String(req.query.all).toLowerCase() === 'true';

    if (wantsAll && isSuperuser(req.user)) {
      const trips = db
        .prepare(
          `SELECT trips.*, trip_members.role AS role
           FROM trips
           LEFT JOIN trip_members
             ON trip_members.trip_id = trips.id AND trip_members.user_id = ?
           ORDER BY trips.created_at DESC, trips.id DESC`
        )
        .all(req.user.id);

      return res.json(
        trips.map((trip) => serializeTrip(trip, { role: trip.role ?? ROLE_SUPERUSER }))
      );
    }

    // Asked for by somebody who is not a super user: answered with their own
    // trips rather than a 403. The parameter is a request for more, and the
    // honest answer to it is everything they are allowed to see.
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
   *
   * Ordered by how many trips the two have shared, not by recency. The person
   * ridden with twenty times is the one being looked for, even if somebody met
   * once last weekend is more recent — and recency is the tiebreaker for
   * everyone on the same count. Unlimited: the list is bounded by how many
   * people this rider has actually ridden with, the client scrolls it, and a
   * cap of ten silently hid exactly the regulars a long-standing group has.
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
                  users.username      AS username,
                  users.photo_url     AS photo_url,
                  COUNT(DISTINCT mine.trip_id) AS trips_together,
                  MAX(theirs.joined_at)        AS last_ridden_together
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
           ORDER BY trips_together DESC, last_ridden_together DESC, users.id DESC`
        )
        .all(req.user.id, req.trip.id);

      res.json(
        suggestions.map((row) => ({
          user_id: row.user_id,
          email: row.email,
          display_name: row.display_name,
          username: row.username,
          photo_url: row.photo_url,
          trips_together: row.trips_together,
        }))
      );
    }
  );

  /**
   * Every member's level, in one call.
   *
   * The map lists a rider's level beside their name, and the alternative was
   * the app asking `/me/level` once per member — which it cannot do anyway,
   * since that route only ever answers for the caller. A trip of eight would
   * otherwise need eight requests on a phone that is already polling
   * positions on a mountain road.
   *
   * Levels are derived from `users.total_km` by the same `progressFor` the
   * profile screen's widget uses, so the two can never disagree about where
   * a rider stands.
   *
   * Any member may read this: they can already see each other's names and
   * positions, and a level is the least private thing on the screen.
   */
  router.get('/trips/:id/member-levels', auth, requireTripMembership(db), (req, res) => {
    const members = db
      .prepare(
        `SELECT users.id AS user_id, users.total_km AS total_km
         FROM trip_members
         JOIN users ON users.id = trip_members.user_id
         WHERE trip_members.trip_id = ?
         ORDER BY users.id ASC`
      )
      .all(req.trip.id);

    res.json(
      members.map((row) => ({
        user_id: row.user_id,
        ...progressFor(row.total_km),
      }))
    );
  });

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
