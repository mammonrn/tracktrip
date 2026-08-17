import { Router } from 'express';

export const router = Router();

router.get('/health', (req, res) => {
  res.json({ status: 'ok' });
});

// TODO: mount auth, trips, and position routes here once implemented.

export default router;
