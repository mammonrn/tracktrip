import { Router } from 'express';
import rateLimit from 'express-rate-limit';
import { requireAuth } from '../auth/middleware.js';
import { GeocodeError } from '../geocode/locationiq.js';
import { MAX_WAYPOINTS, parseCoordinate } from '../geocode/directions.js';
import { SearchCache } from '../geocode/cache.js';

/**
 * How many routes one rider may ask for a minute.
 *
 * Much tighter than place search's twelve, and it can afford to be: the app
 * asks for a route when a trip's start or finish is *set*, not while anybody
 * is typing or riding. Six a minute is a rider changing their mind repeatedly;
 * anything above it is a loop.
 */
export const DIRECTIONS_RATE_LIMIT = { windowMs: 60 * 1000, max: 6 };

/**
 * How long a route stays cached.
 *
 * Six hours, against the search cache's ten minutes, and the difference is not
 * an oversight. A route is asked for by every rider on a trip and it is the
 * same question every time — the road from this trip's start to its finish —
 * so one upstream call should cover the whole group for the whole ride. Roads
 * do change, but not on the timescale of an afternoon, and the cost of being
 * six hours out of date on a drawn line is nothing next to eight riders each
 * spending a request on it.
 */
export const ROUTE_CACHE_TTL_MS = 6 * 60 * 60 * 1000;

/**
 * How precisely a coordinate is matched for caching, in decimal places.
 *
 * Five is about a metre, which would make a cache key out of GPS noise. Four
 * is about eleven metres — close enough that two riders' idea of "the fuel
 * station on the corner" lands on one entry, and far too coarse to route
 * anybody to the wrong place.
 */
const CACHE_PRECISION = 4;

/**
 * The stops a route threads through, in order, or an error saying why not.
 *
 * Accepted as `waypoints=lat,lng;lat,lng` — semicolons between points, the
 * same separator the upstream uses in its own path. An array is accepted too,
 * because Express hands one over on its own for a repeated `?waypoints=`, and
 * a client that does it that way deserves the route it asked for rather than a
 * confusing 400 about a string.
 *
 * Absent is not an error: it is the ordinary shape of a trip with no stops,
 * and every route before this parameter existed was one.
 */
export function parseWaypoints(raw) {
  if (raw === undefined || raw === null || raw === '') return { value: [] };

  const parts = (Array.isArray(raw) ? raw : [raw])
    .flatMap((entry) => (typeof entry === 'string' ? entry.split(';') : [entry]))
    .map((entry) => (typeof entry === 'string' ? entry.trim() : entry))
    .filter((entry) => entry !== '');

  if (parts.length > MAX_WAYPOINTS) {
    return { error: `waypoints must be ${MAX_WAYPOINTS} or fewer` };
  }

  const points = [];
  for (const part of parts) {
    const point = parseCoordinate(part);
    // Refused rather than skipped. A stop dropped because its coordinate did
    // not parse would give a route that misses it and looks entirely correct,
    // which is the worst of the three possible outcomes.
    if (!point) return { error: 'each waypoint must be "lat,lng"' };
    points.push(point);
  }
  return { value: points };
}

/**
 * The key one route is cached under.
 *
 * ## Why every stop is in it
 *
 * Two trips can share a start and a finish and be completely different rides —
 * one straight up the highway, one round three viewpoints. Keyed on the two
 * ends alone, the second one asked would be served the first one's line, and
 * it would be served it for six hours: a route drawn through none of the stops
 * the rider set, with nothing on screen to say it was a stale answer.
 *
 * So the key is every coordinate the route has to touch, in order, at the same
 * rounding. Order is part of it because it is part of the route: the same
 * three stops taken the other way round is a different road.
 */
function cacheKeyFor(from, to, waypoints = []) {
  const round = (point) =>
    `${point.lat.toFixed(CACHE_PRECISION)},${point.lng.toFixed(CACHE_PRECISION)}`;
  return [from, ...waypoints, to].map(round).join('>');
}

