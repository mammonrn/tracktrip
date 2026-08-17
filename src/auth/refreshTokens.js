import crypto from 'node:crypto';

export const REFRESH_TOKEN_TTL_MS = 60 * 24 * 60 * 60 * 1000; // 60 days

export function generateRefreshToken() {
  return crypto.randomBytes(32).toString('hex');
}

export function hashRefreshToken(token) {
  return crypto.createHash('sha256').update(token).digest('hex');
}

export function issueRefreshToken(db, userId, now = new Date()) {
  const token = generateRefreshToken();
  const tokenHash = hashRefreshToken(token);
  const expiresAt = new Date(now.getTime() + REFRESH_TOKEN_TTL_MS).toISOString();
  db.prepare(
    'INSERT INTO refresh_tokens (user_id, token_hash, expires_at, created_at) VALUES (?, ?, ?, ?)'
  ).run(userId, tokenHash, expiresAt, now.toISOString());
  return token;
}

export function findRefreshToken(db, token) {
  return db.prepare('SELECT * FROM refresh_tokens WHERE token_hash = ?').get(hashRefreshToken(token));
}

export function revokeRefreshToken(db, id, now = new Date()) {
  db.prepare('UPDATE refresh_tokens SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL').run(
    now.toISOString(),
    id
  );
}

export function revokeAllRefreshTokensForUser(db, userId, now = new Date()) {
  db.prepare(
    'UPDATE refresh_tokens SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL'
  ).run(now.toISOString(), userId);
}

/**
 * Redeems a presented refresh token for a new one.
 * - Unknown token: 'invalid'.
 * - Already-revoked token presented again (reuse of a rotated/revoked token):
 *   revokes every outstanding token for that user and reports 'reused'.
 * - Expired-but-not-yet-revoked token: revoked and reported 'expired'.
 * - Otherwise: the presented token is revoked and a new one is issued ('ok').
 */
export function rotateRefreshToken(db, presentedToken, now = new Date()) {
  const record = findRefreshToken(db, presentedToken);
  if (!record) {
    return { status: 'invalid' };
  }
  if (record.revoked_at) {
    revokeAllRefreshTokensForUser(db, record.user_id, now);
    return { status: 'reused', userId: record.user_id };
  }
  if (new Date(record.expires_at).getTime() < now.getTime()) {
    revokeRefreshToken(db, record.id, now);
    return { status: 'expired' };
  }
  revokeRefreshToken(db, record.id, now);
  const token = issueRefreshToken(db, record.user_id, now);
  return { status: 'ok', userId: record.user_id, token };
}
