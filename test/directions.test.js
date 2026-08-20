import test from 'node:test';
import assert from 'node:assert/strict';
import Database from 'better-sqlite3';
import supertest from 'supertest';
import { createApp } from '../src/app.js';
import { runMigrations, MIGRATIONS_DIR } from '../src/db/migrate.js';
import { signAccessToken } from '../src/auth/jwt.js';
import { GeocodeError } from '../src/geocode/locationiq.js';
import {
  LOCATIONIQ_DIRECTIONS_URL,
  MAX_ROUTE_POINTS,
  buildDirectionsUrl,
  capPoints,
  createLocationIqDirections,
  douglasPeucker,
  parseCoordinate,
  parseRoute,
  simplifyLine,
} from '../src/geocode/directions.js';

/**
 * A synthetic mountain road: a line that wanders either side of its own
 * straight chord, the way a road through hills does.
 *
 * `windiness` below — drawn length over straight-line length — is the number
 * this file cares about most. It is what a rider actually sees: a line whose
 * windiness has collapsed towards 1 is a straight line on the map, whatever
 * its vertex count says.
 */
function windingRoad(points) {
  return Array.from({ length: points }, (_, i) => {
    const t = i / (points - 1);
    return {
      lat: 18 + t * 1.2,
      // Bends at three scales, because a road has them at three scales: the
      // sweep round a range, the switchbacks up one hillside, and the kinks
      // between two of them. A single sine would be a road that any sampling
      // rate captures or misses cleanly, which is not the shape that made
      // this bug invisible in review and obvious on a motorcycle.
      lng:
        98 +
        t * 0.6 +
        Math.sin(t * 7) * 0.08 +
        Math.sin(t * 53) * 0.02 +
        Math.sin(t * 211) * 0.006,
    };
  });
}

function windiness(points) {
  let drawn = 0;
  for (let i = 0; i < points.length - 1; i += 1) {
    drawn += Math.hypot(
      points[i + 1].lng - points[i].lng,
      points[i + 1].lat - points[i].lat
    );
  }
  const first = points[0];
  const last = points[points.length - 1];
  return drawn / Math.hypot(last.lng - first.lng, last.lat - first.lat);
}

const JWT_SECRET = 'test-secret';

/**
 * Every test in this file mocks the upstream.
 *
 * The same rule the place-search tests follow, and for the same reason: the
 * free tier is 5,000 requests a day for the whole server, and a test suite
 * that spent any of it would be spending a rider's. Nothing here has a
 * LocationIQ key, and nothing here needs one.
 */
function setup({ routeBetween = null } = {}) {
  const db = new Database(':memory:');
  db.pragma('foreign_keys = ON');
  runMigrations(db, MIGRATIONS_DIR);

  const logged = [];
  const searchLogger = {
    log: (line) => logged.push(line),
    warn: (line) => logged.push(line),
  };

  const app = createApp({
    db,
    config: { jwtSecret: JWT_SECRET, googleClientIds: ['test-client-id'] },
    verifyGoogleIdToken: async () => {
      throw new Error('unused in these tests');
    },
    routeBetween,
    searchLogger,
  });

  const insertUser = db.prepare(
    'INSERT INTO users (google_sub, email, display_name) VALUES (?, ?, ?)'
  );
  const addUser = (name) =>
    Number(insertUser.run(`sub-${name}`, `${name}@gmail.com`, name).lastInsertRowid);

  return { db, app, addUser, logged, tokenFor: (id) => signAccessToken(id, JWT_SECRET) };
}

function fakeFetch(responses) {
  const calls = [];
  const queue = Array.isArray(responses) ? [...responses] : [responses];
  const fetchImpl = async (url, options) => {
    calls.push({ url, options });
    const next = queue.length > 1 ? queue.shift() : queue[0];
    if (typeof next === 'function') return next(url, options);
    return next;
  };
  fetchImpl.calls = calls;
  return fetchImpl;
}

function jsonResponse(body, status = 200) {
  return { ok: status >= 200 && status < 300, status, json: async () => body };
}

