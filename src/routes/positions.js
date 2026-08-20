import { Router } from 'express';
import rateLimit from 'express-rate-limit';
import { requireAuth } from '../auth/middleware.js';
import { requireTripMembership } from '../auth/tripMembership.js';
import { requireSharingAllowed } from '../auth/sharingAllowed.js';
import { requireTripParticipation } from '../auth/tripParticipation.js';
import { countableDistanceKm } from '../trips/distance.js';
import { isSharingOn } from '../trips/sharing.js';

const HEADING_MAX_DEGREES = 360;
const BATTERY_MAX_PCT = 100;

/**
 * A safety net, not a quota.
 *
 * ## Why this moved from 10 to 30
 *
 * The app reported every 45 seconds — 1.33 a minute — and 10 left roughly
 * seven and a half times headroom. The cadence is now 10 seconds, which is 6
 * a minute, and against a ceiling of 10 that headroom would have been about
 * 1.4x once the boundary of a fixed window is accounted for (a perfectly
 * periodic 10-second signal puts 6 or 7 posts in any given 60-second window,
 * depending on phase).
 *
 * 1.4x is not a safety net. It is a ceiling the normal cadence brushes, and
 * the first thing it would have caught is a rider whose phone ran a cycle
 * slightly fast — a 429 on a real report, which this limiter exists
 * specifically never to do.
 *
 * 30 restores the character it was given: five times the real cadence, so it
 * only ever trips on something genuinely broken, while still stopping a
 * client stuck in a retry loop long before it fills the database.
 *
 * The Android side keeps a copy of this number and a test that fails when the
 * two part company — see `ReportCadence.BACKEND_MAX_POSTS_PER_MINUTE` and
 * ReportCadenceTest. Changing this without changing that is the failure mode
 * the pair exists to prevent.
 */
export const POSITION_RATE_LIMIT = {
  windowMs: 60 * 1000,
  max: 30,
};

/**
 * The reporting cadence the ceiling above is sized against, in seconds.
 *
 * Duplicated from `ReportCadence.INTERVAL_MS` on the Android side for the
 * same reason that file duplicates the ceiling: the two live in different
 * languages and only a test on each side keeps the copies honest.
 */
export const CLIENT_REPORT_INTERVAL_SECONDS = 10;

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

/** The most points one request will return. See the route's own comment. */
export const HISTORY_MAX_LIMIT = 1000;

/** What one request returns when it does not ask for a number. */
export const HISTORY_DEFAULT_LIMIT = 500;

/**
 * Validates the query string on the trail endpoint.
 *
 * Kept as a function rather than inline so it can be tested without a server,
 * and so the three parameters' rules are written once where they can be read
 * together.
 */
export function validateHistoryQuery(query = {}) {
  let userId = null;
  if (query.user_id !== undefined) {
    userId = Number(query.user_id);
    if (!Number.isInteger(userId) || userId <= 0) {
      return { error: 'user_id must be a positive integer' };
    }
  }

  let since = null;
  if (query.since !== undefined) {
    const parsed = new Date(query.since);
    if (Number.isNaN(parsed.getTime())) {
      return { error: 'since must be an ISO 8601 date string' };
    }
    // Normalized the same way stored timestamps are, so the string comparison
    // in the query means what it looks like it means.
    since = parsed.toISOString();
  }

  let limit = HISTORY_DEFAULT_LIMIT;
  if (query.limit !== undefined) {
    limit = Number(query.limit);
    if (!Number.isInteger(limit) || limit <= 0) {
      return { error: 'limit must be a positive integer' };
    }
    // Clamped rather than refused: a client asking for more than the server
    // will give is not making a mistake, it is asking for everything.
    limit = Math.min(limit, HISTORY_MAX_LIMIT);
  }

  return { value: { userId, since, limit } };
}

