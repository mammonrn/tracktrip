import test from 'node:test';
import assert from 'node:assert/strict';
import Database from 'better-sqlite3';
import supertest from 'supertest';
import { createApp } from '../src/app.js';
import { runMigrations, MIGRATIONS_DIR } from '../src/db/migrate.js';
import { signAccessToken } from '../src/auth/jwt.js';
import {
  GeocodeError,
  LOCATIONIQ_SEARCH_URL,
  MAX_LIMIT,
  biasViewbox,
  buildSearchUrl,
  createLocationIqSearch,
  normalizeBias,
  normalizeLimit,
  normalizeQuery,
  parsePlace,
  parseResults,
} from '../src/geocode/locationiq.js';
import { SearchCache } from '../src/geocode/cache.js';

const JWT_SECRET = 'test-secret';

/**
 * Every test in this file mocks the upstream.
 *
 * Not for speed: the free tier is 5,000 requests a day for the whole server,
 * and a test suite that spent any of it would be spending a rider's. Nothing
 * here has a LocationIQ key, and nothing here needs one.
 */
function setup({ searchPlaces = null } = {}) {
  const db = new Database(':memory:');
  db.pragma('foreign_keys = ON');
  runMigrations(db, MIGRATIONS_DIR);

  // Captured rather than printed, so the tests can assert what an operator
  // would see in `pm2 logs` — which is the only place the answer to "did it
  // actually call LocationIQ?" lives.
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
    searchPlaces,
    searchLogger,
  });

  const insertUser = db.prepare(
    'INSERT INTO users (google_sub, email, display_name) VALUES (?, ?, ?)'
  );
  const addUser = (name) =>
    Number(insertUser.run(`sub-${name}`, `${name}@gmail.com`, name).lastInsertRowid);

  return { db, app, addUser, logged, tokenFor: (id) => signAccessToken(id, JWT_SECRET) };
}

/** One upstream row, in the shape LocationIQ actually sends it. */
const PAI_ROW = {
  place_id: '3226',
  osm_id: 1234567,
  lat: '19.3583',
  lon: '98.4406',
  display_name: 'Pai, Mae Hong Son, Thailand',
  type: 'town',
  address: { name: 'Pai', state: 'Mae Hong Son', country: 'Thailand' },
};

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

// --- the query, before a request is ever spent ------------------------------

test('a query is trimmed and its whitespace collapsed', () => {
  assert.equal(normalizeQuery('  chiang   mai '), 'chiang mai');
  assert.equal(normalizeQuery('Pai'), 'Pai');
});

test('a query too short to be worth a request is refused', () => {
  // One character matches most of the planet. The answer would be useless and
  // the request is not free.
  assert.equal(normalizeQuery('a'), null);
  assert.equal(normalizeQuery('   '), null);
  assert.equal(normalizeQuery(''), null);
  assert.equal(normalizeQuery(undefined), null);
  assert.equal(normalizeQuery(42), null);
});

test('a query longer than a place name is refused', () => {
  assert.equal(normalizeQuery('x'.repeat(121)), null);
  assert.equal(normalizeQuery('x'.repeat(120)), 'x'.repeat(120));
});

test('a limit is clamped rather than passed through', () => {
  assert.equal(normalizeLimit(undefined), 8);
  assert.equal(normalizeLimit(''), 8);
  assert.equal(normalizeLimit('3'), 3);
  assert.equal(normalizeLimit('99'), MAX_LIMIT);
  assert.equal(normalizeLimit('0'), null);
  assert.equal(normalizeLimit('-2'), null);
  assert.equal(normalizeLimit('two'), null);
  assert.equal(normalizeLimit('2.5'), null);
});

// --- the URL ----------------------------------------------------------------

