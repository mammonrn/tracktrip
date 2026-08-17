import express from 'express';
import healthRouter from './routes/index.js';
import { createAuthRouter } from './routes/auth.js';
import { createMeRouter } from './routes/me.js';
import { createTripsRouter } from './routes/trips.js';
import { createInvitesRouter } from './routes/invites.js';
import { createWaypointsRouter } from './routes/waypoints.js';
import { createPositionsRouter } from './routes/positions.js';

export function createApp({ db, config, verifyGoogleIdToken }) {
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
  app.use(createPositionsRouter({ db, config }));
  return app;
}

export default createApp;
