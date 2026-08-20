/**
 * Road routing — two coordinates in, the line a bike would actually ride out.
 *
 * ## Why this is here and not in the app
 *
 * The same reason forward geocoding is (see src/geocode/locationiq.js): one
 * LocationIQ key, metered, and an APK cannot keep a secret. The server holds
 * the key, caches the answers, and rate-limits per rider, so the quota is
 * something that can be defended rather than something every phone spends on
 * its own.
 *
 * ## Why the cache matters more here than it does for search
 *
 * A route is asked for by *every rider on a trip*, and they are all asking the
 * same question: the road from this trip's start to this trip's finish. Eight
 * riders opening the map is one upstream request, not eight, and it stays one
 * for as long as the entry lives — which is why the routing cache's TTL is
 * hours rather than the ten minutes a place-name cache uses. Place data
 * changes when a café opens; the road between two towns does not.
 *
 * ## What this deliberately is not
 *
 * Not turn-by-turn navigation, and not a choice between alternatives. It
 * answers with one line to draw and how long it is. Everything else a routing
 * API can do is a later phase.
 *
 * https://docs.locationiq.com/docs/directions-directions-service
 */

import { GeocodeError } from './locationiq.js';

/** The free tier's directions endpoint. `us1` is the default region. */
export const LOCATIONIQ_DIRECTIONS_URL = 'https://us1.locationiq.com/v1/directions/driving';

/** How long a routing call may take before we stop waiting on it. */
export const REQUEST_TIMEOUT_MS = 10000;

/**
 * The most points one route comes back with.
 *
 * `overview=simplified` already gives a line meant for drawing rather than for
 * navigating, but a thousand-kilometre route still arrives with thousands of
 * vertices, and a phone drawing an overlay on every frame pays for each one.
 * Past this the line is thinned evenly — see [capPoints] for why that is safe
 * for something being drawn and would not be for something being measured.
 */
export const MAX_ROUTE_POINTS = 600;

/**
 * A `lat,lng` pair as the query string carries it, or null when it is not one.
 *
 * Strings, because this parses a query parameter. Both halves are required and
 * both are range-checked: a swapped pair is a route across the planet, and the
 * upstream would happily bill us for it.
 */
export function parseCoordinate(raw) {
  if (typeof raw !== 'string') return null;

  const parts = raw.split(',');
  if (parts.length !== 2) return null;

  const lat = Number(parts[0].trim());
  const lng = Number(parts[1].trim());
  if (!Number.isFinite(lat) || lat < -90 || lat > 90) return null;
  if (!Number.isFinite(lng) || lng < -180 || lng > 180) return null;

  return { lat, lng };
}

/**
 * Thins a line to at most [max] points, keeping the first and the last.
 *
 * Even sampling rather than Douglas-Peucker: this is a line being *drawn*, and
 * at the zoom levels a phone map uses, dropping every other vertex of an
 * already-simplified geometry is invisible. It would not be safe for the
 * distance — which is why the distance comes from the upstream's own figure
 * and is never re-measured from these points.
 */
export function capPoints(points, max = MAX_ROUTE_POINTS) {
  if (!Array.isArray(points)) return [];
  if (max < 2) return points.slice(0, Math.max(0, max));
  if (points.length <= max) return points;

  const kept = [];
  const step = (points.length - 1) / (max - 1);
  for (let i = 0; i < max - 1; i += 1) {
    kept.push(points[Math.round(i * step)]);
  }
  // The last point is taken from the end rather than from the arithmetic, so
  // the drawn line always finishes where the route does.
  kept.push(points[points.length - 1]);
  return kept;
}

/**
 * The URL for one route, key and all.
 *
 * Split out so a test can assert what gets asked for without a network — the
 * two things most likely to be wrong here are silent: `geometries=geojson`
 * missing means an encoded-polyline string that nothing downstream can read,
 * and the coordinate order, which is **lng,lat** in the path. Latitude first
 * would ask for a route through the sea off Somalia for most of Thailand.
 */
export function buildDirectionsUrl({ apiKey, from, to, url = LOCATIONIQ_DIRECTIONS_URL }) {
  const params = new URLSearchParams({
    key: apiKey,
    // The line, as coordinates rather than as an encoded polyline.
    geometries: 'geojson',
    // Enough shape to draw, without the vertex count a navigator would need.
    overview: 'simplified',
    // No turn list: this phase draws a line and nothing reads the steps.
    steps: 'false',
    alternatives: 'false',
  });
  const path = `${from.lng},${from.lat};${to.lng},${to.lat}`;
  return `${url}/${path}?${params.toString()}`;
}

