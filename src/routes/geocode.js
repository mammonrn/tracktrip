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
 * One line per search, so `pm2 logs tracktrip-api` can answer the question
 * that costs the most time to answer any other way: did this server actually
 * call LocationIQ, or did something short-circuit before it got there?
 *
 * Deliberately says which of the three it was — served from cache, fetched
 * upstream, or failed — because "the search is broken" has completely
 * different fixes depending on the answer, and none of them are guessable
 * from the phone. The query is logged; the key never is.
 */
function logSearch(log, outcome, query, detail) {
  log(`geocode: ${outcome} q=${JSON.stringify(query)}${detail ? ` ${detail}` : ''}`);
}

/**
 * Place search, proxied.
 *
 * [search] is injected rather than built here so the tests can answer without
 * a network and without a key — see src/geocode/locationiq.js for why the key
 * never goes near the phone in the first place.
 *
 * Null [search] is the ordinary state of a server whose LOCATIONIQ_API_KEY has
 * not been set yet: the route exists and answers 503 with a message saying so.
 *
 * **Nothing in this router may ever answer 404.** No matches is a 200 with an
 * empty list; a bad query is a 400; an unset key is a 503. The app relies on
 * that: a 404 can then only mean the route is not on the server at all — a
 * backend older than the app — and it says exactly that rather than the
 * generic wording for a 404, which is "That's no longer there." and sent a
 * whole debugging session looking for a missing shopping centre. There is a
 * test in test/geocode.test.js that holds this.
 */
export function createGeocodeRouter({
  db,
  config,
  search = null,
  cache = new SearchCache(),
  // Injected so the tests can assert what gets logged without printing it.
  logger = console,
}) {
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
        logSearch(logger.log, 'cache', query, `${cached.length} result(s)`);
        return res.json({ query, results: cached, cached: true });
      }

      if (!search) {
        logSearch(logger.warn, 'unconfigured', query, 'LOCATIONIQ_API_KEY is not set');
        return res
          .status(503)
          .json({ error: 'Place search is not configured on this server.' });
      }

      let results;
      try {
        results = await search(query, { limit });
      } catch (e) {
        if (e instanceof GeocodeError) {
          logSearch(logger.warn, 'failed', query, `${e.status} ${e.message}`);
          return res.status(e.status).json({ error: e.message });
        }
        logSearch(logger.warn, 'failed', query, `502 ${e && e.message}`);
        return res.status(502).json({ error: "Couldn't search for places just now." });
      }
      logSearch(logger.log, 'upstream', query, `${results.length} result(s)`);

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
