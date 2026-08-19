import express from 'express';
import healthRouter from './routes/index.js';
import { createAuthRouter } from './routes/auth.js';
import { createMeRouter } from './routes/me.js';
import { createTripsRouter } from './routes/trips.js';
import { createInvitesRouter } from './routes/invites.js';
import { createWaypointsRouter } from './routes/waypoints.js';
import { createPositionsRouter } from './routes/positions.js';
import { createSharingRouter } from './routes/sharing.js';
import { syncSuperuserRoles } from './auth/roles.js';
import { noopHub } from './ws/hub.js';

/**
 * [hub] is where a stored position is announced so that anyone watching that
 * trip over a WebSocket sees it at once. Optional: without one the app behaves
 * exactly as it did before sockets existed, which is what every test that does
 * not care about them relies on, and what a deployment with the socket layer
 * switched off would do.
 */
export function createApp({ db, config, verifyGoogleIdToken, hub = noopHub }) {
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
  return app;
}

export default createApp;
