import { Router } from 'express';
import { requireAuth } from '../auth/middleware.js';
import { serializeUser } from '../auth/serializeUser.js';

const DISPLAY_NAME_MIN_LENGTH = 1;
const DISPLAY_NAME_MAX_LENGTH = 40;

export function createMeRouter({ db, config }) {
  const router = Router();
  router.use(requireAuth(db, config));

  router.get('/me', (req, res) => {
    res.json(serializeUser(req.user));
  });

  router.patch('/me', (req, res) => {
    const { display_name: displayName } = req.body || {};
    if (typeof displayName !== 'string') {
      return res.status(400).json({ error: 'display_name is required' });
    }

    const trimmed = displayName.trim();
    if (trimmed.length < DISPLAY_NAME_MIN_LENGTH || trimmed.length > DISPLAY_NAME_MAX_LENGTH) {
      return res.status(400).json({
        error: `display_name must be between ${DISPLAY_NAME_MIN_LENGTH} and ${DISPLAY_NAME_MAX_LENGTH} characters`,
      });
    }

    db.prepare('UPDATE users SET display_name = ?, updated_at = ? WHERE id = ?').run(
      trimmed,
      new Date().toISOString(),
      req.user.id
    );

    const updated = db.prepare('SELECT * FROM users WHERE id = ?').get(req.user.id);
    res.json(serializeUser(updated));
  });

  return router;
}

export default createMeRouter;
