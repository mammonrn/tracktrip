import { verifyAccessToken } from './jwt.js';

export function requireAuth(db, config) {
  return (req, res, next) => {
    const header = req.headers.authorization || '';
    const [scheme, token] = header.split(' ');
    if (scheme !== 'Bearer' || !token) {
      return res.status(401).json({ error: 'unauthorized' });
    }

    let payload;
    try {
      payload = verifyAccessToken(token, config.jwtSecret);
    } catch {
      return res.status(401).json({ error: 'unauthorized' });
    }

    const user = db.prepare('SELECT * FROM users WHERE id = ?').get(payload.sub);
    if (!user) {
      return res.status(401).json({ error: 'unauthorized' });
    }

    req.user = user;
    next();
  };
}
