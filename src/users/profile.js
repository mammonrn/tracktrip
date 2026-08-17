/**
 * Validation for the editable parts of a rider's profile.
 *
 * Every field is optional in two senses, which are not the same thing:
 * omitting a key leaves that column alone, and sending `null` clears it. The
 * profile screen needs both — it PATCHes only what changed, and a rider who
 * fills in a phone number must be able to take it back out again.
 *
 * `display_name` is the exception: it may be edited but not cleared. It is
 * what every other rider sees in the member list, and a blank one turns into
 * "Rider 7" for everyone else on the trip.
 */

export const DISPLAY_NAME_MIN_LENGTH = 1;
export const DISPLAY_NAME_MAX_LENGTH = 40;
export const NAME_MAX_LENGTH = 40;
export const USERNAME_MIN_LENGTH = 3;
export const USERNAME_MAX_LENGTH = 20;

/**
 * Letters, digits, underscore and dot. No leading or trailing dot, and no two
 * in a row — those are the shapes that read as one name but sort as another.
 */
const USERNAME_PATTERN = /^[a-zA-Z0-9_](?:[a-zA-Z0-9_]|\.(?![.]))*[a-zA-Z0-9_]$/;

/**
 * Digits with optional separators, and an optional leading +.
 *
 * Kept loose on purpose. This is a field a rider fills in for their friends to
 * read, not one the server dials, so a strict E.164 check would reject real
 * numbers written the way Thai riders actually write them (081-234-5678) to
 * buy correctness nobody needs.
 */
const PHONE_PATTERN = /^\+?[0-9](?:[0-9 ()-]{6,18}[0-9])$/;
const PHONE_MIN_DIGITS = 8;
const PHONE_MAX_DIGITS = 15;

const BIRTH_DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/;
const MAX_AGE_YEARS = 120;

/** The fields PATCH /me accepts, and how each is checked. */
const FIELDS = {
  display_name: validateDisplayName,
  first_name: (value) => validateOptionalName(value, 'first_name'),
  last_name: (value) => validateOptionalName(value, 'last_name'),
  username: validateUsername,
  phone: validatePhone,
  birth_date: validateBirthDate,
};

function validateDisplayName(value) {
  if (typeof value !== 'string') {
    return { error: 'display_name must be a string' };
  }
  const trimmed = value.trim();
  if (trimmed.length < DISPLAY_NAME_MIN_LENGTH || trimmed.length > DISPLAY_NAME_MAX_LENGTH) {
    return {
      error: `display_name must be between ${DISPLAY_NAME_MIN_LENGTH} and ${DISPLAY_NAME_MAX_LENGTH} characters`,
    };
  }
  return { value: trimmed };
}

function validateOptionalName(value, field) {
  if (value === null) {
    return { value: null };
  }
  if (typeof value !== 'string') {
    return { error: `${field} must be a string or null` };
  }
  const trimmed = value.trim();
  if (trimmed === '') {
    return { value: null };
  }
  if (trimmed.length > NAME_MAX_LENGTH) {
    return { error: `${field} must be at most ${NAME_MAX_LENGTH} characters` };
  }
  return { value: trimmed };
}

function validateUsername(value) {
  if (value === null) {
    return { value: null };
  }
  if (typeof value !== 'string') {
    return { error: 'username must be a string or null' };
  }
  const trimmed = value.trim();
  if (trimmed === '') {
    return { value: null };
  }
  if (trimmed.length < USERNAME_MIN_LENGTH || trimmed.length > USERNAME_MAX_LENGTH) {
    return {
      error: `username must be between ${USERNAME_MIN_LENGTH} and ${USERNAME_MAX_LENGTH} characters`,
    };
  }
  if (!USERNAME_PATTERN.test(trimmed)) {
    return {
      error: 'username may use letters, digits, underscores and single dots between them',
    };
  }
  return { value: trimmed };
}

function validatePhone(value) {
  if (value === null) {
    return { value: null };
  }
  if (typeof value !== 'string') {
    return { error: 'phone must be a string or null' };
  }
  const trimmed = value.trim();
  if (trimmed === '') {
    return { value: null };
  }

  const digits = trimmed.replace(/\D/g, '');
  if (
    !PHONE_PATTERN.test(trimmed) ||
    digits.length < PHONE_MIN_DIGITS ||
    digits.length > PHONE_MAX_DIGITS
  ) {
    return { error: 'phone must be a phone number' };
  }
  return { value: trimmed };
}

/**
 * A calendar date, in the past, within a human lifetime.
 *
 * The round-trip through Date catches 2025-02-30, which the pattern alone
 * lets through — JavaScript rolls it forward to March rather than rejecting
 * it, so the only reliable check is whether the date it produced is the date
 * that was asked for.
 */
function validateBirthDate(value) {
  if (value === null) {
    return { value: null };
  }
  if (typeof value !== 'string') {
    return { error: 'birth_date must be a string or null' };
  }
  const trimmed = value.trim();
  if (trimmed === '') {
    return { value: null };
  }
  if (!BIRTH_DATE_PATTERN.test(trimmed)) {
    return { error: 'birth_date must be in YYYY-MM-DD format' };
  }

  const parsed = new Date(`${trimmed}T00:00:00.000Z`);
  if (Number.isNaN(parsed.getTime()) || parsed.toISOString().slice(0, 10) !== trimmed) {
    return { error: 'birth_date must be a real date' };
  }

  const now = new Date();
  if (parsed.getTime() > now.getTime()) {
    return { error: 'birth_date must be in the past' };
  }
  const oldest = new Date(now.getTime());
  oldest.setUTCFullYear(oldest.getUTCFullYear() - MAX_AGE_YEARS);
  if (parsed.getTime() < oldest.getTime()) {
    return { error: `birth_date must be within the last ${MAX_AGE_YEARS} years` };
  }

  return { value: trimmed };
}

/**
 * Validates a PATCH /me body.
 *
 * Returns `{ error }` with a message for the first field that fails, or
 * `{ value }` with only the columns the caller actually sent — an empty object
 * when they sent nothing this module recognises, which is a no-op rather than
 * an error. Unknown keys are ignored, not rejected: a newer client sending a
 * field this build has never heard of shouldn't have its whole save refused.
 */
export function validateProfilePatch(body) {
  const payload = body || {};
  const updates = {};

  for (const [field, validate] of Object.entries(FIELDS)) {
    if (!Object.prototype.hasOwnProperty.call(payload, field)) {
      continue;
    }
    const { error, value } = validate(payload[field]);
    if (error) {
      return { error };
    }
    updates[field] = value;
  }

  return { value: updates };
}

/**
 * Whether some other rider already holds this username.
 *
 * Matched case-insensitively, against the same expression the unique index
 * uses — so this and the database agree on what a collision is, and the
 * 409 arrives instead of a constraint error.
 */
export function usernameTaken(db, username, userId) {
  if (username === null || username === undefined) {
    return false;
  }
  const clash = db
    .prepare('SELECT 1 FROM users WHERE lower(username) = lower(?) AND id != ?')
    .get(username, userId);
  return Boolean(clash);
}