test('the search URL carries the key, the query and format=json', () => {
  const url = new URL(buildSearchUrl({ apiKey: 'k-123', query: 'Pai', limit: 5 }));

  assert.ok(url.href.startsWith(LOCATIONIQ_SEARCH_URL));
  assert.equal(url.searchParams.get('key'), 'k-123');
  assert.equal(url.searchParams.get('q'), 'Pai');
  assert.equal(url.searchParams.get('limit'), '5');
  // Without this the body comes back as XML and the parser sees nothing.
  assert.equal(url.searchParams.get('format'), 'json');
  assert.equal(url.searchParams.get('addressdetails'), '1');
  assert.equal(url.searchParams.get('countrycodes'), null);
});

test('a country bias is sent only when one is configured', () => {
  const url = new URL(
    buildSearchUrl({ apiKey: 'k', query: 'Pai', limit: 5, countryCodes: 'th' })
  );
  assert.equal(url.searchParams.get('countrycodes'), 'th');
});

// --- parsing ----------------------------------------------------------------

test('coordinates arrive as strings and come out as numbers', () => {
  // The single most likely thing to go wrong here: a client that trusted the
  // type would carry "19.3583" as a latitude and draw a marker nowhere.
  const place = parsePlace(PAI_ROW);

  assert.equal(place.lat, 19.3583);
  assert.equal(place.lng, 98.4406);
  assert.equal(typeof place.lat, 'number');
  assert.equal(typeof place.lng, 'number');
});

test('a place carries both a short name and the full address', () => {
  const place = parsePlace(PAI_ROW);

  assert.equal(place.name, 'Pai');
  assert.equal(place.address, 'Pai, Mae Hong Son, Thailand');
  assert.equal(place.kind, 'town');
  assert.equal(place.osm_id, '1234567');
});

test('a short name is taken from the address before it is guessed at', () => {
  // Splitting display_name on commas gets it wrong the moment a place name
  // has one in it, which is why addressdetails=1 is requested at all.
  const withComma = parsePlace({
    lat: '13.7563',
    lon: '100.5018',
    display_name: 'Wat Pho, Phra Nakhon, Bangkok, Thailand',
    address: { name: 'Wat Pho, Temple of the Reclining Buddha' },
  });

  assert.equal(withComma.name, 'Wat Pho, Temple of the Reclining Buddha');
});

test('a row with no address details still gets a name', () => {
  const place = parsePlace({
    lat: '19.3',
    lon: '98.4',
    display_name: 'Pai, Mae Hong Son, Thailand',
  });

  assert.equal(place.name, 'Pai');
  assert.equal(place.kind, null);
  assert.equal(place.osm_id, null);
});

test('a row that cannot be put on a map is dropped, not passed on', () => {
  // The caller's list is a list of things to tap. A row that goes nowhere is
  // a bug report waiting to be filed.
  assert.equal(parsePlace({ lat: 'not-a-number', lon: '98.4', display_name: 'x' }), null);
  assert.equal(parsePlace({ lat: '91', lon: '98.4', display_name: 'x' }), null);
  assert.equal(parsePlace({ lat: '19.3', lon: '181', display_name: 'x' }), null);
  assert.equal(parsePlace({ lat: '19.3', lon: '98.4', display_name: '' }), null);
  assert.equal(parsePlace(null), null);
  assert.equal(parsePlace('Pai'), null);
});

test('the good rows survive a payload with bad ones in it', () => {
  const results = parseResults([PAI_ROW, { lat: 'x', lon: 'y' }, null]);

  assert.equal(results.length, 1);
  assert.equal(results[0].name, 'Pai');
});

test('a payload that is not an array parses to nothing', () => {
  assert.deepEqual(parseResults({ error: 'Unable to geocode' }), []);
  assert.deepEqual(parseResults(null), []);
});

// --- the upstream call ------------------------------------------------------

test('a search fetches the built URL and returns parsed places', async () => {
  const fetchImpl = fakeFetch(jsonResponse([PAI_ROW]));
  const search = createLocationIqSearch({ apiKey: 'k-123', fetchImpl });

  const results = await search('Pai', { limit: 4 });

  assert.equal(results.length, 1);
  assert.equal(results[0].lat, 19.3583);
  assert.equal(fetchImpl.calls.length, 1);
  const url = new URL(fetchImpl.calls[0].url);
  assert.equal(url.searchParams.get('q'), 'Pai');
  assert.equal(url.searchParams.get('limit'), '4');
});

