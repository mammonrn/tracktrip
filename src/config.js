import 'dotenv/config';
import path from 'node:path';

export const config = {
  port: Number(process.env.PORT || 4100),
  jwtSecret: process.env.JWT_SECRET,
  googleClientIds: (process.env.GOOGLE_CLIENT_ID || '')
    .split(',')
    .map((id) => id.trim())
    .filter(Boolean),
  dbPath: path.resolve(process.env.DB_PATH || './data/trip-tracker.db'),
  historyRetentionDays: Number(process.env.HISTORY_RETENTION_DAYS || 30),
};

export default config;
