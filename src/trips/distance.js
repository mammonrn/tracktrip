const EARTH_RADIUS_KM = 6371;
const MS_PER_HOUR = 60 * 60 * 1000;

/**
 * Thresholds for deciding whether the gap between two fixes is real riding or
 * GPS noise. Both are here, named and together, because they are balance
 * knobs: raising or lowering them changes everyone's lifetime total.
 */
export const JITTER = {
  /**
   * A phone's position wanders by a few metres even when the bike is parked.
   * At one fix every few seconds that drift alone would add kilometres to a
   * rider's total over a lunch stop, so segments shorter than this don't
   * count.
   *
   * The cost of raising it: genuine slow movement (walking the bike, heavy
   * traffic) stops being counted. At 10 m, anything above roughly 4 km/h
   * still registers on a 10-second poll. Set to 0 to count every segment.
   */
  MIN_DISTANCE_KM: 0.01,

  /**
   * A fix that lands far away far too quickly is a bad fix, not a fast rider
   * — a lost signal reacquired in the wrong place, or a tunnel exit. Counting
   * those is what inflates totals, so anything implying a speed above this is
   * dropped.
   */
  MAX_SPEED_KMH: 200,
};

const toRadians = (degrees) => (degrees * Math.PI) / 180;

/**
 * Great-circle distance between two { lat, lng } points, in kilometres.
 */
export function haversineKm(from, to) {
  const deltaLat = toRadians(to.lat - from.lat);
  const deltaLng = toRadians(to.lng - from.lng);
  const fromLat = toRadians(from.lat);
  const toLat = toRadians(to.lat);

  const a =
    Math.sin(deltaLat / 2) ** 2 +
    Math.cos(fromLat) * Math.cos(toLat) * Math.sin(deltaLng / 2) ** 2;

  return 2 * EARTH_RADIUS_KM * Math.asin(Math.min(1, Math.sqrt(a)));
}

/**
 * How much of the gap between a rider's previous fix and their new one counts
 * towards their lifetime total, in kilometres. Returns 0 for anything that
 * looks like noise rather than riding — the caller adds whatever comes back,
 * so a rejected segment simply contributes nothing.
 *
 * Rejected: the first fix of a trip (nothing to measure from), a hop too
 * short to distinguish from GPS drift, and one implying an impossible speed.
 * Also anything that didn't move forward in time — a fix bearing the same
 * timestamp is a correction to where the rider already was, and one bearing
 * an older timestamp is a late arrival that the caller is about to discard
 * anyway.
 */
export function countableDistanceKm(previous, next) {
  if (!previous) {
    return 0;
  }

  const km = haversineKm(previous, next);
  if (km < JITTER.MIN_DISTANCE_KM) {
    return 0;
  }

  const hours = (Date.parse(next.recorded_at) - Date.parse(previous.recorded_at)) / MS_PER_HOUR;
  // Also false when either timestamp is unparseable, which is the safe answer.
  if (!(hours > 0)) {
    return 0;
  }

  if (km / hours > JITTER.MAX_SPEED_KMH) {
    return 0;
  }

  return km;
}