test('a search with no key configured fails as unconfigured, not as broken', async () => {
  const fetchImpl = fakeFetch(jsonResponse([PAI_ROW]));
  const search = createLocationIqSearch({ apiKey: '', fetchImpl });

  await assert.rejects(() => search('Pai'), (e) => {
    assert.ok(e instanceof GeocodeError);
    assert.equal(e.status, 503);
    return true;
  });
  // And nothing was sent: a request with no key is a guaranteed 401.
  assert.equal(fetchImpl.calls.length, 0);
});

test('a 404 from upstream is an empty result, not an error', async () => {
  // LocationIQ answers "nothing matched" with a 404 rather than an empty
  // array. Treating that as a failure would put an error over the map every
  // time somebody typed half a word.
  const search = createLocationIqSearch({
    apiKey: 'k',
    fetchImpl: fakeFetch(jsonResponse({ error: 'Unable to geocode' }, 404)),
  });

  assert.deepEqual(await search('zzzzzz'), []);
});

test('a rejected key reads as a server misconfiguration, never as a sign-in problem', async () => {
  // A 401 from LocationIQ means *this server's* key is wrong. Passing the
  // status through would tell the rider to sign in again, which would not
  // help and would be a lie.
  const search = createLocationIqSearch({
    apiKey: 'wrong',
    fetchImpl: fakeFetch(jsonResponse({ error: 'Invalid key' }, 401)),
  });

  await assert.rejects(() => search('Pai'), (e) => {
    assert.equal(e.status, 503);
    assert.match(e.message, /not configured/i);
    return true;
  });
});

test('an exhausted quota comes back as "busy", with its own status', async () => {
  const search = createLocationIqSearch({
    apiKey: 'k',
    fetchImpl: fakeFetch(jsonResponse({ error: 'Rate Limited' }, 429)),
  });

  await assert.rejects(() => search('Pai'), (e) => {
    assert.equal(e.status, 429);
    return true;
  });
});

test('a network failure is a geocode error, not an unhandled rejection', async () => {
  const search = createLocationIqSearch({
    apiKey: 'k',
    fetchImpl: async () => {
      throw new Error('ECONNREFUSED');
    },
  });

  await assert.rejects(() => search('Pai'), (e) => {
    assert.ok(e instanceof GeocodeError);
    assert.equal(e.status, 502);
    return true;
  });
});

test('a body that is not JSON is a geocode error', async () => {
  const search = createLocationIqSearch({
    apiKey: 'k',
    fetchImpl: fakeFetch({
      ok: true,
      status: 200,
      json: async () => {
        throw new Error('not json');
      },
    }),
  });

  await assert.rejects(() => search('Pai'), (e) => e.status === 502);
});

test('a 200 carrying an error key is an empty result', async () => {
  const search = createLocationIqSearch({
    apiKey: 'k',
    fetchImpl: fakeFetch(jsonResponse({ error: 'Unable to geocode' })),
  });

  assert.deepEqual(await search('zzz'), []);
});

// --- the cache --------------------------------------------------------------

test('a cached answer is returned until it expires', () => {
  let now = 1_000;
  const cache = new SearchCache({ ttlMs: 100, now: () => now });

  cache.set('pai', [PAI_ROW]);
  assert.deepEqual(cache.get('pai'), [PAI_ROW]);

  now += 99;
  assert.deepEqual(cache.get('pai'), [PAI_ROW]);

  now += 2;
  assert.equal(cache.get('pai'), undefined);
  assert.equal(cache.size, 0);
});

test('the cache evicts the least recently used entry, not the oldest read', () => {
  const cache = new SearchCache({ maxEntries: 2 });

  cache.set('a', [1]);
  cache.set('b', [2]);
  cache.get('a'); // 'a' is now the most recently used
  cache.set('c', [3]);

  assert.equal(cache.get('b'), undefined);
  assert.deepEqual(cache.get('a'), [1]);
  assert.deepEqual(cache.get('c'), [3]);
});

