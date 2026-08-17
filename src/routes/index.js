import { Router } from 'express';

export const router = Router();

router.get('/health', (req, res) => {
  res.json({ status: 'ok' });
});

// Auth, profile, trip, invite, and waypoint routes are mounted in app.js.
// TODO: mount position routes here once implemented.

export default router;
