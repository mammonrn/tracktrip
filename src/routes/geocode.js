import { Router } from 'express';
import rateLimit from 'express-rate-limit';
import { requireAuth } from '../auth/middleware.js';
import {
  GeocodeError,
  normalizeLimit,
  normalizeQuery,
  MAX_QUERY_LENGTH,
  MIN_QUERY_LENGTH,
} from '../geocode/locationiq.js';
import { SearchCache } from '../geocode/cache.js';

/**
 * How many searches one rider may run a minute.
 *
 * Keyed on the rider rather than on req.ip, for the same reason position
 * reports are: a group riding together is very often behind one carrier NAT,
 * and an IP-keyed bucket would have them throttle each other.
 *
 * Twelve is generous for a person typing — the app debounces, so a name typed
 * straight through costs one — and mean for anything looping.
 */
export const SEARCH_RATE_LIMIT = { windowMs: 60 * 1000, max: 12 };

/**
 * Place search, proxied.
 *
 * [search] is injected rather than built here so the tests can answer without
 * a network and without a key — see src/geocode/locationiq.js for why the key
 * never goes near the phone in the first place.
 *
 * Null [search] is the ordinary state of a server whose LOCATIONIQ_API_KEY has
 * not been set yet: the route exists and answers 503 with a message saying so,
 * rather than 404 which would read to the app as "this build is too old".
 */
export function createGeocodeRouter({ db, config, search = null, cache = new SearchCache() }) {
  const router = Router();

  const limitSearches = rateLimit({
    windowMs: SEARCH_RATE_LIMIT.windowMs,
    max: SEARCH_RATE_LIMIT.max,
    standardHeaders: true,
    legacyHeaders: false,
    keyGenerator: (req) => String(req.user.id),
    message: { error: 'too many place searches' },
  });

  // Signed in first, and the limiter after it — the limiter's key is the
  // rider's id, which does not exist until requireAuth has run. Authenticated
  // at all because an open proxy in front of a metered key is a quota anyone
  // on the internet can empty.
  router.get(
    '/geocode/search',
    requireAuth(db, config),
    limitSearches,
    async (req, res) => {
      const query = normalizeQuery(req.query.q);
      if (!query) {
        return res.status(400).json({
          error: `q must be between ${MIN_QUERY_LENGTH} and ${MAX_QUERY_LENGTH} characters`,
        });
      }

      const limit = normalizeLimit(req.query.limit);
      if (limit === null) {
        return res.status(400).json({ error: 'limit must be a positive integer' });
      }

      // Case-folded so "Pai" and "pai" are one entry. The upstream is not
      // case-sensitive either, so this changes no answer.
      const cacheKey = `${limit}:${query.toLowerCase()}`;
      const cached = cache.get(cacheKey);
      if (cached) {
        return res.json({ query, results: cached, cached: true });
      }

      if (!search) {
        return res
          .status(503)
          .json({ error: 'Place search is not configured on this server.' });
      }

      let results;
      try {
        results = await search(query, { limit });
      } catch (e) {
        if (e instanceof GeocodeError) {
          return res.status(e.status).json({ error: e.message });
        }
        return res.status(502).json({ error: "Couldn't search for places just now." });
      }

      // Cached even when empty: "nothing is called that" is an answer worth
      // not paying for twice, and it is a common one while somebody is still
      // typing the second half of a name.
      cache.set(cacheKey, results);
      res.json({ query, results, cached: false });
    }
  );

  return router;
}

export default createGeocodeRouter;
