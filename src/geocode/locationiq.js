/**
 * Forward geocoding — a typed place name in, coordinates out.
 *
 * ## Why this lives on the server and not in the app
 *
 * LocationIQ authenticates with a single API key, and an Android APK cannot
 * keep one: anything shipped inside it is one `unzip` away from being read,
 * and a leaked key on a 5,000-request-a-day free tier is not a security
 * incident so much as an immediately exhausted quota. So the phone asks this
 * server, this server holds the key in its environment, and the key never
 * leaves the VPS.
 *
 * That also puts the quota somewhere it can be defended: one cache and one
 * rate limiter here cover every rider, where a per-phone budget could not.
 *
 * https://docs.locationiq.com/docs/search-forward-geocoding
 */

/** The free tier's endpoint. `us1` is the region LocationIQ hands out by default. */
export const LOCATIONIQ_SEARCH_URL = 'https://us1.locationiq.com/v1/search';

/**
 * Shortest query worth spending a request on.
 *
 * One character matches most of the planet, so the answer is useless and the
 * request is not free. The app refuses to send one too — this is the guard
 * behind that, because the app is not the only thing that can call this.
 */
export const MIN_QUERY_LENGTH = 2;

/** Longest query accepted. Past this it is not a place name, it is a paste. */
export const MAX_QUERY_LENGTH = 120;

/** How many results a caller may ask for, and what they get without asking. */
export const DEFAULT_LIMIT = 8;
export const MAX_LIMIT = 10;

/** How long a search may take before we stop waiting on it. */
export const REQUEST_TIMEOUT_MS = 8000;

/**
 * A geocoding failure with a message already fit to show a rider, and the
 * HTTP status the route should answer with.
 *
 * The upstream status is deliberately *not* passed straight through: a 401
 * from LocationIQ means this server's key is wrong, which is not the caller's
 * fault and must not read to them as "you are not signed in".
 */
export class GeocodeError extends Error {
  constructor(message, status = 502) {
    super(message);
    this.name = 'GeocodeError';
    this.status = status;
  }
}

/**
 * The query as it will be sent, or null when there is nothing worth sending.
 *
 * Collapses runs of whitespace so that "  chiang   mai " and "chiang mai" are
 * one cache entry rather than two — on a 5,000-a-day budget, two spellings of
 * the same search costing two requests is worth this much code.
 */
export function normalizeQuery(raw) {
  if (typeof raw !== 'string') return null;
  const collapsed = raw.trim().replace(/\s+/g, ' ');
  if (collapsed.length < MIN_QUERY_LENGTH) return null;
  if (collapsed.length > MAX_QUERY_LENGTH) return null;
  return collapsed;
}

/** A requested result count, clamped to what the endpoint is allowed to serve. */
export function normalizeLimit(raw) {
  if (raw === undefined || raw === null || raw === '') return DEFAULT_LIMIT;
  const parsed = Number(raw);
  if (!Number.isInteger(parsed) || parsed < 1) return null;
  return Math.min(parsed, MAX_LIMIT);
}

/**
 * One LocationIQ result, reduced to what a list of places needs.
 *
 * `display_name` is the full comma-separated address and is what the rider
 * reads; `name` is the first part of it, which is what fills the label field
 * once they pick one. LocationIQ sends the coordinates as **strings**, which
 * is the single most likely thing to go wrong here — a client that trusted
 * the type would end up with "18.7883" as a latitude and a marker nowhere.
 */
export function parsePlace(raw) {
  if (!raw || typeof raw !== 'object') return null;

  const lat = Number(raw.lat);
  const lng = Number(raw.lon);
  if (!Number.isFinite(lat) || lat < -90 || lat > 90) return null;
  if (!Number.isFinite(lng) || lng < -180 || lng > 180) return null;

  const displayName = typeof raw.display_name === 'string' ? raw.display_name.trim() : '';
  const shortName =
    (raw.address && typeof raw.address.name === 'string' && raw.address.name.trim()) ||
    (typeof raw.name === 'string' && raw.name.trim()) ||
    displayName.split(',')[0].trim();

  if (!shortName && !displayName) return null;

  return {
    name: shortName || displayName,
    address: displayName || shortName,
    lat,
    lng,
    // Kept because it is what lets a phone draw a fuel pump differently from
    // a province. Absent on some results, and null rather than missing so the
    // shape on the wire does not change between rows.
    kind: typeof raw.type === 'string' ? raw.type : null,
    osm_id: raw.osm_id === undefined || raw.osm_id === null ? null : String(raw.osm_id),
  };
}