// --- the route --------------------------------------------------------------

test('a search returns places to a signed-in rider', async () => {
  const { app, addUser, tokenFor } = setup({
    searchPlaces: async () => [
      { name: 'Pai', address: 'Pai, Mae Hong Son, Thailand', lat: 19.3583, lng: 98.4406, kind: 'town', osm_id: '1' },
    ],
  });
  const rider = addUser('rider');

  const res = await supertest(app)
    .get('/geocode/search?q=Pai')
    .set('Authorization', `Bearer ${tokenFor(rider)}`);

  assert.equal(res.status, 200);
  assert.equal(res.body.query, 'Pai');
  assert.equal(res.body.results.length, 1);
  assert.equal(res.body.results[0].lat, 19.3583);
  assert.equal(res.body.cached, false);
});

test('a search without a token is refused', async () => {
  // An open proxy in front of a metered key is a quota anyone on the internet
  // can empty in an afternoon.
  let called = 0;
  const { app } = setup({
    searchPlaces: async () => {
      called += 1;
      return [];
    },
  });

  const res = await supertest(app).get('/geocode/search?q=Pai');

  assert.equal(res.status, 401);
  assert.equal(called, 0);
});

test('a query too short never reaches the upstream', async () => {
  let called = 0;
  const { app, addUser, tokenFor } = setup({
    searchPlaces: async () => {
      called += 1;
      return [];
    },
  });
  const rider = addUser('rider');

  const res = await supertest(app)
    .get('/geocode/search?q=a')
    .set('Authorization', `Bearer ${tokenFor(rider)}`);

  assert.equal(res.status, 400);
  assert.equal(called, 0);
});

test('a bad limit is refused before a request is spent', async () => {
  let called = 0;
  const { app, addUser, tokenFor } = setup({
    searchPlaces: async () => {
      called += 1;
      return [];
    },
  });
  const rider = addUser('rider');

  const res = await supertest(app)
    .get('/geocode/search?q=Pai&limit=0')
    .set('Authorization', `Bearer ${tokenFor(rider)}`);

  assert.equal(res.status, 400);
  assert.equal(called, 0);
});

test('the same search twice costs one upstream request', async () => {
  // The debounce in the app stops one rider's typing costing ten requests;
  // this is what stops ten riders' typing costing ten.
  let called = 0;
  const { app, addUser, tokenFor } = setup({
    searchPlaces: async () => {
      called += 1;
      return [{ name: 'Pai', address: 'Pai', lat: 19.3583, lng: 98.4406, kind: null, osm_id: null }];
    },
  });
  const one = addUser('one');
  const two = addUser('two');

  const first = await supertest(app)
    .get('/geocode/search?q=Pai')
    .set('Authorization', `Bearer ${tokenFor(one)}`);
  // Different rider, different casing, extra spaces — the same search.
  const second = await supertest(app)
    .get('/geocode/search?q=%20%20pai%20')
    .set('Authorization', `Bearer ${tokenFor(two)}`);

  assert.equal(called, 1);
  assert.equal(first.body.cached, false);
  assert.equal(second.body.cached, true);
  assert.deepEqual(second.body.results, first.body.results);
});

test('an empty answer is cached too', async () => {
  let called = 0;
  const { app, addUser, tokenFor } = setup({
    searchPlaces: async () => {
      called += 1;
      return [];
    },
  });
  const rider = addUser('rider');
  const auth = `Bearer ${tokenFor(rider)}`;

  await supertest(app).get('/geocode/search?q=zzzzz').set('Authorization', auth);
  const second = await supertest(app).get('/geocode/search?q=zzzzz').set('Authorization', auth);

  assert.equal(called, 1);
  assert.equal(second.status, 200);
  assert.deepEqual(second.body.results, []);
});

test('a server with no key configured says so rather than 404ing the route', async () => {
  // A 404 would read to the app as "this build is too old", which is a
  // different problem with a different fix.
  const { app, addUser, tokenFor } = setup({ searchPlaces: null });
  const rider = addUser('rider');

  const res = await supertest(app)
    .get('/geocode/search?q=Pai')
    .set('Authorization', `Bearer ${tokenFor(rider)}`);

  assert.equal(res.status, 503);
  assert.match(res.body.error, /not configured/i);
});