/** One upstream payload, in the shape LocationIQ actually sends it. */
const CHIANG_MAI_TO_PAI = {
  code: 'Ok',
  routes: [
    {
      distance: 134500,
      duration: 12600,
      geometry: {
        // GeoJSON: [longitude, latitude]. Chiang Mai, a bend, then Pai.
        coordinates: [
          [98.9853, 18.7883],
          [98.7, 19.0],
          [98.4406, 19.3583],
        ],
      },
    },
  ],
};

// --- the coordinates, before a request is ever spent -------------------------

test('a "lat,lng" query parameter is read, whitespace and all', () => {
  assert.deepEqual(parseCoordinate('18.7883,98.9853'), { lat: 18.7883, lng: 98.9853 });
  assert.deepEqual(parseCoordinate(' 18.7883 , 98.9853 '), { lat: 18.7883, lng: 98.9853 });
  assert.deepEqual(parseCoordinate('-33.5,-70.6'), { lat: -33.5, lng: -70.6 });
});

test('anything that is not a coordinate pair is refused', () => {
  assert.equal(parseCoordinate('18.7883'), null);
  assert.equal(parseCoordinate('18.7883,98.9853,5'), null);
  assert.equal(parseCoordinate('here,there'), null);
  assert.equal(parseCoordinate(''), null);
  assert.equal(parseCoordinate(undefined), null);
  assert.equal(parseCoordinate(18.7883), null);
});

test('a coordinate outside the planet is refused', () => {
  // A swapped pair is the realistic version of this: latitude 98 does not
  // exist, and the upstream would bill us for finding that out.
  assert.equal(parseCoordinate('98.9853,18.7883'), null);
  assert.equal(parseCoordinate('18.7883,198.9853'), null);
});

// --- the URL ----------------------------------------------------------------

test('the directions URL carries the key and asks for a drawable geometry', () => {
  const url = new URL(
    buildDirectionsUrl({
      apiKey: 'k-123',
      from: { lat: 18.7883, lng: 98.9853 },
      to: { lat: 19.3583, lng: 98.4406 },
    })
  );

  assert.ok(url.href.startsWith(LOCATIONIQ_DIRECTIONS_URL));
  assert.equal(url.searchParams.get('key'), 'k-123');
  // Without this the geometry arrives as an encoded polyline string, which
  // nothing downstream of here can read.
  assert.equal(url.searchParams.get('geometries'), 'geojson');
  // `full`, not `simplified` — see the comment in buildDirectionsUrl, and the
  // regression test below. `simplified` is an overview-grade geometry: 45
  // points for 130 km of mountain road, which draws as very nearly the
  // straight line this feature exists to replace.
  assert.equal(url.searchParams.get('overview'), 'full');
  // Phase one draws a line. Nothing reads a turn list, so nothing asks for one.
  assert.equal(url.searchParams.get('steps'), 'false');
  assert.equal(url.searchParams.get('alternatives'), 'false');
});

test('the path puts the coordinates in lng,lat order, semicolon separated', () => {
  const url = buildDirectionsUrl({
    apiKey: 'k',
    from: { lat: 18.7883, lng: 98.9853 },
    to: { lat: 19.3583, lng: 98.4406 },
  });

  // Latitude first would route through the sea off Somalia for most of
  // Thailand, and the answer would look plausible enough to ship.
  assert.ok(url.includes('/98.9853,18.7883;98.4406,19.3583?'), url);
});

// --- the payload ------------------------------------------------------------

test('a route becomes points in lat/lng order, kilometres and minutes', () => {
  const parsed = parseRoute(CHIANG_MAI_TO_PAI);

  assert.equal(parsed.points.length, 3);
  assert.deepEqual(parsed.points[0], { lat: 18.7883, lng: 98.9853 });
  assert.deepEqual(parsed.points[2], { lat: 19.3583, lng: 98.4406 });
  assert.equal(parsed.distance_km, 134.5);
  assert.equal(parsed.duration_min, 210);
});

