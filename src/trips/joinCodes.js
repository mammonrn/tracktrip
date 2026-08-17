import { randomBytes } from 'node:crypto';

/**
 * Join codes: the thing behind the QR a rider holds up at a petrol station so
 * the person next to them can get on the trip without spelling out an email
 * address.
 */

/**
 * 32 characters, no `I`, `O`, `0` or `1`.
 *
 * The ambiguous pairs are dropped because a code that can be read off a screen
 * by hand will be, and "was that a zero or an oh" is a support question rather
 * than a feature. 32 is also exactly 256/8, which is what lets the byte-to-
 * character step below stay unbiased.
 */
export const CODE_ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';

/** 8 characters over a 32-symbol alphabet: 40 bits. */
export const CODE_LENGTH = 8;

/** How long a code stays good for. */
export const CODE_TTL_MINUTES = 15;

const MS_PER_MINUTE = 60 * 1000;

/**
 * A random code.
 *
 * `randomBytes`, not `Math.random`: this is the only thing standing between a
 * stranger and someone's live location, so it has to come from a CSPRNG. The
 * modulo is safe precisely because 256 divides evenly by the alphabet length —
 * with 30 or 36 characters it would quietly favour the first few.
 */
export function generateJoinCode() {
  const bytes = randomBytes(CODE_LENGTH);
  let code = '';
  for (const byte of bytes) {
    code += CODE_ALPHABET[byte % CODE_ALPHABET.length];
  }
  return code;
}

/** When a code issued now lapses. */
export function joinCodeExpiry(now = new Date()) {
  return new Date(now.getTime() + CODE_TTL_MINUTES * MS_PER_MINUTE).toISOString();
}

/**
 * Puts a scanned or typed code into the form the table stores.
 *
 * Upper-cased, and with spaces and dashes dropped, so a code read aloud and
 * typed back in as "abcd-efgh" still finds its row. Returns null for anything
 * that isn't the right shape — which keeps malformed input from reaching the
 * lookup at all.
 */
export function normalizeJoinCode(value) {
  if (typeof value !== 'string') {
    return null;
  }
  const cleaned = value.trim().toUpperCase().replace(/[\s-]/g, '');
  if (cleaned.length !== CODE_LENGTH) {
    return null;
  }
  for (const character of cleaned) {
    if (!CODE_ALPHABET.includes(character)) {
      return null;
    }
  }
  return cleaned;
}

/** Whether a `trip_join_codes` row is still redeemable. */
export function isJoinCodeLive(row, nowIso = new Date().toISOString()) {
  return Boolean(row) && row.expires_at > nowIso;
}

/** Shapes a join code for API responses. The code itself is the payload. */
export function serializeJoinCode(row) {
  return {
    trip_id: row.trip_id,
    code: row.code,
    expires_at: row.expires_at,
  };
}
