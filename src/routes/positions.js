import { Router } from 'express';
import rateLimit from 'express-rate-limit';
import { requireAuth } from '../auth/middleware.js';
import { requireTripMembership } from '../auth/tripMembership.js';
import { requireSharingAllowed } from '../auth/sharingAllowed.js';
import { countableDistanceKm } from '../trips/distance.js';
import { isSharingOn } from '../trips/sharing.js';

const HEADING_MAX_DEGREES = 360;
const BATTERY_MAX_PCT = 100;

/**
 * A safety net, not a quota. The app reports a position about once every ten
 * minutes, so this leaves roughly a hundredfold headroom — enough that no
 * real rider will ever see it, while a client stuck in a retry loop stops
 * before it fills the database.
 *
 * Deliberately loose: tightening it towards the real cadence would start
 * catching legitimate bursts (a phone flushing a backlog after losing
 * signal) for no benefit, since the stale-fix rule already discards those.
 */
export const POSITION_RATE_LIMIT = {
  windowMs: 60 * 1000,
  max: 10,
};

function serializePosition(row, { tripActive, nowIso }) {
  // The row carries the rider's sharing session, LEFT JOINed, so a member
  // with no session at all arrives with these as null.
  const session = row.sharing_started_at === null
    ? null
    : { started_at: row.sharing_started_at, expires_at: row.sharing_expires_at };

  return {
    user_id: row.user_id,
    display_name: row.display_name,
    // The name a rider chose for themselves, when they have. The app prefers
    // it over display_name, which is whatever Google supplied.
    username: row.username ?? null,
    photo_url: row.photo_url,
    role: row.role,
    // Derived, not stored, and deliberately the same condition the write
    // guard applies, so a client can tell in advance whether a report would
    // be accepted. On a running trip that is everyone who hasn't paused —
    // most riders never touch the controls, and no session means sharing.
    // Ending the trip stops the whole group, so this is false for everyone
    // afterwards.
    is_sharing: tripActive && isSharingOn(session, nowIso),
    // When this rider's session lapses on its own. null for a rider with no
    // session, and for one set to run until they stop it.
    sharing_until: session?.expires_at ?? null,
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

  // Keyed on the rider, not on req.ip: a group riding together is very often
  // behind one carrier NAT, and an IP-keyed bucket would have them throttle
  // each other. Safe to read req.user here because this only ever runs after
  // requireAuth below.
  const limitPositionReports = rateLimit({
    windowMs: POSITION_RATE_LIMIT.windowMs,
    max: POSITION_RATE_LIMIT.max,
    standardHeaders: true,
    legacyHeaders: false,
    keyGenerator: (req) => String(req.user.id),
    message: { error: 'too many position updates' },
  });

  router.use('/trips/:id/positions', requireAuth(db, config), requireTripMembership(db));

  // Reads stay open on an ended trip; writes are gated per rider.
  //
  // requireSharingAllowed lets a report through while the trip is running, or
  // afterwards if this particular rider still has sharing switched on — see
  // auth/sharingAllowed.js. Any route added to this file that stores
  // something must carry it explicitly.
  //
  // The limiter is attached to this one route rather than to the router, so
  // it governs position reports and nothing else. (Mounting a limiter with
  // router.use() and no path is what silently throttled the whole API until
  // 27586b2.) Reads are left unlimited: the 10/minute figure is derived from
  // how often a rider *reports*, and a map screen refreshing on a different
  // cadence would otherwise be sharing that budget.
  router.post('/trips/:id/positions', limitPositionReports, requireSharingAllowed(db), (req, res) => {
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
        `SELECT member_positions.*, users.display_name, users.username, users.photo_url,
                trip_members.role,
                sharing_sessions.started_at AS sharing_started_at,
                sharing_sessions.expires_at AS sharing_expires_at
         FROM member_positions
         JOIN users ON users.id = member_positions.user_id
         JOIN trip_members
           ON trip_members.trip_id = member_positions.trip_id
          AND trip_members.user_id = member_positions.user_id
         LEFT JOIN sharing_sessions
           ON sharing_sessions.trip_id = member_positions.trip_id
          AND sharing_sessions.user_id = member_positions.user_id
         WHERE member_positions.trip_id = ? AND member_positions.user_id = ?`
      )
      .get(req.trip.id, req.user.id);

    // 200, not 201: there is one row per rider, so this creates it the first
    // time and replaces it after that. The body is what the server now holds,
    // which is not the submitted fix when a stale one was rejected above.
    res.json(
      serializePosition(stored, {
        tripActive: req.trip.status === 'active',
        nowIso: new Date().toISOString(),
      })
    );
  });

  router.get('/trips/:id/positions', (req, res) => {
    // Every member is listed, including those who haven't reported yet (all
    // position fields null) — the friend list shows them as still offline
    // rather than dropping them.
    const positions = db
      .prepare(
        `SELECT trip_members.user_id, trip_members.role,
                users.display_name, users.username, users.photo_url,
                sharing_sessions.started_at AS sharing_started_at,
                sharing_sessions.expires_at AS sharing_expires_at,
                member_positions.lat, member_positions.lng, member_positions.accuracy,
                member_positions.speed, member_positions.heading,
                member_positions.battery_pct, member_positions.recorded_at
         FROM trip_members
         JOIN users ON users.id = trip_members.user_id
         LEFT JOIN member_positions
           ON member_positions.trip_id = trip_members.trip_id
          AND member_positions.user_id = trip_members.user_id
         LEFT JOIN sharing_sessions
           ON sharing_sessions.trip_id = trip_members.trip_id
          AND sharing_sessions.user_id = trip_members.user_id
         WHERE trip_members.trip_id = ?
         ORDER BY (member_positions.recorded_at IS NULL),
                  member_positions.recorded_at DESC,
                  trip_members.user_id ASC`
      )
      .all(req.trip.id);

    // One clock for the whole list, so two riders whose sessions lapse in
    // the same millisecond can't be reported inconsistently.
    const context = {
      tripActive: req.trip.status === 'active',
      nowIso: new Date().toISOString(),
    };
    res.json(positions.map((row) => serializePosition(row, context)));
  });

  return router;
}

export default createPositionsRouter;