test('a payload with no usable route is null rather than an error', () => {
  // "There is no road between these two points" is an answer — an island, a
  // coordinate in the sea — and the map draws a straight line for it.
  assert.equal(parseRoute(null), null);
  assert.equal(parseRoute({}), null);
  assert.equal(parseRoute({ routes: [] }), null);
  assert.equal(parseRoute({ routes: [{ geometry: { coordinates: [] } }] }), null);
  // One point is not a line.
  assert.equal(parseRoute({ routes: [{ geometry: { coordinates: [[98.9, 18.7]] } }] }), null);
});

test('a route missing its distance still draws', () => {
  const parsed = parseRoute({
    routes: [{ geometry: { coordinates: [[98.9, 18.7], [98.5, 19.3]] } }],
  });

  assert.equal(parsed.points.length, 2);
  // Null, not zero: the app falls back to the straight-line distance for the
  // number, and a zero would read as "you have arrived".
  assert.equal(parsed.distance_km, null);
  assert.equal(parsed.duration_min, null);
});

test('an unplaceable vertex is dropped, not passed on', () => {
  const parsed = parseRoute({
    routes: [
      {
        geometry: {
          coordinates: [[98.9, 18.7], ['x', 'y'], [200, 18.8], [98.5, 19.3]],
        },
      },
    ],
  });

  assert.equal(parsed.points.length, 2);
});

// --- thinning the line ------------------------------------------------------

test('a line shorter than the cap is left exactly as it is', () => {
  const points = [{ lat: 1, lng: 1 }, { lat: 2, lng: 2 }, { lat: 3, lng: 3 }];
  assert.deepEqual(capPoints(points, 10), points);
});

test('a long line is thinned but still starts and ends where the route does', () => {
  const points = Array.from({ length: 5000 }, (_, i) => ({ lat: i / 1000, lng: i / 1000 }));
  const capped = capPoints(points, MAX_ROUTE_POINTS);

  assert.equal(capped.length, MAX_ROUTE_POINTS);
  assert.deepEqual(capped[0], points[0]);
  // A line that stopped short of the finish would look like a routing bug.
  assert.deepEqual(capped[capped.length - 1], points[points.length - 1]);
});

test('a parsed route is thinned to something a phone can draw', () => {
  const road = windingRoad(4000);
  const coordinates = road.map((p) => [p.lng, p.lat]);
  const parsed = parseRoute({ routes: [{ distance: 1, duration: 1, geometry: { coordinates } }] });

  assert.ok(parsed.points.length <= MAX_ROUTE_POINTS);
  // And it is still a road, not a chord across one.
  assert.ok(windiness(parsed.points) > windiness(road) * 0.95);
});

test('a straight road is thinned to the two points that describe it', () => {
  // The old even-sampling kept 600 vertices on a dead-straight motorway.
  // Douglas-Peucker spends vertices where the bends are and nowhere else.
  const straight = Array.from({ length: 4000 }, (_, i) => ({ lat: 18 + i / 10000, lng: 98 + i / 10000 }));

  assert.equal(simplifyLine(straight).length, 2);
});

// --- keeping the bends ------------------------------------------------------

test('the bug: an overview-grade geometry is not a road', () => {
  // This is the shape of what shipped and what a rider saw. Forty-five points
  // across a road with forty bends in it cuts every one of them, and the
  // windiness collapses towards 1 — a straight line between the two ends.
  const road = windingRoad(4000);
  const overviewGrade = capPoints(road, 45);

  assert.ok(
    windiness(overviewGrade) < windiness(road) * 0.93,
    `an overview-grade line should have lost its bends, got ${windiness(overviewGrade)} of ${windiness(road)}`
  );
});

test('simplification keeps the road while the cap keeps the payload', () => {
  const road = windingRoad(4000);
  const simplified = simplifyLine(road);

  assert.ok(simplified.length <= MAX_ROUTE_POINTS, `${simplified.length} points`);
  // The number that matters: what is drawn still bends like the road does.
  assert.ok(
    windiness(simplified) > windiness(road) * 0.95,
    `kept ${windiness(simplified)} of ${windiness(road)}`
  );
});

