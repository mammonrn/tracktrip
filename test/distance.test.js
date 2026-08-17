import test from 'node:test';
import assert from 'node:assert/strict';
import { haversineKm, countableDistanceKm, JITTER } from '../src/trips/distance.js';

// One degree of latitude is ~111.19 km anywhere on the globe.
const KM_PER_DEGREE_LAT = 111.19;

const at = (lat, lng, recorded_at = '2026-05-01T08:00:00.000Z') => ({ lat, lng, recorded_at });

/** A point `hours` after `from`, however far away the caller wants. */
const later = (point, hours) => ({
  ...point,
  recorded_at: new Date(Date.parse('2026-05-01T08:00:00.000Z') + hours * 3600 * 1000).toISOString(),
});

test('haversineKm measures a degree of latitude, in both directions', () => {
  const north = haversineKm({ lat: 0, lng: 0 }, { lat: 1, lng: 0 });
  assert.ok(Math.abs(north - KM_PER_DEGREE_LAT) < 0.5, `got ${north}`);

  // A degree of longitude is the same length only at the equator.
  const east = haversineKm({ lat: 0, lng: 0 }, { lat: 0, lng: 1 });
  assert.ok(Math.abs(east - KM_PER_DEGREE_LAT) < 0.5, `got ${east}`);

  // ...and shrinks with the cosine of the latitude: ~0.95 of that at 18°N.
  const eastInThailand = haversineKm({ lat: 18.79, lng: 98.0 }, { lat: 18.79, lng: 99.0 });
  assert.ok(Math.abs(eastInThailand - KM_PER_DEGREE_LAT * Math.cos((18.79 * Math.PI) / 180)) < 0.5);

  // Symmetric, and zero for a point against itself.
  assert.equal(
    haversineKm({ lat: 18.79, lng: 98.98 }, { lat: 19.1, lng: 99.2 }),
    haversineKm({ lat: 19.1, lng: 99.2 }, { lat: 18.79, lng: 98.98 })
  );
  assert.equal(haversineKm({ lat: 18.79, lng: 98.98 }, { lat: 18.79, lng: 98.98 }), 0);
});

test('haversineKm handles the antimeridian and the poles without blowing up', () => {
  // Two points either side of the date line are close, not a world apart.
  const acrossDateLine = haversineKm({ lat: 0, lng: 179.99 }, { lat: 0, lng: -179.99 });
  assert.ok(acrossDateLine < 5, `expected a short hop, got ${acrossDateLine}`);

  // The far side of the planet is half a circumference, not NaN.
  const antipodal = haversineKm({ lat: 0, lng: 0 }, { lat: 0, lng: 180 });
  assert.ok(Math.abs(antipodal - 20015) < 5, `got ${antipodal}`);
  assert.ok(Number.isFinite(haversineKm({ lat: 90, lng: 0 }, { lat: -90, lng: 0 })));
});

test('the first fix of a trip counts nothing — there is nothing to measure from', () => {
  assert.equal(countableDistanceKm(null, at(18.79, 98.98)), 0);
  assert.equal(countableDistanceKm(undefined, at(18.79, 98.98)), 0);
});

test('a plausible ride counts in full', () => {
  const from = at(18.79, 98.98);
  const to = later(at(19.1, 99.2), 1); // ~41 km in an hour

  const km = countableDistanceKm(from, to);
  assert.ok(km > 40 && km < 43, `got ${km}`);
  assert.equal(km, haversineKm(from, to), 'a good segment is credited in full');
});

test('GPS drift while parked is not counted', () => {
  const from = at(18.79, 98.98);
  // ~2 m away, 10 seconds later: a stationary phone wandering.
  const drift = later(at(18.790018, 98.98), 10 / 3600);

  const moved = haversineKm(from, drift);
  assert.ok(moved > 0, 'the points really are different');
  assert.ok(moved < JITTER.MIN_DISTANCE_KM, 'and closer together than the floor');
  assert.equal(countableDistanceKm(from, drift), 0);

  // Repeated drift must not accumulate either — each hop is judged alone.
  let total = 0;
  let previous = from;
  for (let i = 1; i <= 100; i += 1) {
    const next = later(at(18.79 + i * 0.000018, 98.98), (i * 10) / 3600);
    total += countableDistanceKm(previous, next);
    previous = next;
  }
  assert.equal(total, 0, '100 drifting fixes still add up to nothing');
});

test('a jump implying an impossible speed is not counted', () => {
  const from = at(18.79, 98.98);

  // ~41 km in one second: a lost signal reacquired in the wrong place.
  const teleport = later(at(19.1, 99.2), 1 / 3600);
  assert.equal(countableDistanceKm(from, teleport), 0);

  // Same jump over a plausible hour is fine, so it is the speed being
  // rejected and not the distance.
  assert.ok(countableDistanceKm(from, later(at(19.1, 99.2), 1)) > 40);
});

test('the speed limit is a ceiling, not a fence: at the limit still counts', () => {
  const from = at(18.79, 98.98);
  const target = { lat: 19.1, lng: 99.2 };
  const km = haversineKm(from, target);

  // Just inside the limit.
  const atLimit = later({ ...target, recorded_at: '' }, (km / JITTER.MAX_SPEED_KMH) * 1.001);
  assert.ok(countableDistanceKm(from, atLimit) > 0, 'just under the limit counts');

  // Twice the limit does not.
  const overLimit = later({ ...target, recorded_at: '' }, km / JITTER.MAX_SPEED_KMH / 2);
  assert.equal(countableDistanceKm(from, overLimit), 0);
});

test('fixes that did not move forward in time count nothing', () => {
  const from = at(18.79, 98.98);

  // Same instant: a correction to where the rider already was. Judging it by
  // speed would divide by zero.
  const sameInstant = { lat: 19.1, lng: 99.2, recorded_at: from.recorded_at };
  assert.equal(countableDistanceKm(from, sameInstant), 0);

  // Older: a late arrival, which the caller discards anyway.
  const older = later(at(19.1, 99.2), -1);
  assert.equal(countableDistanceKm(from, older), 0);
});

test('an unparseable timestamp counts nothing rather than NaN', () => {
  const from = at(18.79, 98.98);

  assert.equal(countableDistanceKm(from, { lat: 19.1, lng: 99.2, recorded_at: 'nonsense' }), 0);
  assert.equal(
    countableDistanceKm({ lat: 18.79, lng: 98.98, recorded_at: 'nonsense' }, later(at(19.1, 99.2), 1)),
    0
  );
});