/**
 * The first usable route in an upstream payload, or null.
 *
 * Null rather than a throw for a payload that has no route in it: "there is no
 * road between these two points" is an answer — an island, a coordinate in the
 * sea — and it is the caller's job to fall back to a straight line, not to
 * show an error.
 */
export function parseRoute(payload) {
  if (!payload || typeof payload !== 'object') return null;

  const route = Array.isArray(payload.routes) ? payload.routes[0] : null;
  if (!route || typeof route !== 'object') return null;

  const coordinates = route.geometry && route.geometry.coordinates;
  if (!Array.isArray(coordinates) || coordinates.length < 2) return null;

  const points = [];
  for (const pair of coordinates) {
    if (!Array.isArray(pair) || pair.length < 2) continue;
    // GeoJSON is [longitude, latitude]. Getting this the wrong way round is
    // the single most likely mistake in this file, so it is written once.
    const lng = Number(pair[0]);
    const lat = Number(pair[1]);
    if (!Number.isFinite(lat) || lat < -90 || lat > 90) continue;
    if (!Number.isFinite(lng) || lng < -180 || lng > 180) continue;
    points.push({ lat, lng });
  }
  if (points.length < 2) return null;

  // Metres and seconds upstream; kilometres and minutes are what the app
  // shows, and converting here keeps the unit conversion in one place rather
  // than in every client.
  const metres = Number(route.distance);
  const seconds = Number(route.duration);

  return {
    points: capPoints(points),
    distance_km: Number.isFinite(metres) && metres >= 0 ? metres / 1000 : null,
    duration_min:
      Number.isFinite(seconds) && seconds >= 0 ? Math.round(seconds / 60) : null,
  };
}

/**
 * Turns an upstream status into something a rider could read — though on this
 * feature almost nobody ever will, because the app falls back to the straight
 * line it drew before and says nothing. The wording exists for the one caller
 * that is a person poking the endpoint by hand.
 */
function messageForStatus(status) {
  if (status === 401 || status === 403) {
    return new GeocodeError('Road routing is not configured on this server.', 503);
  }
  if (status === 429) {
    return new GeocodeError('Road routing is busy. Try again in a moment.', 429);
  }
  return new GeocodeError("Couldn't work out the road route just now.", 502);
}

/**
 * A routing function bound to one API key.
 *
 * [fetchImpl] is injected so the tests never touch the network, for the same
 * reason the search's is: the quota is not for proving a parser works.
 *
 * Resolves to null when the upstream has no route between the two points —
 * which is an answer, not a failure. Throws [GeocodeError] when the *service*
 * did not answer.
 */
export function createLocationIqDirections({
  apiKey,
  fetchImpl = globalThis.fetch,
  url = LOCATIONIQ_DIRECTIONS_URL,
  timeoutMs = REQUEST_TIMEOUT_MS,
}) {
  return async function route(from, to) {
    if (!apiKey) {
      // Not thrown at construction, for the same reason search isn't: a server
      // with no key still serves every other route, and this one says so.
      throw new GeocodeError('Road routing is not configured on this server.', 503);
    }

    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);

    let response;
    try {
      response = await fetchImpl(buildDirectionsUrl({ apiKey, from, to, url }), {
        signal: controller.signal,
        headers: { accept: 'application/json' },
      });
    } catch (cause) {
      throw new GeocodeError("Couldn't reach the road routing service.", 502);
    } finally {
      clearTimeout(timer);
    }

    // LocationIQ answers "no route between these points" with a 404, the same
    // way it answers "nothing is called that" on search. Not an error.
    if (response.status === 404) return null;

    if (!response.ok) {
      throw messageForStatus(response.status);
    }

    let payload;
    try {
      payload = await response.json();
    } catch (cause) {
      throw new GeocodeError("Couldn't read the road route.", 502);
    }

    // A 200 carrying a refusal, which this API does: `code` is `Ok` on a route
    // it found and something else — `NoRoute`, `InvalidInput` — when it did
    // not. Treated as "no route", because from the map's side it is one.
    if (payload && typeof payload.code === 'string' && payload.code !== 'Ok') {
      return null;
    }

    return parseRoute(payload);
  };
}

export default createLocationIqDirections;
