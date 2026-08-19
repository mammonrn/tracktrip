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

/**
 * The accounts that get the super-user role, by email.
 *
 * A configured list rather than a database flag somebody sets by hand: the
 * answer to "who can see every trip on this server?" should be one line in an
 * environment file that a deploy can diff, not a row nobody remembers writing.
 * `syncSuperuserRoles` reconciles the users table with it at every boot — see
 * src/auth/roles.js for what that means when the list changes.
 *
 * Defaults to the first super user, so a fresh deploy is not locked out of its
 * own admin surface before anyone has set the variable.
 */
const defaultSuperuserEmails = 'krongkrangrn@gmail.com';

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
  /**
   * The LocationIQ key behind `GET /geocode/search`.
   *
   * Server-side only, and that is the point: an API key shipped inside an APK
   * is readable by anyone who downloads it, and this one meters a shared
   * 5,000-request-a-day quota. Empty is a supported state — the search route
   * answers 503 and every other route is unaffected.
   */
  locationIqApiKey: (process.env.LOCATIONIQ_API_KEY || '').trim(),
  /**
   * Optional comma-separated ISO country codes to bias searches towards, e.g.
   * `th`. Unset searches the whole planet, which is right for a group who ride
   * across a border and wrong for nobody.
   */
  locationIqCountryCodes: (process.env.LOCATIONIQ_COUNTRY_CODES || '').trim(),
  superuserEmails: (process.env.SUPERUSER_EMAILS ?? defaultSuperuserEmails)
    .split(',')
    .map((email) => email.trim())
    .filter(Boolean),
};

export default config;
