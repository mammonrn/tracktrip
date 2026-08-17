import 'dotenv/config';
import path from 'node:path';

export const config = {
  port: Number(process.env.PORT || 4100),
  jwtSecret: process.env.JWT_SECRET,
  googleClientId: process.env.GOOGLE_CLIENT_ID,
  dbPath: path.resolve(process.env.DB_PATH || './data/trip-tracker.db'),
  historyRetentionDays: Number(process.env.HISTORY_RETENTION_DAYS || 30),
};

export default config;