test('an upstream failure keeps its status and its message', async () => {
  const { app, addUser, tokenFor } = setup({
    searchPlaces: async () => {
      throw new GeocodeError('Place search is busy. Try again in a moment.', 429);
    },
  });
  const rider = addUser('rider');

  const res = await supertest(app)
    .get('/geocode/search?q=Pai')
    .set('Authorization', `Bearer ${tokenFor(rider)}`);

  assert.equal(res.status, 429);
  assert.match(res.body.error, /busy/i);
});

test('an unexpected throw becomes a 502, not a 500 stack trace', async () => {
  const { app, addUser, tokenFor } = setup({
    searchPlaces: async () => {
      throw new TypeError('something the geocoder did not expect');
    },
  });
  const rider = addUser('rider');

  const res = await supertest(app)
    .get('/geocode/search?q=Pai')
    .set('Authorization', `Bearer ${tokenFor(rider)}`);

  assert.equal(res.status, 502);
  assert.ok(res.body.error);
});

test('a rider hammering the search is throttled, and the throttle is per rider', async () => {
  let called = 0;
  const { app, addUser, tokenFor } = setup({
    searchPlaces: async () => {
      called += 1;
      return [];
    },
  });
  const noisy = addUser('noisy');
  const quiet = addUser('quiet');
  const noisyAuth = `Bearer ${tokenFor(noisy)}`;

  let lastStatus = 200;
  // Distinct queries so the cache cannot answer any of them.
  for (let i = 0; i < 14; i += 1) {
    const res = await supertest(app)
      .get(`/geocode/search?q=place-${i}`)
      .set('Authorization', noisyAuth);
    lastStatus = res.status;
  }
  assert.equal(lastStatus, 429);

  // A group riding together is often behind one carrier NAT. An IP-keyed
  // bucket would have them throttle each other.
  const other = await supertest(app)
    .get('/geocode/search?q=somewhere-else')
    .set('Authorization', `Bearer ${tokenFor(quiet)}`);
  assert.equal(other.status, 200);
  assert.ok(called <= 13);
});

// --- what an operator can see ----------------------------------------------

test('every search says in the log whether it went upstream or came from cache', async () => {
  // The bug this answers: "search is broken" looked identical from the phone
  // whether the server called LocationIQ and got nothing, never called it, or
  // had no search route at all. Two of those three are invisible without this.
  const { app, addUser, tokenFor, logged } = setup({
    searchPlaces: async () => [
      { name: 'Pai', address: 'Pai', lat: 19.3583, lng: 98.4406, kind: null, osm_id: null },
    ],
  });
  const auth = `Bearer ${tokenFor(addUser('rider'))}`;

  await supertest(app).get('/geocode/search?q=Pai').set('Authorization', auth);
  await supertest(app).get('/geocode/search?q=Pai').set('Authorization', auth);

  assert.equal(logged.length, 2);
  assert.match(logged[0], /^geocode: upstream q="Pai" 1 result/);
  assert.match(logged[1], /^geocode: cache q="Pai" 1 result/);
});

test('a server with no key says so in the log, every time it is asked', async () => {
  const { app, addUser, tokenFor, logged } = setup({ searchPlaces: null });
  const auth = `Bearer ${tokenFor(addUser('rider'))}`;

  await supertest(app).get('/geocode/search?q=Pai').set('Authorization', auth);

  assert.match(logged[0], /^geocode: unconfigured q="Pai" LOCATIONIQ_API_KEY is not set/);
});

test('a failed search logs the status it failed with', async () => {
  const { app, addUser, tokenFor, logged } = setup({
    searchPlaces: async () => {
      throw new GeocodeError('Place search is busy. Try again in a moment.', 429);
    },
  });
  const auth = `Bearer ${tokenFor(addUser('rider'))}`;

  await supertest(app).get('/geocode/search?q=Pai').set('Authorization', auth);

  assert.match(logged[0], /^geocode: failed q="Pai" 429 /);
});

