import http from 'node:http';
import { config } from './config.js';
import { openDb } from './db/index.js';
import { runMigrations } from './db/migrate.js';
import { createApp } from './app.js';
import { verifyGoogleIdToken } from './auth/google.js';
import { attachWebSocketServer } from './ws/index.js';
import { PositionHub } from './ws/hub.js';

const db = openDb();
runMigrations(db);

// The one thing the HTTP routes and the socket layer share: a position stored
// by a POST is announced here, and every socket watching that trip has it in
// the same tick.
const hub = new PositionHub();

const app = createApp({ db, config, verifyGoogleIdToken, hub });

const server = http.createServer(app);
attachWebSocketServer(server, { db, config, hub });

server.listen(config.port, config.host, () => {
  console.log(`trip-tracker listening on ${config.host}:${config.port}`);
});
