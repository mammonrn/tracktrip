import jwt from 'jsonwebtoken';

export const ACCESS_TOKEN_TTL_SECONDS = 60 * 60; // 1 hour

export function signAccessToken(userId, secret) {
  return jwt.sign({ sub: userId }, secret, { expiresIn: ACCESS_TOKEN_TTL_SECONDS });
}

export function verifyAccessToken(token, secret) {
  return jwt.verify(token, secret);
}
