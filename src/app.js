import express from 'express';
import healthRouter from './routes/index.js';
import { createAuthRouter } from './routes/auth.js';
import { createMeRouter } from './routes/me.js';

export function createApp({ db, config, verifyGoogleIdToken }) {
  const app = express();
  app.set('trust proxy', 1);
  app.use(express.json());
  app.use(healthRouter);
  app.use(createAuthRouter({ db, config, verifyGoogleIdToken }));
  app.use(createMeRouter({ db, config }));
  return app;
}

export default createApp;
