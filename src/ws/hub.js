/**
 * Who is watching which trip, and how a write reaches them.
 *
 * The hub is the one thing the HTTP side and the socket side share. A position
 * arrives as an ordinary `POST /trips/:id/positions` — that path is unchanged,
 * and it is still the only way a fix is written — and once it has been stored,
 * the route hands the serialized result here. Every socket subscribed to that
 * trip gets it in the same tick.
 *
 * Deliberately not an event bus over the database, a polling loop, or SQLite's
 * update hook. The server is one process (see DEPLOY.md — pm2, one instance,
 * one SQLite file), so the write and the subscribers are in the same memory.
 * Anything cleverer would be building for a second process that does not
 * exist, and it would still have to be replaced properly the day one does.
 *
 * ## What happens when nobody is listening
 *
 * Nothing. `publishPosition` on a trip with no subscribers is a map lookup
 * that finds nothing, and the position is already stored — the socket is a
 * delivery shortcut, never the record. That is the whole reason a client can
 * lose its connection mid-ride and lose nothing but immediacy: the next poll
 * brings it back.
 */
export class PositionHub {
  constructor() {
    /** trip id -> Set of subscriber callbacks. */
    this.subscribers = new Map();
  }

  /**
   * Starts sending [onPosition] every fix stored on [tripId].
   *
   * Returns the function that stops it. A returned unsubscriber rather than a
   * `remove(tripId, fn)` because the caller then cannot get the pair wrong,
   * and a socket that closes has one thing to call per subscription.
   */
  subscribe(tripId, onPosition) {
    const key = Number(tripId);
    let listeners = this.subscribers.get(key);
    if (!listeners) {
      listeners = new Set();
      this.subscribers.set(key, listeners);
    }
    listeners.add(onPosition);

    return () => {
      const current = this.subscribers.get(key);
      if (!current) return;
      current.delete(onPosition);
      // The empty set is removed rather than left behind: a server that has
      // been up for a month should not carry one entry per trip anybody has
      // ever opened.
      if (current.size === 0) {
        this.subscribers.delete(key);
      }
    };
  }

  /** How many sockets are watching a trip. For tests and for a health view. */
  subscriberCount(tripId) {
    return this.subscribers.get(Number(tripId))?.size ?? 0;
  }

  /**
   * Hands one stored fix to everyone watching that trip.
   *
   * A listener that throws is caught and ignored, not allowed to escape:
   * these run inside the HTTP request that stored the position, and one
   * socket in a bad state must not turn a rider's successful report into a
   * 500 for them.
   */
  publishPosition(tripId, position) {
    const listeners = this.subscribers.get(Number(tripId));
    if (!listeners) return 0;

    let delivered = 0;
    for (const listener of listeners) {
      try {
        listener(position);
        delivered += 1;
      } catch {
        // Deliberately swallowed. See above.
      }
    }
    return delivered;
  }
}

/**
 * A hub that goes nowhere, for anything constructed without one.
 *
 * Every test that builds an app through `createApp` predates this and does not
 * care about sockets; they should not all have to be handed a hub to keep
 * working. The methods match, so nothing has to check whether it has a real
 * one.
 */
export const noopHub = {
  subscribe: () => () => {},
  subscriberCount: () => 0,
  publishPosition: () => 0,
};

export default PositionHub;
