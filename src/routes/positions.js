import { Router } from 'express';
import { requireAuth } from '../auth/middleware.js';
import { requireTripMembership } from '../auth/tripMembership.js';
import { requireActiveTrip } from '../auth/tripStatus.js';
import { countableDistanceKm } from '../trips/distance.js';

const HEADING_MAX_DEGREES = 360;
const BATTERY_MAX_PCT = 100;

function serializePosition(row) {
  return {
    user_id: row.user_id,
    display_name: row.display_name,
    photo_url: row.photo_url,
    role: row.role,
    // 0/1 in SQLite; the map screen wants a real boolean.
    is_sharing: row.is_sharing === 1,
    lat: row.lat,
    lng: row.lng,
    accuracy: row.accuracy,
    speed: row.speed,
    heading: row.heading,
    battery_pct: row.battery_pct,
    recorded_at: row.recorded_at,
  };
}

/**
 * Reads an optional numeric field. Returns { error } with a message, or
 * { value } holding the number or null when the field was omitted.
 *
 * `max` may be left out for fields with no meaningful ceiling (how far off a
 * fix can be, how fast a bike can go).
 */
function optionalNumber(payload, field, { min, max, integer = false }) {
  const value = payload[field];
  if (value === undefined) {
    return { value: null };
  }

  const shape = integer ? Number.isInteger(value) : Number.isFinite(value);
  const kind = integer ? 'an integer' : 'a number';
  if (!shape || value < min || (max !== undefined && value > max)) {
    return {
      error:
        max === undefined
          ? `${field} must be ${kind} of ${min} or greater`
          : `${field} must be ${kind} between ${min} and ${max}`,
    };
  }
  return { value };
}

/**
 * Validates a POST body. Returns { error } with a message, or { value } with
 * the cleaned fields ready to upsert.
 *
 * `timestamp` is when the rider's device took the fix, which is not when the
 * request arrives — a phone that lost signal in the hills uploads a backlog
 * once it reconnects. It is optional, and defaults to now.
 */
export function validatePositionInput(body, now = new Date()) {
  const payload = body || {};
  const { lat, lng } = payload;

  // Reject NaN/Infinity/strings — Number.isFinite is false for all of them.
  if (!Number.isFinite(lat) || lat < -90 || lat > 90) {
    return { error: 'lat must be a number between -90 and 90' };
  }
  if (!Number.isFinite(lng) || lng < -180 || lng > 180) {
    return { error: 'lng must be a number between -180 and 180' };
  }

  let recordedAt = now.toISOString();
  if (payload.timestamp !== undefined) {
    if (typeof payload.timestamp !== 'string') {
      return { error: 'timestamp must be an ISO 8601 date string' };
    }
    const parsed = new Date(payload.timestamp);
    if (Number.isNaN(parsed.getTime())) {
      return { error: 'timestamp must be an ISO 8601 date string' };
    }
    // Normalized to UTC, so that stored timestamps stay directly comparable
    // as strings however the device chose to format its offset.
    recordedAt = parsed.toISOString();
  }

  const optional = {
    accuracy: optionalNumber(payload, 'accuracy', { min: 0 }),
    speed: optionalNumber(payload, 'speed', { min: 0 }),
    heading: optionalNumber(payload, 'heading', { min: 0, max: HEADING_MAX_DEGREES }),
    battery_pct: optionalNumber(payload, 'battery_pct', {
      min: 0,
      max: BATTERY_MAX_PCT,
      integer: true,
    }),
  };
  for (const result of Object.values(optional)) {
    if (result.error) {
      return { error: result.error };
    }
  }

  return {
    value: {
      lat,
      lng,
      accuracy: optional.accuracy.value,
      speed: optional.speed.value,
      heading: optional.heading.value,
      battery_pct: optional.battery_pct.value,
      recorded_at: recordedAt,
    },
  };
}