test('simplification beats even sampling, on fewer points', () => {
  // At a budget tight enough for the difference to show: Douglas-Peucker
  // keeps more of the road *and* spends fewer vertices doing it, because it
  // puts them where the bends are instead of spreading them evenly over
  // straights. This is why capPoints is only the backstop.
  const road = windingRoad(4000);
  const simplified = simplifyLine(road, 60);
  const evenlySampled = capPoints(road, 60);

  assert.ok(simplified.length <= evenlySampled.length);
  assert.ok(
    windiness(simplified) > windiness(evenlySampled),
    `simplified ${windiness(simplified)} vs even ${windiness(evenlySampled)}`
  );
});

test('a short route is not thinned at all', () => {
  // A ride across town keeps every vertex the router gave it: the tolerance
  // starts tight and only loosens when the line does not fit.
  const short = windingRoad(120);

  assert.deepEqual(simplifyLine(short), short);
});

test('simplification always keeps both ends where the route put them', () => {
  const road = windingRoad(4000);
  const simplified = simplifyLine(road);

  assert.deepEqual(simplified[0], road[0]);
  assert.deepEqual(simplified[simplified.length - 1], road[road.length - 1]);
});

test('a looser tolerance never keeps more points than a tighter one', () => {
  const road = windingRoad(2000);
  let previous = Infinity;
  for (const tolerance of [0.00002, 0.00005, 0.0001, 0.0002, 0.001]) {
    const kept = douglasPeucker(road, tolerance).length;
    assert.ok(kept <= previous, `tolerance ${tolerance} kept ${kept}, previous kept ${previous}`);
    previous = kept;
  }
});

test('a line too short to simplify comes back untouched', () => {
  assert.deepEqual(douglasPeucker([], 0.001), []);
  assert.deepEqual(douglasPeucker([{ lat: 1, lng: 1 }], 0.001), [{ lat: 1, lng: 1 }]);
  const two = [{ lat: 1, lng: 1 }, { lat: 2, lng: 2 }];
  assert.deepEqual(douglasPeucker(two, 0.001), two);
});

test('a geometry with thousands of identical points cannot blow the stack', () => {
  // Iterative rather than recursive: a pathological upstream geometry must
  // return a bad line, not take the server down.
  const degenerate = Array.from({ length: 20000 }, () => ({ lat: 18, lng: 98 }));

  assert.ok(simplifyLine(degenerate).length <= MAX_ROUTE_POINTS);
});

// --- the upstream call ------------------------------------------------------

test('a route is fetched and parsed without touching the network', async () => {
  const fetchImpl = fakeFetch(jsonResponse(CHIANG_MAI_TO_PAI));
  const route = createLocationIqDirections({ apiKey: 'k-123', fetchImpl });

  const found = await route({ lat: 18.7883, lng: 98.9853 }, { lat: 19.3583, lng: 98.4406 });

  assert.equal(found.points.length, 3);
  assert.equal(fetchImpl.calls.length, 1);
  assert.ok(fetchImpl.calls[0].url.includes('key=k-123'));
});

test('a 404 from upstream means "no road", not a failure', async () => {
  const route = createLocationIqDirections({
    apiKey: 'k',
    fetchImpl: fakeFetch(jsonResponse({ error: 'Unable to find route' }, 404)),
  });

  assert.equal(await route({ lat: 0, lng: 0 }, { lat: 1, lng: 1 }), null);
});

test('a 200 carrying a refusal code means "no road" too', async () => {
  const route = createLocationIqDirections({
    apiKey: 'k',
    fetchImpl: fakeFetch(jsonResponse({ code: 'NoRoute', routes: [] })),
  });

  assert.equal(await route({ lat: 0, lng: 0 }, { lat: 1, lng: 1 }), null);
});

