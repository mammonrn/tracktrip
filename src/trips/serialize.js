/**
 * Shapes a trips row for API responses. `extra` carries fields that aren't on
 * the row itself but that the caller has already resolved — typically the
 * requesting user's `role` in the trip.
 */
export function serializeTrip(trip, extra = {}) {
  return {
    id: trip.id,
    name: trip.name,
    owner_id: trip.owner_id,
    status: trip.status,
    created_at: trip.created_at,
    ended_at: trip.ended_at,
    // Where the trip starts and ends, or null. Nested rather than six flat
    // fields: a client asking "is there a destination?" should have one thing
    // to check, not three that could disagree. Null means not set — the two
    // are independent, so a trip may have a destination and no origin.
    origin: serializeEndpoint(trip.origin_lat, trip.origin_lng, trip.origin_label),
    destination: serializeEndpoint(
      trip.destination_lat,
      trip.destination_lng,
      trip.destination_label
    ),
    ...extra,
  };
}

/**
 * One end of a trip, or null when it was never set.
 *
 * The coordinate decides: a row can only hold a label without a coordinate if
 * something wrote around the API, and a label alone cannot be drawn.
 */
function serializeEndpoint(lat, lng, label) {
  if (lat === null || lat === undefined || lng === null || lng === undefined) {
    return null;
  }
  return { lat, lng, label: label ?? null };
}

/**
 * Shapes a trip_invites row for API responses. `extra` is used the same way
 * as in serializeTrip — e.g. the trip's name when listing an invitee's
 * pending invites.
 */
export function serializeInvite(invite, extra = {}) {
  return {
    id: invite.id,
    trip_id: invite.trip_id,
    email: invite.email,
    invited_by: invite.invited_by,
    status: invite.status,
    created_at: invite.created_at,
    accepted_at: invite.accepted_at,
    accepted_by: invite.accepted_by,
    ...extra,
  };
}