export function createPositionsRouter({ db, config, hub }) {
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

  const addBreadcrumb = db.prepare(
    `INSERT INTO position_history (trip_id, user_id, lat, lng, recorded_at)
     VALUES (?, ?, ?, ?, ?)`
  );

  /**
   * Stores a rider's fix, records the breadcrumb, and credits the ground they
   * covered getting there.
   *
   * One transaction, because the three writes are one fact: crediting distance
   * without storing the fix it was measured to would credit the same stretch
   * again on the next post, and a trail with a point the current position row
   * never accepted would be a route the rider did not take.
   *
   * The previous fix is read here rather than by the caller so that the
   * measure-then-overwrite pair can't be split by anything else.
   *
   * ## The breadcrumb
   *
   * `position_history` has existed since migration 0001 and until now nothing
   * ever wrote a row to it — the table, its index, and the cleanup job that
   * purges it were all in place around an empty space. `member_positions`
   * holds one row per rider and is overwritten on every report, so the moment
   * a fix was replaced, where that rider had been was gone. This is the trail:
   * one row per accepted fix, which is what a "route travelled" line on the
   * map will be drawn from.
   *
   * Only *accepted* fixes are recorded. A retry or a phone flushing a backlog
   * after losing signal can deliver an older fix after a newer one; the
   * position row refuses to move backwards (see the WHERE on the upsert), and
   * the trail has to refuse the same one or a replayed backlog would scribble
   * over a route that was already right.
   */
  const recordFix = db.transaction((tripId, userId, value) => {
    const previous = selectPrevious.get(tripId, userId);
    const km = countableDistanceKm(previous, value);
    const movesForward = !previous || value.recorded_at >= previous.recorded_at;

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

    if (movesForward) {
      addBreadcrumb.run(tripId, userId, value.lat, value.lng, value.recorded_at);
    }

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
  //
  // requireTripParticipation is what keeps a super user out of this one: they
  // pass the membership check without being on the trip, and a position from
  // somebody who is not riding would be a phantom on everybody's map.
  const reportGuards = [
    limitPositionReports,
    requireTripParticipation(),
    requireSharingAllowed(db),
  ];

  router.post('/trips/:id/positions', reportGuards, (req, res) => {
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

    const serialized = serializePosition(stored, {
      tripActive: req.trip.status === 'active',
      nowIso: new Date().toISOString(),
    });

    // Everyone watching this trip over a socket hears about it now rather
    // than on their next poll — which is the whole point of the socket. After
    // the write, never instead of it: what is announced is what the server
    // has actually stored, which is not the submitted fix when a stale one
    // was rejected above.
    hub?.publishPosition(req.trip.id, serialized);

    // 200, not 201: there is one row per rider, so this creates it the first
    // time and replaces it after that.
    res.json(serialized);
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

  /**
   * The trail: every fix a trip has accepted, oldest first.
   *
   * `GET /trips/:id/positions` answers "where is everyone now" from
   * `member_positions`, which holds one row per rider and is overwritten on
   * every report. This answers "where have they been", from
   * `position_history`, and the two are different questions with different
   * shapes — one row per rider against one row per fix.
   *
   * Query parameters, all optional:
   *
   *  - `user_id` — one rider's trail rather than the whole group's. Drawing
   *    eight overlapping lines is rarely what anybody wants.
   *  - `since` — an ISO timestamp; only fixes recorded after it. This is what
   *    makes the endpoint usable while riding: fetch the trail once, then ask
   *    only for what has been added.
   *  - `limit` — capped at [HISTORY_MAX_LIMIT]. A day's ride at the app's
   *    cadence is around two thousand points per rider, and a phone drawing a
   *    line does not want them all at once.
   *
   * Reads stay open on an ended trip, like every other read here: looking at
   * where a group went is most of the point of having gone.
   */
  router.get('/trips/:id/positions/history', (req, res) => {
    const parsed = validateHistoryQuery(req.query);
    if (parsed.error) {
      return res.status(400).json({ error: parsed.error });
    }
    const { userId, since, limit } = parsed.value;

    const clauses = ['trip_id = ?'];
    const params = [req.trip.id];
    if (userId !== null) {
      clauses.push('user_id = ?');
      params.push(userId);
    }
    if (since !== null) {
      clauses.push('recorded_at > ?');
      params.push(since);
    }

    // Ordered oldest first because that is the order a line is drawn in, and
    // the id breaks ties: two fixes can share a timestamp to the millisecond
    // when a phone flushes a backlog, and a trail whose points swap places
    // between two reads would redraw itself differently each time.
    const rows = db
      .prepare(
        `SELECT id, user_id, lat, lng, recorded_at
         FROM position_history
         WHERE ${clauses.join(' AND ')}
         ORDER BY recorded_at ASC, id ASC
         LIMIT ?`
      )
      .all(...params, limit);

    res.json({
      trip_id: req.trip.id,
      // Said out loud so a client can tell "that is the whole trail" from
      // "that is as much as you asked for". Without it, a map would draw a
      // line that stops in the middle of a road and look like a bug.
      truncated: rows.length === limit,
      points: rows.map((row) => ({
        id: row.id,
        user_id: row.user_id,
        lat: row.lat,
        lng: row.lng,
        recorded_at: row.recorded_at,
      })),
    });
  });

  return router;
}

export default createPositionsRouter;
