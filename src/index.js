import http from 'node:http';
import { config } from './config.js';
import { openDb } from './db/index.js';
import { runMigrations } from './db/migrate.js';
import { createApp } from './app.js';
import { verifyGoogleIdToken } from './auth/google.js';
import { attachWebSocketServer } from './ws/index.js';

const db = openDb();
runMigrations(db);

const app = createApp({ db, config, verifyGoogleIdToken });

const server = http.createServer(app);
attachWebSocketServer(server);

server.listen(config.port, () => {
  console.log(`trip-tracker listening on port ${config.port}`);
});
