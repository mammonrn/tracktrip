/**
 * Places the riders add themselves: what one may contain, how many a rider may
 * add, and how a typed query finds one.
 *
 * ## Why any of this is here rather than in the route
 *
 * All three are decisions rather than plumbing, and all three are the sort
 * that go wrong quietly. A matcher that drops the second word of a query still
 * returns *something*, so it looks like it works; a quota that resets on a
 * restart still refuses somebody eventually, so it looks like it works. Pure
 * functions in their own file are the difference between "looks like it works"
 * and a test that says so.
 *
 * See src/db/migrations/0011_shared_places.sql for why the table is shared by
 * everybody on the installation rather than private to whoever typed the row.
 */

import { haversineKm } from '../trips/distance.js';

/** The same bounds `trip_waypoints` puts on a name — one list, one shape. */
export const NAME_MIN_LENGTH = 1;
export const NAME_MAX_LENGTH = 60;

/** How many results a caller may ask for, and what they get without asking. */
export const DEFAULT_LIMIT = 8;
export const MAX_LIMIT = 25;

/**
 * How many places one rider may add, and over what window.
 *
 * ## Why a rolling window and not "per calendar day"
 *
 * A calendar day has to be a day *somewhere*. On UTC, the riders this serves
 * are in Thailand and their quota would reset at seven in the morning, part
 * way through the ride they are planning; on Bangkok time, the server would be
 * doing timezone arithmetic to enforce a spam limit. A rolling twenty-four
 * hours needs neither and cannot be doubled by adding twenty at one minute to
 * midnight and twenty more at one minute past.
 *
 * Twenty is set against what the feature is for: naming the handful of places
 * OpenStreetMap is missing. A rider who has typed twenty in a day is not doing
 * that, and one who genuinely needs the twenty-first can add it tomorrow or
 * ask somebody else — which, on a shared list, actually works.
 */
export const ADD_LIMIT = 20;
export const ADD_WINDOW_MS = 24 * 60 * 60 * 1000;

/** How far around a point results are preferred, when a caller sends one. */
export const NEAR_BIAS_KM = 200;

/**
 * A shared place on the wire.
 *
 * [created_by_name] is joined in rather than left to the caller to look up: a
 * list of user ids is not something a phone can show, and the one thing this
 * list has to say that a LocationIQ result does not is *who typed this*. Null
 * when the account is gone — see the migration's ON DELETE SET NULL.
 */
export function serializeSharedPlace(row) {
  return {
    id: row.id,
    name: row.name,
    lat: row.lat,
    lng: row.lng,
    created_by: row.created_by ?? null,
    created_by_name: row.created_by_name ?? null,
    created_at: row.created_at,
  };
}

/**
 * Validates a POST body. Returns { error } with a message, or { value } with
 * the cleaned fields ready to insert.
 *
 * Deliberately the same shape and the same wording as
 * [validateWaypointInput] — the two take the same three fields, and a client
 * that has learned to read one error should not meet a different vocabulary
 * for the identical mistake.
 */
export function validateSharedPlaceInput(body) {
  const { name, lat, lng } = body || {};

  if (typeof name !== 'string') {
    return { error: 'name is required' };
  }
  const trimmedName = name.trim();
  if (trimmedName.length < NAME_MIN_LENGTH || trimmedName.length > NAME_MAX_LENGTH) {
    return {
      error: `name must be between ${NAME_MIN_LENGTH} and ${NAME_MAX_LENGTH} characters`,
    };
  }

  // Number.isFinite is false for NaN, Infinity and every string, which is the
  // whole guard: JSON will happily carry "18.7883" and a coordinate that is a
  // string lands a pin nowhere.
  if (!Number.isFinite(lat) || lat < -90 || lat > 90) {
    return { error: 'lat must be a number between -90 and 90' };
  }
  if (!Number.isFinite(lng) || lng < -180 || lng > 180) {
    return { error: 'lng must be a number between -180 and 180' };
  }

  return { value: { name: trimmedName, lat, lng } };
}

/**
 * A requested result count, clamped to what this route is willing to serve.
 *
 * Null for anything that is not a positive integer, so the route answers 400
 * rather than quietly serving the default — a client sending `limit=abc`
 * has a bug, and hiding it costs somebody an afternoon.
 */
export function normalizeLimit(raw) {
  if (raw === undefined || raw === null || raw === '') return DEFAULT_LIMIT;
  const parsed = Number(raw);
  if (!Number.isInteger(parsed) || parsed < 1) return null;
  return Math.min(parsed, MAX_LIMIT);
}

/**
 * The words a query is made of.
 *
 * Split on whitespace, and every one of them has to appear somewhere in the
 * name for the place to match. That rule is the entire reason this table
 * exists: "ปตท สวนดอก" is two words, and the geocoder that failed on it failed
 * because no single string in OpenStreetMap contains both. Here a rider can
 * type the name with both words in it, and both words are what is looked for.
 *
 * No stemming and no word segmentation. Thai does not put spaces between
 * words, so any attempt at segmenting is a dictionary this project is not
 * going to carry — and substring matching on an un-segmented language is
 * exactly right: "สวนดอก" is a substring of "ปั๊มสวนดอก" whether or not
 * anything knows where the word ends.
 */
export function queryTerms(raw) {
  if (typeof raw !== 'string') return [];
  return raw.trim().split(/\s+/).filter(Boolean);
}

/** Whether [name] contains every word of [terms], ignoring ASCII case. */
export function matchesTerms(name, terms) {
  if (terms.length === 0) return true;
  const haystack = String(name).toLowerCase();
  return terms.every((term) => haystack.includes(term.toLowerCase()));
}