/**
 * One line per routing call, so `pm2 logs tracktrip-api` can answer the
 * question that is otherwise unanswerable from a phone: did this server call
 * LocationIQ, or did the cache answer? The coordinates are logged; the key
 * never is.
 */
function logRoute(log, outcome, key, detail) {
  log(`directions: ${outcome} ${key}${detail ? ` ${detail}` : ''}`);
}

/**
 * Road routing, proxied.
 *
 * `GET /directions?from=lat,lng&to=lat,lng[&waypoints=lat,lng;lat,lng]` answers
 * with the line to draw, how long it is by road, and roughly how long it takes.
 *
 * `waypoints` is one request, not several joined here: the upstream takes a
 * list of coordinates and returns a single geometry through all of them, which
 * is one call against the quota, one distance that is really the distance, and
 * one line with no seams at the stops.
 *
 * [route] is injected rather than built here so the tests answer without a
 * network and without a key. Null is the ordinary state of a server whose
 * LOCATIONIQ_API_KEY has not been set: the route exists and answers 503.
 *
 * **Nothing in this router may ever answer 404**, for exactly the reason the
 * geocoding router may not (see src/routes/geocode.js): the app has to be able
 * to read a 404 as "this backend is older than this app" and nothing else. A
 * pair of points with no road between them is a 200 carrying `route: null`,
 * which the app draws as the straight line it always drew.
 */
export function createDirectionsRouter({
  db,
  config,
  route = null,
  cache = new SearchCache({ ttlMs: ROUTE_CACHE_TTL_MS }),
  logger = console,
}) {
  const router = Router();

  const limitRoutes = rateLimit({
    windowMs: DIRECTIONS_RATE_LIMIT.windowMs,
    max: DIRECTIONS_RATE_LIMIT.max,
    standardHeaders: true,
    legacyHeaders: false,
    keyGenerator: (req) => String(req.user.id),
    message: { error: 'too many route requests' },
  });

  // Signed in first, limiter after — the limiter's key is the rider's id,
  // which does not exist until requireAuth has run. Authenticated at all
  // because an open proxy in front of a metered key is a quota anyone on the
  // internet can empty.
  router.get('/directions', requireAuth(db, config), limitRoutes, async (req, res) => {
    const from = parseCoordinate(req.query.from);
    const to = parseCoordinate(req.query.to);
    if (!from || !to) {
      return res.status(400).json({ error: 'from and to must each be "lat,lng"' });
    }

    const stops = parseWaypoints(req.query.waypoints);
    if (stops.error) {
      return res.status(400).json({ error: stops.error });
    }
    const waypoints = stops.value;

    const key = cacheKeyFor(from, to, waypoints);
    const cached = cache.get(key);
    if (cached !== undefined) {
      logRoute(logger.log, 'cache', key, cached ? `${cached.points.length} point(s)` : 'no route');
      return res.json({ from, to, waypoints, route: cached, cached: true });
    }

    if (!route) {
      logRoute(logger.warn, 'unconfigured', key, 'LOCATIONIQ_API_KEY is not set');
      return res.status(503).json({ error: 'Road routing is not configured on this server.' });
    }

    let found;
    try {
      found = await route(from, to, waypoints);
    } catch (e) {
      if (e instanceof GeocodeError) {
        logRoute(logger.warn, 'failed', key, `${e.status} ${e.message}`);
        return res.status(e.status).json({ error: e.message });
      }
      logRoute(logger.warn, 'failed', key, `502 ${e && e.message}`);
      return res.status(502).json({ error: "Couldn't work out the road route just now." });
    }

    logRoute(logger.log, 'upstream', key, found ? `${found.points.length} point(s)` : 'no route');

    // Cached even when there is no route: "these two points are not connected
    // by road" is an answer worth not paying for twice, and a trip with a
    // finish on the wrong side of a strait would otherwise re-ask on every
    // rider's map. Stored as null, which is why the read above tests for
    // `undefined` rather than for falsiness.
    cache.set(key, found ?? null);
    res.json({ from, to, waypoints, route: found ?? null, cached: false });
  });

  return router;
}

export default createDirectionsRouter;