test('the log never carries the API key', async () => {
  // The line exists to be pasted into a chat window when something is wrong.
  const { app, addUser, tokenFor, logged } = setup({
    searchPlaces: async () => {
      throw new GeocodeError('upstream said no', 502);
    },
  });
  const auth = `Bearer ${tokenFor(addUser('rider'))}`;

  await supertest(app).get('/geocode/search?q=Pai').set('Authorization', auth);

  assert.equal(logged.join('\n').includes('key='), false);
});

test('the search route never answers 404, so a 404 can only mean it is absent', async () => {
  // This is the contract the app leans on to tell "no such place" apart from
  // "this server has no place search" — the confusion that had three real
  // cities reported as "That's no longer there.". Nothing below may 404.
  const { app, addUser, tokenFor } = setup({ searchPlaces: async () => [] });
  const auth = `Bearer ${tokenFor(addUser('rider'))}`;

  for (const url of [
    '/geocode/search?q=zzzzzzzz',   // nothing matched
    '/geocode/search?q=a',          // too short
    '/geocode/search?q=Pai&limit=0' // bad limit
  ]) {
    const res = await supertest(app).get(url).set('Authorization', auth);
    assert.notEqual(res.status, 404, `${url} must not answer 404`);
  }
});

/* ------------------------------------------------------------------ *
 * Biasing results towards where the rider is
 *
 * A place name typed in Thai used to compete with the whole planet on string
 * relevance alone, so a well-known local landmark could lose to a better match
 * six thousand kilometres away. The rider's own position is a hint the app has
 * had all along and never sent.
 * ------------------------------------------------------------------ */

test('a bias point is rounded to about eleven kilometres', () => {
  // Rounded for the cache, not for the geocoder: unrounded, every rider would
  // get their own entry and one upstream request per search would become eight.
  assert.deepEqual(normalizeBias('18.78831,98.98531'), { lat: 18.8, lng: 99.0 });
  assert.deepEqual(normalizeBias(' 13.7563 , 100.5018 '), { lat: 13.8, lng: 100.5 });
});

test('no bias, or an unusable one, searches the way it always did', () => {
  for (const raw of [undefined, null, '', 'nope', '1', '91,0', '0,181', '1,2,3']) {
    assert.equal(normalizeBias(raw), null, String(raw));
  }
});

test('the viewbox is a box around the rider, lng first', () => {
  const box = biasViewbox({ lat: 0, lng: 0 }, 111.195);
  const [minLng, minLat, maxLng, maxLat] = box.split(',').map(Number);

  // lon,lat,lon,lat — the order inherited from Nominatim, and the single most
  // likely thing to be silently wrong here.
  assert.ok(minLng < 0 && maxLng > 0 && minLat < 0 && maxLat > 0);
  assert.ok(Math.abs(minLat + 1) < 0.01, `minLat ${minLat}`);
  assert.ok(Math.abs(maxLat - 1) < 0.01, `maxLat ${maxLat}`);
});

test('the box is square on the ground, not square in degrees', () => {
  // At Thailand's latitude a degree of longitude is about 5% shorter than a
  // degree of latitude, so a box built without the cosine would be narrower on
  // the ground than it looks.
  const box = biasViewbox({ lat: 18.8, lng: 99.0 });
  const [minLng, minLat, maxLng, maxLat] = box.split(',').map(Number);

  assert.ok(maxLng - minLng > maxLat - minLat, 'longitude span must be the wider one');
});

test('a box near the pole stays on the planet', () => {
  const box = biasViewbox({ lat: 89.9, lng: 179.9 });
  const [minLng, minLat, maxLng, maxLat] = box.split(',').map(Number);

  assert.ok(minLat >= -90 && maxLat <= 90, box);
  assert.ok(minLng >= -180 && maxLng <= 180, box);
});