/**
 * How good a match this name is for this query: lower sorts first.
 *
 * Three bands, and the gaps between them are what stop a distance tie-break
 * from promoting a vague match over an exact one:
 *
 *   0 — the name *is* the query. Somebody typed the name of the place.
 *   1 — the name starts with it. "ปตท สวนดอก 2" for "ปตท สวนดอก".
 *   2 — every word is in there somewhere. "ปั๊ม ปตท. สาขาสวนดอก".
 *
 * Band 2 is the one the geocoder could not do, and it is deliberately last:
 * within it, the order is settled by where the rider is.
 */
export function matchRank(name, terms, query) {
  const lowered = String(name).toLowerCase();
  const wanted = String(query ?? '').trim().toLowerCase();
  if (wanted && lowered === wanted) return 0;
  if (wanted && lowered.startsWith(wanted)) return 1;
  return 2;
}

/**
 * Shared places for one query, best first.
 *
 * Pure, and given rows rather than a database, because the interesting part is
 * the ordering and the ordering is worth a test that does not need a schema.
 *
 * [near] is a preference and never a filter — the same rule the geocoder
 * follows. A rider in Chiang Mai typing a name that exists in both Chiang Mai
 * and Nan sees the near one first and the far one second, rather than not
 * seeing the far one at all. Within a band, ties are settled by distance when
 * there is a position to measure from and by newest-first when there is not.
 */
export function rankSharedPlaces(rows, { query = '', near = null, limit = DEFAULT_LIMIT } = {}) {
  const terms = queryTerms(query);

  const scored = rows
    .filter((row) => matchesTerms(row.name, terms))
    .map((row) => ({
      row,
      rank: matchRank(row.name, terms, query),
      // Infinity rather than null, so it sorts last without a special case
      // and so a row with no bias to measure against never jumps the queue.
      distanceKm: near ? haversineKm(near, { lat: row.lat, lng: row.lng }) : Infinity,
    }));

  scored.sort((a, b) => {
    if (a.rank !== b.rank) return a.rank - b.rank;
    if (a.distanceKm !== b.distanceKm) return a.distanceKm - b.distanceKm;
    // Newest first. The id is the tie-break of last resort so the order is
    // total: two rows written in the same millisecond must not swap between
    // requests, or a list would reshuffle under a rider's finger.
    const byTime = String(b.row.created_at ?? '').localeCompare(String(a.row.created_at ?? ''));
    if (byTime !== 0) return byTime;
    return (b.row.id ?? 0) - (a.row.id ?? 0);
  });

  return scored.slice(0, limit).map((entry) => entry.row);
}

/**
 * A `near=lat,lng` parameter as it will be used, or null when there is none.
 *
 * Null is the ordinary case and not a failure: a phone with no fix yet, or a
 * caller that did not send one. The list then comes back newest-first, which
 * is what it did before a bias existed.
 *
 * Unrounded, unlike the geocoder's — that one rounds to share cache entries
 * between riders, and there is no cache here to share. A local query answered
 * from SQLite costs nothing to get exactly right.
 */
export function normalizeNear(raw) {
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
 * Whether this rider may delete this place.
 *
 * Whoever typed it, or a super user. Not "any member", even though any member
 * can *read* every row and any member can drop a waypoint on somebody else's
 * trip: a waypoint belongs to one ride and disappears with it, and a shared
 * place is a thing the whole group is still using. Somebody removing another
 * rider's petrol station for fun is a small act with no undo.
 *
 * An orphaned row — `created_by` NULL because the account was deleted — is
 * nobody's, so no ordinary rider matches it and only a super user can clear
 * it. That is the honest answer rather than a special case: the person who
 * could speak for it is gone.
 */
export function canDeleteSharedPlace({ place, userId, isSuperuser = false }) {
  if (isSuperuser) return true;
  if (place?.created_by == null) return false;
  return place.created_by === userId;
}

/**
 * The oldest `created_at` still inside the add window, as the ISO string the
 * column stores.
 *
 * A string comparison rather than SQLite date arithmetic, because the column
 * is ISO-8601 in UTC with a fixed width — so lexicographic order *is*
 * chronological order, and `created_at >= ?` uses the index. Date functions on
 * a text column would not.
 */
export function addWindowStart(now = Date.now(), windowMs = ADD_WINDOW_MS) {
  // `toISOString` and SQLite's `strftime('%Y-%m-%dT%H:%M:%fZ')` produce the
  // same shape to the millisecond, which is what makes the comparison valid.
  return new Date(now - windowMs).toISOString();
}

/**
 * What is left of a rider's allowance, given how many they have added inside
 * the window.
 *
 * Split out from the query so the arithmetic is testable and so the route has
 * one thing to ask rather than three: whether to refuse, and what to say.
 */
export function addAllowance(usedInWindow, limit = ADD_LIMIT) {
  const remaining = Math.max(0, limit - usedInWindow);
  return { allowed: remaining > 0, remaining, limit };
}

export default {
  ADD_LIMIT,
  ADD_WINDOW_MS,
  DEFAULT_LIMIT,
  MAX_LIMIT,
  NAME_MAX_LENGTH,
  NAME_MIN_LENGTH,
  addAllowance,
  addWindowStart,
  canDeleteSharedPlace,
  matchRank,
  matchesTerms,
  normalizeLimit,
  normalizeNear,
  queryTerms,
  rankSharedPlaces,
  serializeSharedPlace,
  validateSharedPlaceInput,
};
