/**
 * How much a single socket may say, per minute.
 *
 * ## Why this exists separately from the REST limiter
 *
 * `POSITION_RATE_LIMIT` bounds how often a rider may *write a position*, and
 * it still does — positions are not accepted over this socket at all (see
 * protocol.js). What a socket can be abused for is different: an open
 * connection costs the server memory and a place in its subscription tables,
 * and a client in a loop can send subscribe/unsubscribe as fast as the network
 * allows without ever writing anything to the database. `express-rate-limit`
 * has nothing to say about that — it counts HTTP requests, and this is one
 * request that never ends.
 *
 * So: a plain token bucket per connection, and a cap on how many trips one
 * connection may hold open.
 *
 * ## Why the numbers are what they are
 *
 * A well-behaved client sends a handful of messages in its whole life: one
 * subscribe per trip screen it opens, one unsubscribe when it leaves, and a
 * ping if it wants to check the line is alive. [MESSAGES_PER_MINUTE] is far
 * above that and far below what a loop produces, which is the shape a limit
 * should have — a rider switching quickly between two trips must never see
 * it, and a runaway client must hit it within a second.
 */
export const MESSAGES_PER_MINUTE = 60;

/**
 * How many trips one socket may be subscribed to at once.
 *
 * Nobody watches four maps. The cap is here so that a client which subscribes
 * in a loop runs out of room rather than growing the server's tables until
 * something else falls over.
 */
export const MAX_SUBSCRIPTIONS = 8;

const WINDOW_MS = 60_000;

/**
 * A refilling budget of messages.
 *
 * Continuous rather than a fixed window: tokens come back a fraction at a
 * time, so a client that has been quiet for thirty seconds has half its
 * allowance back rather than none until the window happens to tick over. A
 * fixed window would also let a client send twice the limit across a boundary,
 * which is the classic way these are wrong.
 *
 * The clock is injected because a test that has to sleep for a minute is a
 * test nobody runs.
 */
export class MessageBudget {
  constructor({ perMinute = MESSAGES_PER_MINUTE, now = () => Date.now() } = {}) {
    this.capacity = perMinute;
    this.tokens = perMinute;
    this.perMs = perMinute / WINDOW_MS;
    this.now = now;
    this.lastRefill = now();
  }

  /**
   * Spends one message. Returns false when there is nothing left to spend,
   * which is the caller's cue to close the connection rather than to answer.
   */
  spend() {
    this.refill();
    if (this.tokens < 1) {
      return false;
    }
    this.tokens -= 1;
    return true;
  }

  refill() {
    const now = this.now();
    const elapsed = now - this.lastRefill;
    if (elapsed <= 0) return;
    this.lastRefill = now;
    this.tokens = Math.min(this.capacity, this.tokens + elapsed * this.perMs);
  }
}

export default MessageBudget;
