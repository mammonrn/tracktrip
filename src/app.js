import express from 'express';
import healthRouter from './routes/index.js';
import { createAuthRouter } from './routes/auth.js';
import { createMeRouter } from './routes/me.js';
import { createTripsRouter } from './routes/trips.js';
import { createInvitesRouter } from './routes/invites.js';
import { createWaypointsRouter } from './routes/waypoints.js';
import { createPositionsRouter } from './routes/positions.js';
import { createSharingRouter } from './routes/sharing.js';
import { createGeocodeRouter } from './routes/geocode.js';
import { createPlacesRouter } from './routes/places.js';
import { createDirectionsRouter } from './routes/directions.js';
import { syncSuperuserRoles } from './auth/roles.js';
import { noopHub } from './ws/hub.js';

/**
 * [hub] is where a stored position is announced so that anyone watching that
 * trip over a WebSocket sees it at once. Optional: without one the app behaves
 * exactly as it did before sockets existed, which is what every test that does
 * not care about them relies on, and what a deployment with the socket layer
 * switched off would do.
 *
 * [searchPlaces] is the forward geocoder behind `GET /geocode/search`. Also
 * optional, and for a similar reason: a server with no LOCATIONIQ_API_KEY set
 * still runs, and that one route answers 503 saying so rather than the whole
 * app refusing to boot over a feature nobody on it is using yet.
 *
 * [routeBetween] is the road router behind `GET /directions`, on the same key
 * and optional for the same reason. A server without it makes the map draw the
 * straight line it drew before road routing existed, which is why the app
 * treats its absence as a fallback rather than as a failure.
 */
export function createApp({
  db,
  config,
  verifyGoogleIdToken,
  hub = noopHub,
  searchPlaces = null,
  routeBetween = null,
  // Where the place-search and routing routes write their one line per call.
  // Injected only so the tests can read it back instead of printing it.
  searchLogger = console,
}) {
  // Before the first request, so no route can be served by a process whose
  // idea of who is a super user is older than its configuration. Cheap: one
  // scan of a table that has as many rows as the app has riders.
  syncSuperuserRoles(db, config.superuserEmails ?? []);

  const app = express();
  // nginx runs on the same host and connects over loopback, so trusting
  // only loopback addresses is enough for req.ip / X-Forwarded-For to
  // reflect the real client IP (needed for the /auth/* rate limiter).
  app.set('trust proxy', 'loopback');
  app.use(express.json());
  app.use(healthRouter);
  app.use(createAuthRouter({ db, config, verifyGoogleIdToken }));
  app.use(createMeRouter({ db, config }));
  app.use(createTripsRouter({ db, config }));
  app.use(createInvitesRouter({ db, config }));
  app.use(createWaypointsRouter({ db, config }));
  app.use(createPositionsRouter({ db, config, hub }));
  app.use(createSharingRouter({ db, config }));
  app.use(createGeocodeRouter({ db, config, search: searchPlaces, logger: searchLogger }));
  // The riders' own places, which need no API key and answer when the
  // geocoder above cannot — see src/routes/places.js.
  app.use(createPlacesRouter({ db, config }));
  app.use(createDirectionsRouter({ db, config, route: routeBetween, logger: searchLogger }));
  return app;
}

export default createApp;
