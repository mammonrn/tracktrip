/**
 * What a client and the server may say to each other over the socket.
 *
 * Kept apart from the socket itself so the grammar can be tested without one.
 * Everything here is a pure function of a string: no database, no connection,
 * no clock.
 *
 * ## Why the socket does not accept positions
 *
 * The obvious symmetry would be to let a phone *report* over the socket as
 * well as receive over it. It is deliberately refused, and the refusal names
 * the REST route rather than being silent.
 *
 * Writing a position is not one write. It stores a fix, refuses one that
 * arrives out of order, records a breadcrumb, credits lifetime distance, and
 * is bounded by a rate limit sized against that distance being credited once.
 * A second path into all of that is a second place for a bug to credit the
 * same kilometre twice — and the first symptom would be somebody's rider
 * level quietly inflating, which nobody would report as a bug because it
 * looks like good news.
 *
 * There is also nothing to gain. The phone reports every 45 seconds from a
 * foreground service; the delay a rider actually sees is not the POST, it is
 * the wait until the next screen refresh. That is the half this socket
 * removes.
 */

/** Anything longer than this is not a message this protocol has. */
export const MAX_MESSAGE_BYTES = 4096;

export const CLIENT_MESSAGES = ['subscribe', 'unsubscribe', 'ping'];

/**
 * Parses one inbound frame.
 *
 * Returns `{ error }` with a sentence fit to send back, or `{ value }` with a
 * message this server understands. Never throws: a socket is an open door,
 * and everything that comes through it is somebody else's typing.
 */
export function parseClientMessage(raw) {
  if (typeof raw !== 'string') {
    return { error: 'expected a text frame' };
  }
  if (Buffer.byteLength(raw, 'utf8') > MAX_MESSAGE_BYTES) {
    return { error: 'message too large' };
  }

  let parsed;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return { error: 'expected JSON' };
  }
  if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
    return { error: 'expected a JSON object' };
  }

  const { type } = parsed;
  if (typeof type !== 'string') {
    return { error: 'type is required' };
  }

  if (type === 'position') {
    return {
      error: 'positions are reported over POST /trips/:id/positions, not over this socket',
    };
  }

  if (!CLIENT_MESSAGES.includes(type)) {
    return { error: `unknown message type: ${type}` };
  }

  if (type === 'ping') {
    return { value: { type } };
  }

  const tripId = Number(parsed.trip_id);
  if (!Number.isInteger(tripId) || tripId <= 0) {
    return { error: 'trip_id must be a positive integer' };
  }

  return { value: { type, tripId } };
}

/** A frame the server sends. One place, so the shapes cannot drift apart. */
export const serverMessage = {
  ready: (userId) => ({ type: 'ready', user_id: userId }),
  subscribed: (tripId) => ({ type: 'subscribed', trip_id: tripId }),
  unsubscribed: (tripId) => ({ type: 'unsubscribed', trip_id: tripId }),
  position: (tripId, position) => ({ type: 'position', trip_id: tripId, position }),
  pong: () => ({ type: 'pong' }),
  error: (message) => ({ type: 'error', error: message }),
};

/**
 * Close codes, in the 4000–4999 range the standard leaves to applications.
 *
 * A client that is told *why* it was closed can do the right thing: a
 * rejected token means refresh and retry, and a rate-limited connection means
 * back off rather than reconnect in a loop and be closed again.
 */
export const CLOSE_UNAUTHORIZED = 4401;
export const CLOSE_RATE_LIMITED = 4429;
export const CLOSE_GOING_AWAY = 4000;