test('a bad key is reported as this server being unconfigured, not as a sign-in problem', async () => {
  const route = createLocationIqDirections({
    apiKey: 'wrong',
    fetchImpl: fakeFetch(jsonResponse({ error: 'Invalid key' }, 401)),
  });

  await assert.rejects(
    () => route({ lat: 0, lng: 0 }, { lat: 1, lng: 1 }),
    (e) => e instanceof GeocodeError && e.status === 503
  );
});

test('an exhausted quota is passed through as 429', async () => {
  const route = createLocationIqDirections({
    apiKey: 'k',
    fetchImpl: fakeFetch(jsonResponse({ error: 'Rate Limited' }, 429)),
  });

  await assert.rejects(
    () => route({ lat: 0, lng: 0 }, { lat: 1, lng: 1 }),
    (e) => e instanceof GeocodeError && e.status === 429
  );
});

test('an unreachable upstream is a 502, not a crash', async () => {
  const route = createLocationIqDirections({
    apiKey: 'k',
    fetchImpl: async () => {
      throw new Error('ENOTFOUND');
    },
  });

  await assert.rejects(
    () => route({ lat: 0, lng: 0 }, { lat: 1, lng: 1 }),
    (e) => e instanceof GeocodeError && e.status === 502
  );
});

test('no key configured is a 503 on use, not a throw at construction', async () => {
  const route = createLocationIqDirections({ apiKey: '' });

  await assert.rejects(
    () => route({ lat: 0, lng: 0 }, { lat: 1, lng: 1 }),
    (e) => e instanceof GeocodeError && e.status === 503
  );
});

// --- the route --------------------------------------------------------------

test('GET /directions answers with a route for a signed-in rider', async () => {
  const calls = [];
  const { app, addUser, tokenFor } = setup({
    routeBetween: async (from, to) => {
      calls.push({ from, to });
      return { points: [{ lat: 1, lng: 1 }, { lat: 2, lng: 2 }], distance_km: 12.5, duration_min: 20 };
    },
  });
  const rider = addUser('poom');

  const res = await supertest(app)
    .get('/directions?from=1,1&to=2,2')
    .set('Authorization', `Bearer ${tokenFor(rider)}`);

  assert.equal(res.status, 200);
  assert.equal(res.body.route.distance_km, 12.5);
  assert.equal(res.body.route.points.length, 2);
  assert.equal(res.body.cached, false);
  assert.deepEqual(calls, [{ from: { lat: 1, lng: 1 }, to: { lat: 2, lng: 2 } }]);
});

test('signing in is required — an open proxy is a quota anyone can empty', async () => {
  const { app } = setup({ routeBetween: async () => null });

  const res = await supertest(app).get('/directions?from=1,1&to=2,2');

  assert.equal(res.status, 401);
});

test('a malformed coordinate is a 400 and never reaches the upstream', async () => {
  let called = 0;
  const { app, addUser, tokenFor } = setup({
    routeBetween: async () => {
      called += 1;
      return null;
    },
  });
  const rider = addUser('poom');

  for (const query of ['?from=1,1', '?to=2,2', '?from=nope&to=2,2', '?from=1,1&to=98,181']) {
    const res = await supertest(app)
      .get(`/directions${query}`)
      .set('Authorization', `Bearer ${tokenFor(rider)}`);
    assert.equal(res.status, 400, query);
  }
  assert.equal(called, 0);
});

test('the second rider to ask for a trip\'s route is served from cache', async () => {
  let called = 0;
  const { app, addUser, tokenFor, logged } = setup({
    routeBetween: async () => {
      called += 1;
      return { points: [{ lat: 1, lng: 1 }, { lat: 2, lng: 2 }], distance_km: 5, duration_min: 9 };
    },
  });
  const first = addUser('poom');
  const second = addUser('nut');

  await supertest(app)
    .get('/directions?from=18.7883,98.9853&to=19.3583,98.4406')
    .set('Authorization', `Bearer ${tokenFor(first)}`);
  const res = await supertest(app)
    .get('/directions?from=18.7883,98.9853&to=19.3583,98.4406')
    .set('Authorization', `Bearer ${tokenFor(second)}`);

  assert.equal(res.status, 200);
  assert.equal(res.body.cached, true);
  // Eight riders on one trip must cost one request, not eight.
  assert.equal(called, 1);
  assert.ok(logged.some((line) => line.startsWith('directions: cache')));
});

