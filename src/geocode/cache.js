/**
 * A small, short-lived answer cache for place searches.
 *
 * ## Why a cache is part of the feature, not an optimisation
 *
 * The free tier is 5,000 requests a day for the whole server, shared by every
 * rider on it. A group planning one ride will type the same handful of place
 * names — "Pai", "Mae Hong Son", the same fuel station — within minutes of
 * each other, and each of those is a request nobody gets back.
 *
 * The app debounces so that typing a name costs one request instead of ten;
 * this is the other half, so that ten riders typing the same name cost one
 * request instead of ten. Neither alone is enough.
 *
 * Deliberately in memory and deliberately small: a geocode is not worth a
 * table, and a restart losing it costs one request per query.
 */
export class SearchCache {
  /**
   * [ttlMs] is short on purpose. Place data does change — a new café, a
   * renamed road — and a stale answer that persists for a day would be worse
   * than the request it saved. Ten minutes covers a group planning together
   * and nothing beyond it.
   */
  constructor({ ttlMs = 10 * 60 * 1000, maxEntries = 500, now = () => Date.now() } = {}) {
    this.ttlMs = ttlMs;
    this.maxEntries = maxEntries;
    this.now = now;
    this.entries = new Map();
  }

  /** The cached value for this key, or undefined when there is none or it has expired. */
  get(key) {
    const entry = this.entries.get(key);
    if (!entry) return undefined;

    if (entry.expiresAt <= this.now()) {
      this.entries.delete(key);
      return undefined;
    }

    // Re-inserted so the Map's insertion order is least-recently-used first,
    // which is what makes the eviction below evict the right thing.
    this.entries.delete(key);
    this.entries.set(key, entry);
    return entry.value;
  }

  set(key, value) {
    this.entries.delete(key);
    this.entries.set(key, { value, expiresAt: this.now() + this.ttlMs });

    while (this.entries.size > this.maxEntries) {
      const oldest = this.entries.keys().next();
      if (oldest.done) break;
      this.entries.delete(oldest.value);
    }
  }

  get size() {
    return this.entries.size;
  }
}

export default SearchCache;
