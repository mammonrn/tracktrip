import http from 'node:http';
import { config } from './config.js';
import { openDb } from './db/index.js';
import { runMigrations } from './db/migrate.js';
import { createApp } from './app.js';
import { verifyGoogleIdToken } from './auth/google.js';
import { attachWebSocketServer } from './ws/index.js';
import { PositionHub } from './ws/hub.js';
import { createLocationIqSearch } from './geocode/locationiq.js';
import { createLocationIqDirections } from './geocode/directions.js';

const db = openDb();
runMigrations(db);

// The one thing the HTTP routes and the socket layer share: a position stored
// by a POST is announced here, and every socket watching that trip has it in
// the same tick.
const hub = new PositionHub();

// Built even without a key: the factory throws a 503-shaped error on use
// rather than at construction, so the route can say "not configured here"
// instead of the server refusing to start over a feature that is optional.
const searchPlaces = createLocationIqSearch({
  apiKey: config.locationIqApiKey,
  countryCodes: config.locationIqCountryCodes || undefined,
});

// The same key, the same reasoning: built without one so the server still
// boots, and the one route that needs it answers 503 on use.
const routeBetween = createLocationIqDirections({ apiKey: config.locationIqApiKey });

if (!config.locationIqApiKey) {
  console.warn(
    'LOCATIONIQ_API_KEY is not set — GET /geocode/search and GET /directions will answer 503.'
  );
}

const app = createApp({ db, config, verifyGoogleIdToken, hub, searchPlaces, routeBetween });

const server = http.createServer(app);
attachWebSocketServer(server, { db, config, hub });

server.listen(config.port, config.host, () => {
  console.log(`trip-tracker listening on ${config.host}:${config.port}`);
});
