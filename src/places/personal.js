/**
 * Places one rider saved for themselves: what one may contain, and the single
 * rule that matters about reading them.
 *
 * ## The rule
 *
 * **Every query is scoped to one `user_id`, with no exception and no
 * override.** There is no "list all", no admin read, and no super-user path —
 * not because a super user could not be trusted, but because a code path that
 * can return another rider's rows is a code path that can be reached by
 * accident, and this table holds home addresses.
 *
 * That is enforced in three places on purpose, because one is not enough:
 * the route mounts under `/me`, so the owner is `req.user.id` and never a
 * parameter a caller can influence; every statement in src/routes/places.js
 * carries `WHERE user_id = ?`; and [canAccessPersonalPlace] below is the
 * predicate the route checks before it answers with a single row.
 *
 * ## What is deliberately absent
 *
 * No join to `users`, ever. `shared_places` joins to show who added a place,
 * because that is the point of a shared list; here the owner is the caller and
 * already known, so a join would add a table to a query whose whole safety
 * property is which table it reads.
 *
 * No search endpoint either. A rider has a handful of these — home, work,
 * their parents' place — and they are all sent at once. Matching them against
 * a typed query happens on the phone, which means the query never travels and
 * there is no filter to get wrong.
 */

/** The same bounds every other named point in this app is held to. */
export const NAME_MIN_LENGTH = 1;
export const NAME_MAX_LENGTH = 60;

/** What a shortcut chip may say. Short, because it is drawn on a chip. */
export const LABEL_MIN_LENGTH = 1;
export const LABEL_MAX_LENGTH = 24;

/**
 * How many a rider may keep.
 *
 * Not a spam guard — nobody else can see these, so there is nothing to spam.
 * It is a guard on the screen: the shortcuts are a row of chips above the
 * search results, and a rider with two hundred of them has a search screen
 * made entirely of chips. Twenty is far past home, work, and everywhere else
 * a person actually rides to often.
 */
export const MAX_PER_USER = 20;

/**
 * One personal place on the wire.
 *
 * `user_id` is **not** serialised. The caller is the owner — that is the only
 * way a row gets here at all — so sending it back says nothing, and a field
 * that says nothing is a field that ends up in a log.
 */
export function serializePersonalPlace(row) {
  return {
    id: row.id,
    label: row.label,
    name: row.name,
    lat: row.lat,
    lng: row.lng,
    created_at: row.created_at,
  };
}

/**
 * Validates a POST body. Returns { error } with a message, or { value } with
 * the cleaned fields ready to insert.
 *
 * Note what is *not* taken from the body: `user_id`. It comes from the
 * authenticated session and nowhere else, so a caller cannot write a place
 * into somebody else's list by asking to.
 */
export function validatePersonalPlaceInput(body) {
  const { label, name, lat, lng } = body || {};

  if (typeof label !== 'string') {
    return { error: 'label is required' };
  }
  const trimmedLabel = label.trim();
  if (trimmedLabel.length < LABEL_MIN_LENGTH || trimmedLabel.length > LABEL_MAX_LENGTH) {
    return {
      error: `label must be between ${LABEL_MIN_LENGTH} and ${LABEL_MAX_LENGTH} characters`,
    };
  }

  if (typeof name !== 'string') {
    return { error: 'name is required' };
  }
  const trimmedName = name.trim();
  if (trimmedName.length < NAME_MIN_LENGTH || trimmedName.length > NAME_MAX_LENGTH) {
    return {
      error: `name must be between ${NAME_MIN_LENGTH} and ${NAME_MAX_LENGTH} characters`,
    };
  }

  // Number.isFinite is false for NaN, Infinity and every string.
  if (!Number.isFinite(lat) || lat < -90 || lat > 90) {
    return { error: 'lat must be a number between -90 and 90' };
  }
  if (!Number.isFinite(lng) || lng < -180 || lng > 180) {
    return { error: 'lng must be a number between -180 and 180' };
  }

  return { value: { label: trimmedLabel, name: trimmedName, lat, lng } };
}

/**
 * Whether this rider may touch this row. The only answer is "it is theirs".
 *
 * No super-user branch, unlike [canDeleteSharedPlace]. A super user administers
 * the installation's shared list; nobody administers somebody's home address,
 * and a privilege that could read one would have to be audited every time this
 * file changed. The absence is the feature.
 *
 * A missing row answers false rather than throwing, so the route can treat
 * "not yours" and "not there" as one branch — see the 404 in the route for why
 * those two must be indistinguishable from outside.
 */
export function canAccessPersonalPlace({ place, userId }) {
  if (!place) return false;
  if (userId === null || userId === undefined) return false;
  return place.user_id === userId;
}

/** Whether this rider has room for another. See [MAX_PER_USER]. */
export function hasRoomForAnother(count, max = MAX_PER_USER) {
  return count < max;
}

export default {
  LABEL_MAX_LENGTH,
  LABEL_MIN_LENGTH,
  MAX_PER_USER,
  NAME_MAX_LENGTH,
  NAME_MIN_LENGTH,
  canAccessPersonalPlace,
  hasRoomForAnother,
  serializePersonalPlace,
  validatePersonalPlaceInput,
};
