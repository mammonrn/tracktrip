/**
 * How long a rider can choose to keep sharing for, in minutes.
 *
 * A fixed set rather than a free number: these are the buttons on the sharing
 * sheet, and letting a client post any duration would make "how long could
 * this rider still be broadcasting?" unanswerable without reading the row.
 * Retuning the offer means editing this list.
 */
export const SHARING_DURATION_MINUTES = [30, 60, 240];

const MS_PER_MINUTE = 60 * 1000;

/**
 * Validates a POST /trips/:id/share/start body.
 *
 * `duration_minutes` may be one of {@link SHARING_DURATION_MINUTES}, or null
 * for "until I turn it off". Omitting the field entirely is *not* the same as
 * null — a client that forgot to send it would otherwise silently get an
 * unlimited session, which is the one outcome worth being explicit about.
 */
export function validateShareStartInput(body) {
  const payload = body || {};

  if (!Object.prototype.hasOwnProperty.call(payload, 'duration_minutes')) {
    return {
      error:
        'duration_minutes is required: ' +
        `${SHARING_DURATION_MINUTES.join(', ')}, or null to share until stopped`,
    };
  }

  const duration = payload.duration_minutes;
  if (duration === null) {
    return { value: { durationMinutes: null } };
  }
  if (!SHARING_DURATION_MINUTES.includes(duration)) {
    return {
      error:
        `duration_minutes must be one of ${SHARING_DURATION_MINUTES.join(', ')}, ` +
        'or null to share until stopped',
    };
  }

  return { value: { durationMinutes: duration } };
}

/** When a session started now with this duration would lapse; null if never. */
export function expiryFor(durationMinutes, now = new Date()) {
  if (durationMinutes === null) {
    return null;
  }
  return new Date(now.getTime() + durationMinutes * MS_PER_MINUTE).toISOString();
}

/**
 * Whether a session row still counts as sharing.
 *
 * Expired rows are left in place rather than deleted on a timer, so this is
 * the single place that decides — used by the write guard and by the reads
 * that report who is live, which must never disagree.
 */
export function isSessionOpen(session, nowIso = new Date().toISOString()) {
  if (!session) {
    return false;
  }
  return session.expires_at === null || session.expires_at > nowIso;
}

/** Shapes a sharing session for API responses. */
export function serializeSharingSession(tripId, userId, session, nowIso = new Date().toISOString()) {
  return {
    trip_id: tripId,
    user_id: userId,
    sharing: isSessionOpen(session, nowIso),
    started_at: session?.started_at ?? null,
    expires_at: session?.expires_at ?? null,
  };
}
