const EMAIL_MAX_LENGTH = 254;
// Deliberately loose: one @, no spaces, a dot in the domain. Anything
// stricter starts rejecting addresses that are actually deliverable, and the
// real check is that the invitee signs in with this address via Google.
const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

/**
 * Trims and lowercases an email so invites dedupe and match reliably —
 * Google hands us whatever casing the account was typed with, and an invite
 * to `Rider@Gmail.com` has to match a sign-in as `rider@gmail.com`.
 *
 * Returns null when the value isn't usable as an address.
 *
 * Note this accepts any domain, not just @gmail.com: riders sign in with
 * Google, which covers Workspace accounts on custom domains too.
 */
export function normalizeEmail(value) {
  if (typeof value !== 'string') {
    return null;
  }

  const normalized = value.trim().toLowerCase();
  if (normalized.length === 0 || normalized.length > EMAIL_MAX_LENGTH) {
    return null;
  }
  if (!EMAIL_PATTERN.test(normalized)) {
    return null;
  }

  return normalized;
}

export default normalizeEmail;
