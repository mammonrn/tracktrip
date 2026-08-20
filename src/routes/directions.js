import { Router } from 'express';
import rateLimit from 'express-rate-limit';
import { requireAuth } from '../auth/middleware.js';
import { GeocodeError } from '../geocode/locationiq.js';
import { parseCoordinate } from '../geocode/directions.js';
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

function cacheKeyFor(from, to) {
  const round = (value) => value.toFixed(CACHE_PRECISION);
  return `${round(from.lat)},${round(from.lng)}>${round(to.lat)},${round(to.lng)}`;
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
 * `GET /directions?from=lat,lng&to=lat,lng` answers with the line to draw
 * between two points, how long it is by road, and roughly how long it takes.
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

    const key = cacheKeyFor(from, to);
    const cached = cache.get(key);
    if (cached !== undefined) {
      logRoute(logger.log, 'cache', key, cached ? `${cached.points.length} point(s)` : 'no route');
      return res.json({ from, to, route: cached, cached: true });
    }

    if (!route) {
      logRoute(logger.warn, 'unconfigured', key, 'LOCATIONIQ_API_KEY is not set');
      return res.status(503).json({ error: 'Road routing is not configured on this server.' });
    }

    let found;
    try {
      found = await route(from, to);
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
    res.json({ from, to, route: found ?? null, cached: false });
  });

  return router;
}

export default createDirectionsRouter;
