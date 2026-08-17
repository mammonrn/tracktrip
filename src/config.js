import 'dotenv/config';
import os from 'node:os';
import path from 'node:path';

/**
 * Where uploaded files land.
 *
 * Outside the repo by default, under the deploy user's home: a deploy replaces
 * the checkout, and anything stored inside it would go with the old copy. The
 * same path is what the nginx `location /uploads/` block in
 * deploy/nginx-api.ptrip.app.conf serves from, so the two have to agree.
 */
const defaultUploadsDir = path.join(os.homedir(), 'tracktrip', 'uploads');

export const config = {
  host: process.env.HOST || '127.0.0.1',
  port: Number(process.env.PORT || 4100),
  jwtSecret: process.env.JWT_SECRET,
  googleClientIds: (process.env.GOOGLE_CLIENT_ID || '')
    .split(',')
    .map((id) => id.trim())
    .filter(Boolean),
  dbPath: path.resolve(process.env.DB_PATH || './data/trip-tracker.db'),
  uploadsDir: path.resolve(process.env.UPLOADS_DIR || defaultUploadsDir),
  historyRetentionDays: Number(process.env.HISTORY_RETENTION_DAYS || 30),
};

export default config;