test('coordinates a few metres apart share one cache entry', async () => {
  let called = 0;
  const { app, addUser, tokenFor } = setup({
    routeBetween: async () => {
      called += 1;
      return { points: [{ lat: 1, lng: 1 }, { lat: 2, lng: 2 }], distance_km: 5, duration_min: 9 };
    },
  });
  const rider = addUser('poom');

  await supertest(app)
    .get('/directions?from=18.78831,98.98531&to=19.35831,98.44061')
    .set('Authorization', `Bearer ${tokenFor(rider)}`);
  await supertest(app)
    .get('/directions?from=18.78834,98.98534&to=19.35834,98.44064')
    .set('Authorization', `Bearer ${tokenFor(rider)}`);

  // The fifth decimal place is GPS noise, not a different journey.
  assert.equal(called, 1);
});

test('"no road between these points" is cached too, and is a 200 with a null route', async () => {
  let called = 0;
  const { app, addUser, tokenFor } = setup({
    routeBetween: async () => {
      called += 1;
      return null;
    },
  });
  const rider = addUser('poom');

  const first = await supertest(app)
    .get('/directions?from=1,1&to=2,2')
    .set('Authorization', `Bearer ${tokenFor(rider)}`);
  const second = await supertest(app)
    .get('/directions?from=1,1&to=2,2')
    .set('Authorization', `Bearer ${tokenFor(rider)}`);

  assert.equal(first.status, 200);
  assert.equal(first.body.route, null);
  assert.equal(second.body.cached, true);
  assert.equal(called, 1);
});

test('a server with no key answers 503, and every other route still works', async () => {
  const { app, addUser, tokenFor, logged } = setup({ routeBetween: null });
  const rider = addUser('poom');

  const res = await supertest(app)
    .get('/directions?from=1,1&to=2,2')
    .set('Authorization', `Bearer ${tokenFor(rider)}`);

  assert.equal(res.status, 503);
  assert.ok(logged.some((line) => line.includes('unconfigured')));

  const health = await supertest(app).get('/health');
  assert.equal(health.status, 200);
});

test('an upstream failure is passed through with its own status, never a 404', async () => {
  const { app, addUser, tokenFor } = setup({
    routeBetween: async () => {
      throw new GeocodeError('Road routing is busy. Try again in a moment.', 429);
    },
  });
  const rider = addUser('poom');

  const res = await supertest(app)
    .get('/directions?from=1,1&to=2,2')
    .set('Authorization', `Bearer ${tokenFor(rider)}`);

  assert.equal(res.status, 429);
});

test('an unexpected upstream error becomes a 502 rather than a stack trace', async () => {
  const { app, addUser, tokenFor } = setup({
    routeBetween: async () => {
      throw new TypeError('something else entirely');
    },
  });
  const rider = addUser('poom');

  const res = await supertest(app)
    .get('/directions?from=1,1&to=2,2')
    .set('Authorization', `Bearer ${tokenFor(rider)}`);

  assert.equal(res.status, 502);
  assert.ok(res.body.error);
});

test('nothing on this route ever answers 404', async () => {
  // The app reads a 404 on this path as "this backend predates road routing"
  // and falls back silently. Any other 404 here would be read the same way and
  // would send somebody looking for a deploy that already happened.
  const { app, addUser, tokenFor } = setup({ routeBetween: async () => null });
  const rider = addUser('poom');
  const auth = { Authorization: `Bearer ${tokenFor(rider)}` };

  for (const path of [
    '/directions',
    '/directions?from=1,1',
    '/directions?from=1,1&to=2,2',
    '/directions?from=bad&to=worse',
  ]) {
    const res = await supertest(app).get(path).set(auth);
    assert.notEqual(res.status, 404, path);
  }
});
