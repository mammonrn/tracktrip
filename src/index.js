import http from 'node:http';
import express from 'express';
import { config } from './config.js';
import { openDb } from './db/index.js';
import { runMigrations } from './db/migrate.js';
import router from './routes/index.js';
import { attachWebSocketServer } from './ws/index.js';

const db = openDb();
runMigrations(db);

const app = express();
app.use(express.json());
app.use(router);

const server = http.createServer(app);
attachWebSocketServer(server);

server.listen(config.port, () => {
  console.log(`trip-tracker listening on port ${config.port}`);
});
