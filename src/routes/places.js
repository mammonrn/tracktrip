import { Router } from 'express';
import rateLimit from 'express-rate-limit';
import { requireAuth } from '../auth/middleware.js';
import { isSuperuser } from '../auth/roles.js';
import {
  ADD_LIMIT,
  MAX_LIMIT,
  NAME_MAX_LENGTH,
  NAME_MIN_LENGTH,
  addAllowance,
  addWindowStart,
  canDeleteSharedPlace,
  normalizeLimit,
  normalizeNear,
  rankSharedPlaces,
  serializeSharedPlace,
  validateSharedPlaceInput,
} from '../places/shared.js';

/**
 * A burst guard, on top of the daily allowance.
 *
 * The allowance in [ADD_LIMIT] is the real quota and the only one a rider
 * should ever meet; this is the one a loop meets. They do different jobs and
 * neither replaces the other: an in-memory limiter is emptied by every `pm2
 * restart`, so it cannot be trusted with a day, and counting rows on every
 * request of a hammering client is work this refuses before it starts.
 *
 * Keyed on the rider rather than on req.ip, like every other limiter here: a
 * group riding together is very often behind one carrier NAT, and an IP-keyed
 * bucket would have them throttle each other.
 */
export const ADD_RATE_LIMIT = { windowMs: 60 * 1000, max: 10 };

/**
 * Places the riders add themselves, shared across the installation.
 *
 * ## What this is for
 *
 * The geocoder cannot find what is not in OpenStreetMap, and the thing that
 * started this was exactly that: "ปตท สวนดอก" is a petrol station every rider
 * in Chiang Mai can name, tagged in OSM as `PTT` with no mention of Suan Dok
 * anywhere in its name or its address. There is no query that finds it. So the
 * riders write it down once, and everybody finds it afterwards.
 *
 * ## Who may do what
 *
 * Reading and adding are open to every signed-in rider, deliberately — see the
 * migration for why this list is shared rather than per-rider. Deleting is not:
 * whoever typed a row, or a super user, and nobody else. A row is something the
 * whole group is using, and removing one has no undo.
 *
 * This is the first route to use [requireSuperuser]'s privilege for something
 * of its own rather than by widening a check that already existed — it is read
 * inline here rather than as middleware, because super-user is one of two ways
 * past this gate rather than the gate itself.
 *
 * ## Why the search is not merged into /geocode/search
 *
 * Two sources with two failure modes. `/geocode/search` is a metered upstream
 * that answers 503 with no API key, 429 when the quota is gone and 502 when
 * LocationIQ is unreachable; this is a local table that answers all three of
 * those with the rider's own places. Merging them server-side would put the
 * local answer behind the remote one's failures, which is precisely backwards
 * — the shared list is most useful on the day the geocoder is not working.
 * The app asks both and merges what comes back.
 */
export function createPlacesRouter({ db, config, now = () => Date.now() }) {
  const router = Router();

  const limitAdds = rateLimit({
    windowMs: ADD_RATE_LIMIT.windowMs,
    max: ADD_RATE_LIMIT.max,
    standardHeaders: true,
    legacyHeaders: false,
    keyGenerator: (req) => String(req.user.id),
    message: { error: 'too many places added' },
  });

  // Signed in for everything. The list is shared with the riders on this
  // server, which is not the same thing as shared with the internet.
  router.use('/places', requireAuth(db, config));

  /**
   * Every shared place, or the ones matching `q`.
   *
   * Matched in JavaScript over the whole table rather than with a SQL LIKE.
   * That is a deliberate trade and it is worth saying why: the match has to
   * require *every* word of the query separately — the entire point of the
   * feature — which in SQL is one `LIKE` per word with its own escaping, and
   * the ranking that follows cannot be expressed in `ORDER BY` at all. On a
   * table this size, which is tens of rows per rider per year, the scan is
   * free and the rule is readable and tested. If it ever stops being free the
   * fix is FTS5, and the ranking above it does not change.
   */
  router.get('/places', (req, res) => {
    const limit = normalizeLimit(req.query.limit);
    if (limit === null) {
      return res.status(400).json({ error: 'limit must be a positive integer' });
    }

    const query = typeof req.query.q === 'string' ? req.query.q : '';
    const near = normalizeNear(req.query.near);

    const rows = db
      .prepare(
        `SELECT p.*, u.display_name AS created_by_name
           FROM shared_places p
           LEFT JOIN users u ON u.id = p.created_by
          ORDER BY p.created_at DESC, p.id DESC`
      )
      .all();

    const results = rankSharedPlaces(rows, { query, near, limit });
    res.json({ query, results: results.map(serializeSharedPlace) });
  });

  router.post('/places', limitAdds, (req, res) => {
    const { error, value } = validateSharedPlaceInput(req.body);
    if (error) {
      return res.status(400).json({ error });
    }

    // The allowance, counted from the rows themselves rather than from a
    // counter in memory. A restart must not hand anybody a fresh twenty.
    const used = db
      .prepare(
        'SELECT COUNT(*) AS n FROM shared_places WHERE created_by = ? AND created_at >= ?'
      )
      .get(req.user.id, addWindowStart(now())).n;

    const allowance = addAllowance(used);
    if (!allowance.allowed) {
      // 429 rather than 403: this is a rate, not a permission. The rider may
      // add places — just not another one yet — and the wording says which.
      return res.status(429).json({
        error: `You can add up to ${allowance.limit} places a day. Try again tomorrow.`,
      });
    }

    const inserted = db
      .prepare('INSERT INTO shared_places (name, lat, lng, created_by) VALUES (?, ?, ?, ?)')
      .run(value.name, value.lat, value.lng, req.user.id);

    // Read back with the join, so a created row and a listed row are the same
    // shape. A client that had to handle two shapes for one thing would get
    // one of them wrong.
    const created = db
      .prepare(
        `SELECT p.*, u.display_name AS created_by_name
           FROM shared_places p
           LEFT JOIN users u ON u.id = p.created_by
          WHERE p.id = ?`
      )
      .get(inserted.lastInsertRowid);

    res.status(201).json(serializeSharedPlace(created));
  });

  router.delete('/places/:id', (req, res) => {
    const id = Number(req.params.id);
    if (!Number.isInteger(id) || id < 1) {
      return res.status(404).json({ error: 'place not found' });
    }

    const place = db.prepare('SELECT * FROM shared_places WHERE id = ?').get(id);
    if (!place) {
      return res.status(404).json({ error: 'place not found' });
    }

    const allowed = canDeleteSharedPlace({
      place,
      userId: req.user.id,
      isSuperuser: isSuperuser(req.user),
    });
    if (!allowed) {
      // 403 and not 404. The row exists and every rider on this server can
      // already see it in their search results — pretending it is missing
      // would be a lie they can immediately disprove.
      return res.status(403).json({ error: 'only the rider who added this place can remove it' });
    }

    db.prepare('DELETE FROM shared_places WHERE id = ?').run(id);
    res.status(204).end();
  });

  return router;
}

export { ADD_LIMIT, MAX_LIMIT, NAME_MAX_LENGTH, NAME_MIN_LENGTH };
export default createPlacesRouter;
