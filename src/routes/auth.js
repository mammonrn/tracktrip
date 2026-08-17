import { Router } from 'express';
import rateLimit from 'express-rate-limit';
import { upsertGoogleUser } from '../auth/users.js';
import { serializeUser } from '../auth/serializeUser.js';
import { signAccessToken } from '../auth/jwt.js';
import {
  issueRefreshToken,
  rotateRefreshToken,
  findRefreshToken,
  revokeRefreshToken,
} from '../auth/refreshTokens.js';

export function createAuthRouter({ db, config, verifyGoogleIdToken }) {
  const router = Router();

  // Scoped to '/auth' deliberately. Mounted without a path this runs for
  // every request that reaches this router — which, since the router is
  // mounted app-wide, meant /me, /trips and /positions all shared the sign-in
  // budget of 20 requests a minute. A client polling positions exhausted it
  // in well under a minute.
  router.use(
    '/auth',
    rateLimit({
      windowMs: 60 * 1000,
      max: 20,
      standardHeaders: true,
      legacyHeaders: false,
    })
  );

  router.post('/auth/google', async (req, res) => {
    const { idToken } = req.body || {};
    if (typeof idToken !== 'string' || !idToken) {
      return res.status(400).json({ error: 'idToken is required' });
    }

    let profile;
    try {
      profile = await verifyGoogleIdToken(idToken, config.googleClientIds);
    } catch {
      return res.status(401).json({ error: 'invalid_id_token' });
    }

    const user = upsertGoogleUser(db, profile);
    const accessToken = signAccessToken(user.id, config.jwtSecret);
    const refreshToken = issueRefreshToken(db, user.id);

    res.json({ accessToken, refreshToken, user: serializeUser(user) });
  });

  router.post('/auth/refresh', (req, res) => {
    const { refreshToken } = req.body || {};
    if (typeof refreshToken !== 'string' || !refreshToken) {
      return res.status(400).json({ error: 'refreshToken is required' });
    }

    const rotate = db.transaction((token) => rotateRefreshToken(db, token));
    const result = rotate(refreshToken);

    if (result.status !== 'ok') {
      return res.status(401).json({ error: 'invalid_refresh_token' });
    }

    const accessToken = signAccessToken(result.userId, config.jwtSecret);
    res.json({ accessToken, refreshToken: result.token });
  });

  router.post('/auth/logout', (req, res) => {
    const { refreshToken } = req.body || {};
    if (typeof refreshToken !== 'string' || !refreshToken) {
      return res.status(400).json({ error: 'refreshToken is required' });
    }

    const record = findRefreshToken(db, refreshToken);
    if (record && !record.revoked_at) {
      revokeRefreshToken(db, record.id);
    }

    res.status(204).end();
  });

  return router;
}

export default createAuthRouter;