test('the search URL carries the box as a preference, never a fence', () => {
  const url = new URL(
    buildSearchUrl({ apiKey: 'k', query: 'วัดร่องขุ่น', limit: 8, bias: { lat: 18.8, lng: 99.0 } })
  );

  assert.ok(url.searchParams.get('viewbox'));
  // bounded=0 is the whole difference between a bias and a filter: somewhere
  // across the country must still be findable, just ranked lower.
  assert.equal(url.searchParams.get('bounded'), '0');
  // And the query itself is untouched — Thai reaches the upstream as typed.
  assert.equal(url.searchParams.get('q'), 'วัดร่องขุ่น');
});

test('no bias adds no viewbox at all', () => {
  const url = new URL(buildSearchUrl({ apiKey: 'k', query: 'Pai', limit: 8 }));

  assert.equal(url.searchParams.get('viewbox'), null);
  assert.equal(url.searchParams.get('bounded'), null);
});

test('GET /geocode/search passes the rider position through as a bias', async () => {
  const calls = [];
  const { app, addUser, tokenFor } = setup({
    searchPlaces: async (query, options) => {
      calls.push({ query, options });
      return [];
    },
  });
  const rider = addUser('poom');

  await supertest(app)
    .get('/geocode/search?q=' + encodeURIComponent('วัดร่องขุ่น') + '&near=19.82,99.76')
    .set('Authorization', `Bearer ${tokenFor(rider)}`);

  assert.equal(calls.length, 1);
  assert.equal(calls[0].query, 'วัดร่องขุ่น');
  assert.deepEqual(calls[0].options.bias, { lat: 19.8, lng: 99.8 });
});

test('riders in different provinces do not share a cached ordering', async () => {
  let called = 0;
  const { app, addUser, tokenFor } = setup({
    searchPlaces: async () => {
      called += 1;
      return [];
    },
  });
  const rider = addUser('poom');
  const auth = { Authorization: `Bearer ${tokenFor(rider)}` };

  await supertest(app).get('/geocode/search?q=market&near=18.79,98.99').set(auth);
  await supertest(app).get('/geocode/search?q=market&near=13.75,100.50').set(auth);

  // The bias is part of the answer, so serving one rider the other's ordering
  // would be the wrong answer served fast.
  assert.equal(called, 2);
});

test('a group riding together still shares one entry and one request', async () => {
  let called = 0;
  const { app, addUser, tokenFor } = setup({
    searchPlaces: async () => {
      called += 1;
      return [];
    },
  });
  const first = addUser('poom');
  const second = addUser('nut');

  // A few kilometres apart, which the rounding folds into one bias point.
  await supertest(app)
    .get('/geocode/search?q=market&near=18.7883,98.9853')
    .set('Authorization', `Bearer ${tokenFor(first)}`);
  const res = await supertest(app)
    .get('/geocode/search?q=market&near=18.8102,99.0041')
    .set('Authorization', `Bearer ${tokenFor(second)}`);

  assert.equal(res.body.cached, true);
  assert.equal(called, 1);
});

test('an unbiased search and a biased one are different questions', async () => {
  let called = 0;
  const { app, addUser, tokenFor } = setup({
    searchPlaces: async () => {
      called += 1;
      return [];
    },
  });
  const rider = addUser('poom');
  const auth = { Authorization: `Bearer ${tokenFor(rider)}` };

  await supertest(app).get('/geocode/search?q=market').set(auth);
  await supertest(app).get('/geocode/search?q=market&near=18.79,98.99').set(auth);

  assert.equal(called, 2);
});

test('a rider with no fix searches unbiased rather than failing', async () => {
  const calls = [];
  const { app, addUser, tokenFor } = setup({
    searchPlaces: async (query, options) => {
      calls.push(options);
      return [];
    },
  });
  const rider = addUser('poom');

  const res = await supertest(app)
    .get('/geocode/search?q=Pai&near=not-a-coordinate')
    .set('Authorization', `Bearer ${tokenFor(rider)}`);

  // A phone with no fix, or a garbled one, must not turn a working search into
  // a 400 — the bias is a hint, and a missing hint is not an error.
  assert.equal(res.status, 200);
  assert.equal(calls[0].bias, null);
});