export function createPositionsRouter({ db, config }) {
  const router = Router();

  const selectPrevious = db.prepare(
    'SELECT lat, lng, recorded_at FROM member_positions WHERE trip_id = ? AND user_id = ?'
  );

  const upsertPosition = db.prepare(
    `INSERT INTO member_positions
       (trip_id, user_id, lat, lng, accuracy, speed, heading, battery_pct, recorded_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
     ON CONFLICT (trip_id, user_id) DO UPDATE SET
       lat = excluded.lat,
       lng = excluded.lng,
       accuracy = excluded.accuracy,
       speed = excluded.speed,
       heading = excluded.heading,
       battery_pct = excluded.battery_pct,
       recorded_at = excluded.recorded_at
     WHERE excluded.recorded_at >= member_positions.recorded_at`
  );

  const addLifetimeKm = db.prepare('UPDATE users SET total_km = total_km + ? WHERE id = ?');

  /**
   * Stores a rider's fix and credits the ground they covered getting to it.
   *
   * One transaction, because the two writes are one fact: crediting distance
   * without storing the fix it was measured to would credit the same stretch
   * again on the next post.
   *
   * The previous fix is read here rather than by the caller so that the
   * measure-then-overwrite pair can't be split by anything else.
   */
  const recordFix = db.transaction((tripId, userId, value) => {
    const previous = selectPrevious.get(tripId, userId);
    const km = countableDistanceKm(previous, value);

    upsertPosition.run(
      tripId,
      userId,
      value.lat,
      value.lng,
      value.accuracy,
      value.speed,
      value.heading,
      value.battery_pct,
      value.recorded_at
    );

    if (km > 0) {
      addLifetimeKm.run(km, userId);
    }
  });

  router.use('/trips/:id/positions', requireAuth(db, config), requireTripMembership(db));

  // Reads stay open on an ended trip; writes don't — same rule as waypoints,
  // so a finished ride can still be looked back on (where everyone finished)
  // while nothing new attaches to it.
  //
  // requireActiveTrip therefore has to be mounted per write route: any route
  // added to this file that stores something must carry it explicitly.
  router.post('/trips/:id/positions', requireActiveTrip(), (req, res) => {
    const { error, value } = validatePositionInput(req.body);
    if (error) {
      return res.status(400).json({ error });
    }

    // One row per rider per trip. A retry or a flushed backlog can deliver an
    // older fix after a newer one, so the stored row only moves forward in
    // time — otherwise the map would jump backwards.
    recordFix(req.trip.id, req.user.id, value);

    const stored = db
      .prepare(
        `SELECT member_positions.*, users.display_name, users.photo_url,
                trip_members.role, trip_members.is_sharing
         FROM member_positions
         JOIN users ON users.id = member_positions.user_id
         JOIN trip_members
           ON trip_members.trip_id = member_positions.trip_id
          AND trip_members.user_id = member_positions.user_id
         WHERE member_positions.trip_id = ? AND member_positions.user_id = ?`
      )
      .get(req.trip.id, req.user.id);

    // 200, not 201: there is one row per rider, so this creates it the first
    // time and replaces it after that. The body is what the server now holds,
    // which is not the submitted fix when a stale one was rejected above.
    res.json(serializePosition(stored));
  });

  router.get('/trips/:id/positions', (req, res) => {
    // Every member is listed, including those who haven't reported yet (all
    // position fields null) — the friend list shows them as still offline
    // rather than dropping them.
    const positions = db
      .prepare(
        `SELECT trip_members.user_id, trip_members.role, trip_members.is_sharing,
                users.display_name, users.photo_url,
                member_positions.lat, member_positions.lng, member_positions.accuracy,
                member_positions.speed, member_positions.heading,
                member_positions.battery_pct, member_positions.recorded_at
         FROM trip_members
         JOIN users ON users.id = trip_members.user_id
         LEFT JOIN member_positions
           ON member_positions.trip_id = trip_members.trip_id
          AND member_positions.user_id = trip_members.user_id
         WHERE trip_members.trip_id = ?
         ORDER BY (member_positions.recorded_at IS NULL),
                  member_positions.recorded_at DESC,
                  trip_members.user_id ASC`
      )
      .all(req.trip.id);

    res.json(positions.map(serializePosition));
  });

  return router;
}

export default createPositionsRouter;