/**
 * Every usable result in an upstream payload.
 *
 * A row that cannot be placed on a map is dropped rather than passed on: the
 * caller's list is a list of things to tap, and a row that goes nowhere is a
 * bug report waiting to be filed.
 */
export function parseResults(payload) {
  if (!Array.isArray(payload)) return [];
  return payload.map(parsePlace).filter(Boolean);
}

/**
 * The URL for one search, key and all.
 *
 * Split out so a test can assert what gets asked for without a network, which
 * matters more than usual here: `format=json` missing means an XML body, and
 * a missing `key` means a 401 that reads as "the search is broken".
 */
export function buildSearchUrl({ apiKey, query, limit, url = LOCATIONIQ_SEARCH_URL, countryCodes }) {
  const params = new URLSearchParams({
    key: apiKey,
    q: query,
    format: 'json',
    limit: String(limit),
    // Address parts as their own fields rather than only the joined string:
    // the app fills the name field from `address.name`, and reconstructing
    // that by splitting on commas gets it wrong the moment a place name has
    // one in it.
    addressdetails: '1',
    normalizecity: '1',
  });
  if (countryCodes) params.set('countrycodes', countryCodes);
  return `${url}?${params.toString()}`;
}

/**
 * Turns an upstream status into something a rider can read.
 *
 * 404 is the odd one: LocationIQ answers a search that matched nothing with
 * 404, not with an empty array. That is not an error — it is the answer — so
 * the caller gets an empty list and no message.
 */
function messageForStatus(status) {
  if (status === 401 || status === 403) {
    // The rider cannot do anything about this and must not be told to try
    // signing in again. The server operator can, and the log line says so.
    return new GeocodeError('Place search is not configured on this server.', 503);
  }
  if (status === 429) {
    return new GeocodeError("Place search is busy. Try again in a moment.", 429);
  }
  return new GeocodeError("Couldn't search for places just now.", 502);
}

/**
 * A search function bound to one API key.
 *
 * [fetchImpl] is injected so the tests never touch the network — the whole
 * point of a mocked upstream is that the quota is not spent proving the
 * parser works.
 */
export function createLocationIqSearch({
  apiKey,
  fetchImpl = globalThis.fetch,
  url = LOCATIONIQ_SEARCH_URL,
  countryCodes,
  timeoutMs = REQUEST_TIMEOUT_MS,
}) {
  return async function search(query, { limit = DEFAULT_LIMIT } = {}) {
    if (!apiKey) {
      // Not thrown at construction: a server with no key still serves every
      // other route, and the one endpoint that needs it says so on use.
      throw new GeocodeError('Place search is not configured on this server.', 503);
    }

    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);

    let response;
    try {
      response = await fetchImpl(buildSearchUrl({ apiKey, query, limit, url, countryCodes }), {
        signal: controller.signal,
        headers: { accept: 'application/json' },
      });
    } catch (cause) {
      throw new GeocodeError("Couldn't reach the place search service.", 502);
    } finally {
      clearTimeout(timer);
    }

    // A search that matched nothing, which is an ordinary outcome.
    if (response.status === 404) return [];

    if (!response.ok) {
      throw messageForStatus(response.status);
    }

    let payload;
    try {
      payload = await response.json();
    } catch (cause) {
      throw new GeocodeError("Couldn't read the place search results.", 502);
    }

    // LocationIQ reports some failures with a 200 and an `error` key.
    if (payload && !Array.isArray(payload) && typeof payload.error === 'string') {
      return [];
    }

    return parseResults(payload);
  };
}

export default createLocationIqSearch;
